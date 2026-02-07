package com.example.ghostlib.util;

import com.example.ghostlib.block.entity.GhostBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class GhostJobManager {
    private static final Map<Level, GhostJobManager> INSTANCES = new ConcurrentHashMap<>();

    public enum JobType {
        CONSTRUCTION,
        GHOST_REMOVAL,
        DIRECT_DECONSTRUCT
    }

    public record Job(BlockPos pos, JobType type, BlockState targetAfter, BlockState finalState) {
    }

    private final Map<Long, Map<BlockPos, BlockState>> constructionJobs = new ConcurrentHashMap<>();
    private final Map<Long, Set<BlockPos>> ghostRemovalJobs = new ConcurrentHashMap<>();
    private final Map<Long, Map<BlockPos, BlockState>> directDeconstructJobs = new ConcurrentHashMap<>();
    private final Map<Long, Map<BlockPos, BlockState>> hibernatingJobs = new ConcurrentHashMap<>();
    private final Map<BlockPos, BlockState> jobFinalStates = new ConcurrentHashMap<>();
    private final Map<BlockPos, UUID> assignedPositions = new ConcurrentHashMap<>();
    private final Map<Long, Set<BlockPos>> assignedInChunk = new ConcurrentHashMap<>();

    private boolean dirty = false;
    private GhostJobSavedData savedData = null;

    public static GhostJobManager get(Level level) {
        return INSTANCES.computeIfAbsent(level, k -> {
            GhostJobManager manager = new GhostJobManager();
            if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
                manager.savedData = GhostJobSavedData.getOrCreate(serverLevel, manager);
            }
            return manager;
        });
    }

    public void registerJob(BlockPos pos, GhostBlockEntity.GhostState state, BlockState target) {
        long key = ChunkPos.asLong(pos);
        pos = pos.immutable();

        // 1. Determine if we should clear the drone assignment
        boolean clearAssignment = (state == GhostBlockEntity.GhostState.UNASSIGNED
                || state == GhostBlockEntity.GhostState.TO_REMOVE
                || state == GhostBlockEntity.GhostState.MISSING_ITEMS);

        // 2. Always clean up existing job state maps first to prevent duplicates/stale data
        removeFromAllMaps(pos, clearAssignment);

        // 3. Register based on new state
        if (state == GhostBlockEntity.GhostState.TO_REMOVE || state == GhostBlockEntity.GhostState.REMOVING) {
            ghostRemovalJobs.computeIfAbsent(key, k -> Collections.synchronizedSet(new LinkedHashSet<>())).add(pos);
            // Even if removing, keep construction intent if target is valid
            if (target != null && !target.isAir()) {
                constructionJobs.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(pos, target);
            }
            dirty = true;
            markDataDirty();
        } else if (state == GhostBlockEntity.GhostState.MISSING_ITEMS) {
            hibernatingJobs.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(pos, target);
            markDataDirty();
        } else {
            // All other building-related states (UNASSIGNED, ASSIGNED, FETCHING, INCOMING)
            if (target != null && !target.isAir()) {
                constructionJobs.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(pos, target);
                markDataDirty();
            }
        }
    }

    private void removeFromAllMaps(BlockPos pos, boolean clearAssignment) {
        long key = ChunkPos.asLong(pos);
        if (constructionJobs.containsKey(key)) constructionJobs.get(key).remove(pos);
        if (ghostRemovalJobs.containsKey(key)) ghostRemovalJobs.get(key).remove(pos);
        if (directDeconstructJobs.containsKey(key)) directDeconstructJobs.get(key).remove(pos);
        if (hibernatingJobs.containsKey(key)) hibernatingJobs.get(key).remove(pos);
        
        if (clearAssignment) {
            assignedPositions.remove(pos);
            Set<BlockPos> chunkAssignments = assignedInChunk.get(key);
            if (chunkAssignments != null) {
                chunkAssignments.remove(pos);
                if (chunkAssignments.isEmpty()) assignedInChunk.remove(key);
            }
            jobFinalStates.remove(pos);
        }
    }

    public void registerDirectDeconstruct(BlockPos pos, BlockState targetAfter, Level level) {
        registerDirectDeconstruct(pos, targetAfter, null, level);
    }

    public void registerDirectDeconstruct(BlockPos pos, BlockState targetAfter, BlockState finalState, Level level) {
        long key = ChunkPos.asLong(pos);
        pos = pos.immutable();
        removeFromAllMaps(pos, true);
        directDeconstructJobs.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(pos, targetAfter);
        if (finalState != null) jobFinalStates.put(pos, finalState);
        dirty = true;
        markDataDirty();
        syncToClients(level);
    }

    public void removeJob(BlockPos pos) {
        removeFromAllMaps(pos, true);
        markDataDirty();
        // Fallback sync if we have a level reference, but typically removeJob is called within a context that syncs
    }

    public void completeJob(BlockPos pos, Level level) {
        removeFromAllMaps(pos, true);
        markDataDirty();
        syncToClients(level);
    }

    public void tick(Level level) {
        if (level.isClientSide || level.getGameTime() % 20 != 0) return; // Every 1 second
        
        for (Map<BlockPos, BlockState> chunkMap : hibernatingJobs.values()) {
            for (BlockPos pos : new ArrayList<>(chunkMap.keySet())) {
                BlockState target = chunkMap.get(pos);
                if (target == null) continue;

                ItemStack required = new ItemStack(target.getBlock().asItem());
                if (isItemAvailableInNetwork(level, pos, required)) {
                    // Item found! Transition to UNASSIGNED to wake up the job.
                    if (level.getBlockEntity(pos) instanceof GhostBlockEntity gbe) {
                        // This will trigger a registerJob call which moves it back to constructionJobs
                        gbe.setState(GhostBlockEntity.GhostState.UNASSIGNED);
                        com.example.ghostlib.util.GhostLogger.log("JOB", "Waking up halted ghost at " + pos + " - Items now available.");
                    } else {
                        removeFromAllMaps(pos, true);
                    }
                }
            }
        }
    }

    private boolean isItemAvailableInNetwork(Level level, BlockPos pos, ItemStack required) {
        // 1. Check nearby players
        for (Player player : level.players()) {
            if (player.blockPosition().distSqr(pos) < 4096) { // 64 block range
                for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                    if (player.getInventory().getItem(i).is(required.getItem())) return true;
                }
            }
        }
        
        // 2. Check Logistics Network
        LogisticsNetworkManager networkManager = LogisticsNetworkManager.get(level);
        if (networkManager != null) {
            Integer networkId = networkManager.getNetworkId(pos);
            if (networkId == null) {
                // Try searching for any network in range
                for (int dx = -16; dx <= 16; dx += 8) {
                    for (int dy = -8; dy <= 8; dy += 4) {
                        for (int dz = -16; dz <= 16; dz += 8) {
                            Integer nearbyId = networkManager.getNetworkId(pos.offset(dx, dy, dz));
                            if (nearbyId != null) {
                                networkId = nearbyId;
                                break;
                            }
                        }
                        if (networkId != null) break;
                    }
                    if (networkId != null) break;
                }
            }

            if (networkId != null) {
                Set<BlockPos> members = networkManager.getNetworkMembers(networkId);
                for (BlockPos memberPos : members) {
                    if (level.isLoaded(memberPos)) {
                        net.neoforged.neoforge.items.IItemHandler handler = level.getCapability(
                            net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, 
                            memberPos, 
                            null
                        );
                        if (handler != null) {
                            for (int i = 0; i < handler.getSlots(); i++) {
                                if (handler.getStackInSlot(i).is(required.getItem())) return true;
                            }
                        }
                    }
                }
            }
        }
        
        return false; 
    }

    public boolean isDeconstructAt(BlockPos pos) {
        long key = ChunkPos.asLong(pos);
        return directDeconstructJobs.containsKey(key) && directDeconstructJobs.get(key).containsKey(pos);
    }

    public BlockState getTargetAfterDeconstruct(BlockPos pos) {
        long key = ChunkPos.asLong(pos);
        return directDeconstructJobs.containsKey(key) ? directDeconstructJobs.get(key).get(pos) : null;
    }

    public void restoreAssignment(BlockPos pos, UUID droneId) {
        assignedPositions.put(pos, droneId);
        assignedInChunk.computeIfAbsent(ChunkPos.asLong(pos), k -> ConcurrentHashMap.newKeySet()).add(pos);
    }

    public Map<BlockPos, BlockState> getConstructionJobs() {
        Map<BlockPos, BlockState> all = new HashMap<>();
        constructionJobs.values().forEach(all::putAll);
        return all;
    }

    public Map<Long, Map<BlockPos, BlockState>> getConstructionJobsMap() {
        return constructionJobs;
    }

    public Map<Long, Set<BlockPos>> getGhostRemovalJobsMap() {
        return ghostRemovalJobs;
    }

    public Map<Long, Map<BlockPos, BlockState>> getDirectDeconstructJobs() {
        return directDeconstructJobs;
    }

    public Map<Long, Map<BlockPos, BlockState>> getHibernatingJobsMap() {
        return hibernatingJobs;
    }

    public Map<BlockPos, BlockState> getHibernatingJobs() {
        Map<BlockPos, BlockState> all = new HashMap<>();
        hibernatingJobs.values().forEach(all::putAll);
        return all;
    }

    public Map<BlockPos, BlockState> getJobFinalStates() {
        return jobFinalStates;
    }

    public Map<BlockPos, UUID> getAssignments() {
        return assignedPositions;
    }

    public void syncToClients(Level level) {
        if (level.isClientSide || !(level instanceof ServerLevel serverLevel)) return;
        
        Map<BlockPos, Integer> syncMap = new HashMap<>();
        for (Map<BlockPos, BlockState> chunkMap : directDeconstructJobs.values()) {
            for (Map.Entry<BlockPos, BlockState> entry : chunkMap.entrySet()) {
                syncMap.put(entry.getKey(), net.minecraft.world.level.block.Block.getId(entry.getValue()));
            }
        }
        
        com.example.ghostlib.network.payload.S2CSyncDeconstructionPacket packet = new com.example.ghostlib.network.payload.S2CSyncDeconstructionPacket(syncMap);
        for (net.minecraft.server.level.ServerPlayer player : serverLevel.players()) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, packet);
        }
    }

    public void releaseAssignmentsInChunk(long chunkKey) {
        Set<BlockPos> positions = assignedInChunk.remove(chunkKey);
        if (positions != null) {
            positions.forEach(assignedPositions::remove);
        }
    }

    public boolean hasAvailableJob(BlockPos pos, int range) {
        return requestJob(pos, UUID.randomUUID(), true) != null;
    }

    public boolean jobExistsAt(BlockPos pos) {
        if (assignedPositions.containsKey(pos)) return true;
        long key = ChunkPos.asLong(pos);
        if (constructionJobs.containsKey(key) && constructionJobs.get(key).containsKey(pos)) return true;
        if (ghostRemovalJobs.containsKey(key) && ghostRemovalJobs.get(key).contains(pos)) return true;
        if (directDeconstructJobs.containsKey(key) && directDeconstructJobs.get(key).containsKey(pos)) return true;
        if (hibernatingJobs.containsKey(key) && hibernatingJobs.get(key).containsKey(pos)) return true;
        return false;
    }

    public boolean isAssignedTo(BlockPos pos, UUID droneId) {
        UUID current = assignedPositions.get(pos);
        return current != null && current.equals(droneId);
    }

    public void reassignJob(BlockPos pos, UUID oldId, UUID newId) {
        UUID current = assignedPositions.get(pos);
        if (current != null && current.equals(oldId)) {
            assignedPositions.put(pos, newId);
        }
    }

    public void releaseJob(BlockPos pos, UUID droneId) {
        UUID current = assignedPositions.get(pos);
        if (current != null && current.equals(droneId)) {
            assignedPositions.remove(pos);
            long key = ChunkPos.asLong(pos);
            Set<BlockPos> chunkAssignments = assignedInChunk.get(key);
            if (chunkAssignments != null) {
                chunkAssignments.remove(pos);
                if (chunkAssignments.isEmpty()) assignedInChunk.remove(key);
            }
        }
    }

    public Job requestJob(BlockPos dronePos, UUID droneId, boolean canBuild) {
        int cx = SectionPos.blockToSectionCoord(dronePos.getX());
        int cz = SectionPos.blockToSectionCoord(dronePos.getZ());
        // Optimized range: 8 sections (128 blocks) is plenty for technical mods
        for (int r = 0; r <= 8; r++) {
            Job job = searchRing(cx, cz, r, dronePos, droneId, canBuild);
            if (job != null) return job;
        }
        return null;
    }

    private Job searchRing(int cx, int cz, int r, BlockPos dronePos, UUID droneId, boolean canBuild) {
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                if (r > 0 && Math.abs(x - cx) < r && Math.abs(z - cz) < r) continue;
                long key = ChunkPos.asLong(x, z);
                
                // Prioritize Deconstruction/Removal over Construction
                Job j = findInMap(directDeconstructJobs.get(key), dronePos, droneId, JobType.DIRECT_DECONSTRUCT);
                if (j != null) return j;
                
                j = findInSet(ghostRemovalJobs.get(key), dronePos, droneId, JobType.GHOST_REMOVAL);
                if (j != null) return j;
                
                if (canBuild) {
                    j = findInMap(constructionJobs.get(key), dronePos, droneId, JobType.CONSTRUCTION);
                    if (j != null) return j;
                }
            }
        }
        return null;
    }

    private Job findInMap(Map<BlockPos, BlockState> map, BlockPos dronePos, UUID droneId, JobType type) {
        if (map == null || map.isEmpty()) return null;
        for (Map.Entry<BlockPos, BlockState> entry : map.entrySet()) {
            BlockPos pos = entry.getKey();
            if (!assignedPositions.containsKey(pos)) {
                if (assignedPositions.putIfAbsent(pos, droneId) == null) {
                    assignedInChunk.computeIfAbsent(ChunkPos.asLong(pos), k -> ConcurrentHashMap.newKeySet()).add(pos);
                    return new Job(pos, type, entry.getValue(), jobFinalStates.get(pos));
                }
            }
        }
        return null;
    }

    private Job findInSet(Set<BlockPos> set, BlockPos dronePos, UUID droneId, JobType type) {
        if (set == null || set.isEmpty()) return null;
        synchronized (set) {
            for (BlockPos pos : set) {
                if (!assignedPositions.containsKey(pos)) {
                    if (assignedPositions.putIfAbsent(pos, droneId) == null) {
                        assignedInChunk.computeIfAbsent(ChunkPos.asLong(pos), k -> ConcurrentHashMap.newKeySet()).add(pos);
                        return new Job(pos, type, Blocks.AIR.defaultBlockState(), null);
                    }
                }
            }
        }
        return null;
    }

    private void markDataDirty() {
        if (savedData != null) savedData.setDirty();
    }
}