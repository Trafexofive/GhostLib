package com.example.ghostlib.util;

import com.example.ghostlib.block.entity.GhostBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class GhostJobManager {
    private static final Map<Level, GhostJobManager> INSTANCES = new ConcurrentHashMap<>();

    public enum JobType { CONSTRUCTION, GHOST_REMOVAL, DIRECT_DECONSTRUCT }
    public record Job(BlockPos pos, JobType type, BlockState targetAfter) {}

    private final Map<Long, Map<BlockPos, BlockState>> constructionJobs = new ConcurrentHashMap<>();
    private final Map<Long, Set<BlockPos>> ghostRemovalJobs = new ConcurrentHashMap<>(); 
    private final Map<Long, Map<BlockPos, BlockState>> directDeconstructJobs = new ConcurrentHashMap<>();
    private final Map<Long, Map<BlockPos, BlockState>> hibernatingJobs = new ConcurrentHashMap<>();
    
    private final Map<BlockPos, UUID> assignedPositions = new ConcurrentHashMap<>();
    private boolean dirty = false;

    public static GhostJobManager get(Level level) {
        return INSTANCES.computeIfAbsent(level, k -> new GhostJobManager());
    }

    public void registerJob(BlockPos pos, GhostBlockEntity.GhostState state, BlockState target) {
        long key = ChunkPos.asLong(pos);
        pos = pos.immutable();
        
        // Only clear assignment if the state implies no drone is working on it
        boolean clearAssignment = (state == GhostBlockEntity.GhostState.UNASSIGNED 
                                || state == GhostBlockEntity.GhostState.TO_REMOVE 
                                || state == GhostBlockEntity.GhostState.MISSING_ITEMS);
        
        removeFromAllMaps(pos, clearAssignment);

        if (state == GhostBlockEntity.GhostState.TO_REMOVE) {
            ghostRemovalJobs.computeIfAbsent(key, k -> Collections.synchronizedSet(new LinkedHashSet<>())).add(pos);
            dirty = true;
        } else if (state == GhostBlockEntity.GhostState.UNASSIGNED) {
            constructionJobs.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(pos, target);
        } else if (state == GhostBlockEntity.GhostState.MISSING_ITEMS) {
            hibernatingJobs.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(pos, target);
        }
    }

    private void removeFromAllMaps(BlockPos pos, boolean clearAssignment) {
        long key = ChunkPos.asLong(pos);
        if (constructionJobs.containsKey(key)) constructionJobs.get(key).remove(pos);
        
        if (ghostRemovalJobs.containsKey(key)) {
             if (ghostRemovalJobs.get(key).remove(pos)) dirty = true;
        }
        
        if (directDeconstructJobs.containsKey(key)) {
             if (directDeconstructJobs.get(key).remove(pos) != null) dirty = true;
        }
        
        if (hibernatingJobs.containsKey(key)) hibernatingJobs.get(key).remove(pos);
        if (clearAssignment) {
            assignedPositions.remove(pos);
        }
    }

    public void registerDirectDeconstruct(BlockPos pos, BlockState targetAfter, Level level) {
        long key = ChunkPos.asLong(pos);
        pos = pos.immutable();
        removeFromAllMaps(pos, true);
        directDeconstructJobs.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(pos, targetAfter);
        dirty = true;
    }

    public void removeJob(BlockPos pos) {
        removeFromAllMaps(pos, true);
    }

    public void releaseJob(BlockPos pos, UUID droneId) {
        if (pos == null) return;
        UUID current = assignedPositions.get(pos);
        if (current != null && current.equals(droneId)) assignedPositions.remove(pos);
    }

    public boolean isAssignedTo(BlockPos pos, UUID droneId) {
        if (pos == null || droneId == null) return false;
        UUID current = assignedPositions.get(pos);
        return current != null && current.equals(droneId);
    }

    public Job requestJob(BlockPos dronePos, UUID droneId, boolean canBuild) {
        int cx = SectionPos.blockToSectionCoord(dronePos.getX());
        int cz = SectionPos.blockToSectionCoord(dronePos.getZ());

        for (int r = 0; r <= 6; r++) {
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
        BlockPos best = null;
        double minDist = Double.MAX_VALUE;
        for (BlockPos p : map.keySet()) {
            if (assignedPositions.containsKey(p)) continue;
            double d = dronePos.distSqr(p);
            if (d < minDist) { minDist = d; best = p; }
        }
        if (best != null) {
            assignedPositions.put(best, droneId);
            return new Job(best, type, map.get(best));
        }
        return null;
    }

    private Job findInSet(Set<BlockPos> set, BlockPos dronePos, UUID droneId, JobType type) {
        if (set == null || set.isEmpty()) return null;
        BlockPos best = null;
        double minDist = Double.MAX_VALUE;
        synchronized (set) {
            for (BlockPos p : set) {
                if (assignedPositions.containsKey(p)) continue;
                double d = dronePos.distSqr(p);
                if (d < minDist) { minDist = d; best = p; }
            }
        }
        if (best != null) {
            assignedPositions.put(best, droneId);
            return new Job(best, type, Blocks.AIR.defaultBlockState());
        }
        return null;
    }

    public void tick(Level level) {
        if (level.isClientSide) return;
        
        if (dirty || level.getGameTime() % 100 == 0) {
            syncToClients(level);
            dirty = false;
        }

        // Wake up hibernating jobs every 5 seconds (100 ticks)
        if (level.getGameTime() % 100 == 0) {
            wakeUpHibernatingJobs(level);
        }
    }

    private void wakeUpHibernatingJobs(Level level) {
        for (Map.Entry<Long, Map<BlockPos, BlockState>> entry : hibernatingJobs.entrySet()) {
            long key = entry.getKey();
            Map<BlockPos, BlockState> jobs = entry.getValue();
            if (jobs.isEmpty()) continue;

            // Move all hibernating jobs in this chunk to construction queue
            // We use a copy to avoid concurrent modification exceptions if needed, but ConcurrentHashMap is safe for iteration
            for (Map.Entry<BlockPos, BlockState> job : jobs.entrySet()) {
                BlockPos pos = job.getKey();
                BlockState target = job.getValue();
                
                // Update the BlockEntity state to UNASSIGNED (Deep Blue)
                if (level.getBlockEntity(pos) instanceof GhostBlockEntity gbe) {
                    gbe.setState(GhostBlockEntity.GhostState.UNASSIGNED);
                } else {
                    // If the BE is gone, just register it directly (fallback) or remove it?
                    // If BE is gone, the job is invalid.
                    removeFromAllMaps(pos, true);
                }
            }
        }
    }

    public void syncToClients(Level level) {
        if (level.isClientSide) return;
        Map<BlockPos, Boolean> active = new HashMap<>();
        for (Map<BlockPos, BlockState> map : directDeconstructJobs.values()) {
            for (BlockPos p : map.keySet()) active.put(p, true);
        }
        for (Set<BlockPos> set : ghostRemovalJobs.values()) {
            synchronized (set) { for (BlockPos p : set) active.put(p, true); }
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToAllPlayers(new com.example.ghostlib.network.payload.S2CSyncDeconstructionPacket(active));
    }

    public Map<Long, Map<BlockPos, BlockState>> getDirectDeconstructJobs() { return directDeconstructJobs; }
    public BlockState getTargetAfterDeconstruct(BlockPos pos) {
        long key = ChunkPos.asLong(pos);
        Map<BlockPos, BlockState> map = directDeconstructJobs.get(key);
        return map != null ? map.get(pos) : null;
    }
}
