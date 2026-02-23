package com.example.ghostlib.util;

import com.example.ghostlib.block.entity.GhostBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages all pending drone jobs, partitioned by chunk for O(R²) search.
 *
 * <h2>Concurrency Model</h2>
 * All five job maps ({@code constructionJobs}, {@code ghostRemovalJobs},
 * {@code directDeconstructJobs}, {@code hibernatingJobs}, {@code assignedPositions})
 * are {@link ConcurrentHashMap} instances. Assignment uses {@code putIfAbsent} for
 * lock-free atomic claiming — no external {@code synchronized} blocks are needed or
 * present. Callers that iterate and then mutate must tolerate TOCTOU races; the only
 * truly safe claim is through {@link #requestJob}.
 *
 * <h2>Job Priority</h2>
 * Within a chunk: Deconstruct > GhostRemoval > Construction.
 * Between chunks: nearest chunk to the requesting drone wins.
 * Within a chunk map, iteration order is arbitrary (ConcurrentHashMap).
 *
 * <h2>Load-Safe Path</h2>
 * During SavedData load, no {@link Level} is available.
 * Use {@link #registerJobSafe} and {@link #registerDirectDeconstructSafe}
 * instead of their Level-taking counterparts to skip client sync.
 */
public class GhostJobManager {

    private static final Map<Level, GhostJobManager> INSTANCES = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Job type
    // -------------------------------------------------------------------------

    public enum JobType {
        CONSTRUCTION,
        GHOST_REMOVAL,
        DIRECT_DECONSTRUCT
    }

    public record Job(BlockPos pos, JobType type, BlockState targetAfter, BlockState finalState) {}

    // -------------------------------------------------------------------------
    // State maps — keyed by ChunkPos.asLong(blockPos)
    // -------------------------------------------------------------------------

    /** Blocks that a drone must BUILD. Value = target BlockState. */
    private final Map<Long, Map<BlockPos, BlockState>> constructionJobs      = new ConcurrentHashMap<>();
    /** Ghost blocks that need to be physically removed (no target). */
    private final Map<Long, Set<BlockPos>>             ghostRemovalJobs       = new ConcurrentHashMap<>();
    /** Real blocks that must be physically broken. Value = state to place after. */
    private final Map<Long, Map<BlockPos, BlockState>> directDeconstructJobs  = new ConcurrentHashMap<>();
    /** Jobs paused because required items are unavailable. */
    private final Map<Long, Map<BlockPos, BlockState>> hibernatingJobs        = new ConcurrentHashMap<>();
    /** Optional second target for chained deconstruct→construct. */
    private final Map<BlockPos, BlockState>            jobFinalStates         = new ConcurrentHashMap<>();
    /** Which drone UUID has claimed each position. Atomic via putIfAbsent. */
    private final Map<BlockPos, UUID>                  assignedPositions      = new ConcurrentHashMap<>();
    /** Reverse index: chunk → set of assigned positions (for bulk release on unload). */
    private final Map<Long, Set<BlockPos>>             assignedInChunk        = new ConcurrentHashMap<>();

    private GhostJobSavedData savedData = null;

    // -------------------------------------------------------------------------
    // Singleton factory
    // -------------------------------------------------------------------------

    public static GhostJobManager get(Level level) {
        return INSTANCES.computeIfAbsent(level, k -> {
            GhostJobManager mgr = new GhostJobManager();
            if (!level.isClientSide() && level instanceof ServerLevel sl) {
                mgr.savedData = GhostJobSavedData.getOrCreate(sl, mgr);
            }
            return mgr;
        });
    }

    // -------------------------------------------------------------------------
    // Registration — normal (requires Level for client sync)
    // -------------------------------------------------------------------------

    /**
     * Register or update a job driven by a {@link GhostBlockEntity} state change.
     * Always called from server-side block-entity code where a Level is available.
     */
    public void registerJob(BlockPos pos, GhostBlockEntity.GhostState state, BlockState target) {
        long key = ChunkPos.asLong(pos);
        pos = pos.immutable();

        boolean clearAssignment = state == GhostBlockEntity.GhostState.UNASSIGNED
                || state == GhostBlockEntity.GhostState.TO_REMOVE
                || state == GhostBlockEntity.GhostState.MISSING_ITEMS;

        removeFromAllMaps(pos, clearAssignment);

        if (state == GhostBlockEntity.GhostState.TO_REMOVE
                || state == GhostBlockEntity.GhostState.REMOVING) {
            ghostRemovalJobs.computeIfAbsent(key, k -> Collections.synchronizedSet(new LinkedHashSet<>())).add(pos);
            if (target != null && !target.isAir()) {
                constructionJobs.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(pos, target);
            }
        } else if (state == GhostBlockEntity.GhostState.MISSING_ITEMS) {
            if (target != null && !target.isAir()) {
                hibernatingJobs.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(pos, target);
            }
        } else {
            // UNASSIGNED / ASSIGNED / FETCHING / INCOMING
            if (target != null && !target.isAir()) {
                constructionJobs.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(pos, target);
            }
        }

        markDataDirty();
    }

    /**
     * Load-safe variant — skips client sync. Use during SavedData deserialization
     * when no Level reference is available.
     */
    public void registerJobSafe(BlockPos pos, GhostBlockEntity.GhostState state, BlockState target) {
        registerJob(pos, state, target); // registerJob itself never calls syncToClients; safe.
    }

    // -------------------------------------------------------------------------
    // Direct deconstruction registration
    // -------------------------------------------------------------------------

    public void registerDirectDeconstruct(BlockPos pos, BlockState targetAfter, Level level) {
        registerDirectDeconstruct(pos, targetAfter, null, level);
    }

    public void registerDirectDeconstruct(BlockPos pos, BlockState targetAfter, BlockState finalState, Level level) {
        long key = ChunkPos.asLong(pos);
        pos = pos.immutable();
        removeFromAllMaps(pos, true);
        directDeconstructJobs.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(pos, targetAfter);
        if (finalState != null) jobFinalStates.put(pos, finalState);
        markDataDirty();

        // Sync is optional — level may be null during load reconstruction.
        if (level != null) syncToClients(level);
    }

    /**
     * Load-safe variant — skips client sync.
     */
    public void registerDirectDeconstructSafe(BlockPos pos, BlockState targetAfter, BlockState finalState) {
        long key = ChunkPos.asLong(pos);
        pos = pos.immutable();
        removeFromAllMaps(pos, true);
        directDeconstructJobs.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(pos, targetAfter);
        if (finalState != null) jobFinalStates.put(pos, finalState);
        markDataDirty();
    }

    // -------------------------------------------------------------------------
    // Job lifecycle
    // -------------------------------------------------------------------------

    public void removeJob(BlockPos pos) {
        removeFromAllMaps(pos, true);
        markDataDirty();
    }

    public void completeJob(BlockPos pos, Level level) {
        removeFromAllMaps(pos, true);
        markDataDirty();
        if (level != null) syncToClients(level);
    }

    /**
     * Release a drone's claim on a position. No-op if {@code droneId} does not
     * match the current claimant (prevents stale releases from raced-out drones).
     */
    public void releaseJob(BlockPos pos, UUID droneId) {
        // ConcurrentHashMap.remove(key, value) is atomic — only removes if value matches.
        if (assignedPositions.remove(pos, droneId)) {
            long key = ChunkPos.asLong(pos);
            Set<BlockPos> chunkSet = assignedInChunk.get(key);
            if (chunkSet != null) {
                chunkSet.remove(pos);
                if (chunkSet.isEmpty()) assignedInChunk.remove(key);
            }
        }
    }

    /**
     * Atomically transfer a job claim from {@code oldId} to {@code newId}.
     * Used by DronePort when a port-UUID placeholder is replaced by a real drone.
     */
    public void reassignJob(BlockPos pos, UUID oldId, UUID newId) {
        assignedPositions.replace(pos, oldId, newId);
    }

    /**
     * Restore a previously persisted assignment. Called during SavedData load.
     * Does not validate whether the drone UUID is still alive.
     */
    public void restoreAssignment(BlockPos pos, UUID droneId) {
        assignedPositions.put(pos, droneId);
        assignedInChunk.computeIfAbsent(ChunkPos.asLong(pos), k -> ConcurrentHashMap.newKeySet()).add(pos);
    }

    // -------------------------------------------------------------------------
    // Tick — wake hibernating jobs when items become available
    // -------------------------------------------------------------------------

    public void tick(Level level) {
        if (level.isClientSide || level.getGameTime() % 20 != 0) return;

        for (Map<BlockPos, BlockState> chunkMap : hibernatingJobs.values()) {
            for (BlockPos pos : new ArrayList<>(chunkMap.keySet())) {
                BlockState target = chunkMap.get(pos);
                if (target == null) continue;
                if (isItemAvailableInNetwork(level, pos, new ItemStack(target.getBlock().asItem()))) {
                    if (level.getBlockEntity(pos) instanceof GhostBlockEntity gbe) {
                        gbe.setState(GhostBlockEntity.GhostState.UNASSIGNED);
                        com.example.ghostlib.util.GhostLogger.log("JOB",
                                "Waking hibernated job at " + pos + " — items now available.");
                    } else {
                        removeFromAllMaps(pos, true);
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Job querying
    // -------------------------------------------------------------------------

    /**
     * Claim the nearest available job within {@code range} chunks of {@code dronePos}.
     *
     * <p>Priority within each candidate chunk: Deconstruct → GhostRemoval → Construction.
     * Within each priority map, selection order is arbitrary (ConcurrentHashMap ordering).
     *
     * @param canBuild whether this drone is allowed to take construction jobs
     * @return a claimed {@link Job}, or {@code null} if nothing is available
     */
    public Job requestJob(BlockPos dronePos, UUID droneId, boolean canBuild) {
        int cx = SectionPos.blockToSectionCoord(dronePos.getX());
        int cz = SectionPos.blockToSectionCoord(dronePos.getZ());
        int range = 8;

        // Collect only chunks that actually contain jobs — avoids scanning 289 mostly-empty slots.
        Set<Long> candidateChunks = new HashSet<>();
        candidateChunks.addAll(directDeconstructJobs.keySet());
        candidateChunks.addAll(ghostRemovalJobs.keySet());
        if (canBuild) candidateChunks.addAll(constructionJobs.keySet());

        if (candidateChunks.isEmpty()) return null;

        List<Long> sorted = candidateChunks.stream()
                .filter(key -> {
                    int kx = ChunkPos.getX(key);
                    int kz = ChunkPos.getZ(key);
                    return Math.abs(kx - cx) <= range && Math.abs(kz - cz) <= range;
                })
                .sorted(Comparator.comparingDouble(key -> {
                    double dx = ChunkPos.getX(key) - cx;
                    double dz = ChunkPos.getZ(key) - cz;
                    return dx * dx + dz * dz;
                }))
                .toList();

        for (Long key : sorted) {
            // Priority: deconstruct > ghost removal > construction
            Job j = findInMap(directDeconstructJobs.get(key), droneId, JobType.DIRECT_DECONSTRUCT);
            if (j != null) return j;

            j = findInSet(ghostRemovalJobs.get(key), droneId, JobType.GHOST_REMOVAL);
            if (j != null) return j;

            if (canBuild) {
                j = findInMap(constructionJobs.get(key), droneId, JobType.CONSTRUCTION);
                if (j != null) return j;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Remove {@code pos} from every job map. If {@code clearAssignment} is true,
     * also release any drone claim.
     */
    private void removeFromAllMaps(BlockPos pos, boolean clearAssignment) {
        long key = ChunkPos.asLong(pos);
        Map<BlockPos, BlockState> cm;
        Set<BlockPos> cs;

        if ((cm = constructionJobs.get(key))     != null) cm.remove(pos);
        if ((cm = directDeconstructJobs.get(key)) != null) cm.remove(pos);
        if ((cm = hibernatingJobs.get(key))       != null) cm.remove(pos);
        if ((cs = ghostRemovalJobs.get(key))      != null) cs.remove(pos);

        if (clearAssignment) {
            assignedPositions.remove(pos);
            Set<BlockPos> chunkSet = assignedInChunk.get(key);
            if (chunkSet != null) {
                chunkSet.remove(pos);
                if (chunkSet.isEmpty()) assignedInChunk.remove(key);
            }
            jobFinalStates.remove(pos);
        }
    }

    /**
     * Attempt to claim an entry from {@code map} using lock-free {@code putIfAbsent}.
     * Returns a {@link Job} on success, {@code null} if all entries are already claimed.
     */
    private Job findInMap(Map<BlockPos, BlockState> map, UUID droneId, JobType type) {
        if (map == null || map.isEmpty()) return null;
        for (Map.Entry<BlockPos, BlockState> entry : map.entrySet()) {
            BlockPos pos = entry.getKey();
            // putIfAbsent is atomic on ConcurrentHashMap — no external lock needed.
            if (assignedPositions.putIfAbsent(pos, droneId) == null) {
                assignedInChunk.computeIfAbsent(ChunkPos.asLong(pos), k -> ConcurrentHashMap.newKeySet()).add(pos);
                return new Job(pos, type, entry.getValue(), jobFinalStates.get(pos));
            }
        }
        return null;
    }

    /**
     * Attempt to claim an entry from {@code set}.
     */
    private Job findInSet(Set<BlockPos> set, UUID droneId, JobType type) {
        if (set == null || set.isEmpty()) return null;
        // synchronized only because LinkedHashSet is not thread-safe for iteration.
        synchronized (set) {
            for (BlockPos pos : set) {
                if (assignedPositions.putIfAbsent(pos, droneId) == null) {
                    assignedInChunk.computeIfAbsent(ChunkPos.asLong(pos), k -> ConcurrentHashMap.newKeySet()).add(pos);
                    return new Job(pos, type, Blocks.AIR.defaultBlockState(), null);
                }
            }
        }
        return null;
    }

    private boolean isItemAvailableInNetwork(Level level, BlockPos pos, ItemStack required) {
        for (Player player : level.players()) {
            if (player.blockPosition().distSqr(pos) < 4096) {
                for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                    if (player.getInventory().getItem(i).is(required.getItem())) return true;
                }
            }
        }

        LogisticsNetworkManager networkManager = LogisticsNetworkManager.get(level);
        if (networkManager == null) return false;

        Integer networkId = networkManager.getNetworkId(pos);
        if (networkId == null) return false;

        for (BlockPos memberPos : networkManager.getNetworkMembers(networkId)) {
            if (!level.isLoaded(memberPos)) continue;
            net.neoforged.neoforge.items.IItemHandler handler =
                    level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, memberPos, null);
            if (handler != null) {
                for (int i = 0; i < handler.getSlots(); i++) {
                    if (handler.getStackInSlot(i).is(required.getItem())) return true;
                }
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Client sync
    // -------------------------------------------------------------------------

    public void syncToClients(Level level) {
        if (level == null || level.isClientSide || !(level instanceof ServerLevel serverLevel)) return;

        Map<BlockPos, Integer> syncMap = new HashMap<>();
        for (Map<BlockPos, BlockState> chunkMap : directDeconstructJobs.values()) {
            for (Map.Entry<BlockPos, BlockState> entry : chunkMap.entrySet()) {
                syncMap.put(entry.getKey(), net.minecraft.world.level.block.Block.getId(entry.getValue()));
            }
        }

        com.example.ghostlib.network.payload.S2CSyncDeconstructionPacket packet =
                new com.example.ghostlib.network.payload.S2CSyncDeconstructionPacket(syncMap);
        for (net.minecraft.server.level.ServerPlayer player : serverLevel.players()) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, packet);
        }
    }

    // -------------------------------------------------------------------------
    // Chunk unload cleanup
    // -------------------------------------------------------------------------

    public void releaseAssignmentsInChunk(long chunkKey) {
        Set<BlockPos> positions = assignedInChunk.remove(chunkKey);
        if (positions != null) positions.forEach(assignedPositions::remove);
    }

    // -------------------------------------------------------------------------
    // Query helpers
    // -------------------------------------------------------------------------

    /**
     * Check if a position has any job registered (in any queue).
     */
    public boolean hasJob(BlockPos pos) {
        long key = ChunkPos.asLong(pos);
        Map<BlockPos, BlockState> cm;
        Set<BlockPos> cs;
        if ((cm = constructionJobs.get(key))      != null && cm.containsKey(pos)) return true;
        if ((cs = ghostRemovalJobs.get(key))       != null && cs.contains(pos))   return true;
        if ((cm = directDeconstructJobs.get(key))  != null && cm.containsKey(pos)) return true;
        if ((cm = hibernatingJobs.get(key))        != null && cm.containsKey(pos)) return true;
        return assignedPositions.containsKey(pos);
    }

    public boolean jobExistsAt(BlockPos pos) {
        if (assignedPositions.containsKey(pos)) return true;
        long key = ChunkPos.asLong(pos);
        Map<BlockPos, BlockState> cm;
        Set<BlockPos> cs;
        if ((cm = constructionJobs.get(key))      != null && cm.containsKey(pos)) return true;
        if ((cs = ghostRemovalJobs.get(key))       != null && cs.contains(pos))   return true;
        if ((cm = directDeconstructJobs.get(key))  != null && cm.containsKey(pos)) return true;
        if ((cm = hibernatingJobs.get(key))        != null && cm.containsKey(pos)) return true;
        return false;
    }

    public boolean isAssignedTo(BlockPos pos, UUID droneId) {
        return droneId.equals(assignedPositions.get(pos));
    }

    public boolean isDeconstructAt(BlockPos pos) {
        Map<BlockPos, BlockState> m = directDeconstructJobs.get(ChunkPos.asLong(pos));
        return m != null && m.containsKey(pos);
    }

    public BlockState getTargetAfterDeconstruct(BlockPos pos) {
        Map<BlockPos, BlockState> m = directDeconstructJobs.get(ChunkPos.asLong(pos));
        return m != null ? m.get(pos) : null;
    }

    public boolean hasAvailableJob(BlockPos pos, int range) {
        return requestJob(pos, UUID.randomUUID(), true) != null;
    }

    // -------------------------------------------------------------------------
    // Accessors for SavedData serialization
    // -------------------------------------------------------------------------

    public Map<BlockPos, BlockState>                getConstructionJobs()       { Map<BlockPos, BlockState> r = new HashMap<>(); constructionJobs.values().forEach(r::putAll); return r; }
    public Map<Long, Map<BlockPos, BlockState>>     getConstructionJobsMap()    { return constructionJobs; }
    public Map<Long, Set<BlockPos>>                 getGhostRemovalJobsMap()    { return ghostRemovalJobs; }
    public Map<Long, Map<BlockPos, BlockState>>     getDirectDeconstructJobs()  { return directDeconstructJobs; }
    public Map<Long, Map<BlockPos, BlockState>>     getHibernatingJobsMap()     { return hibernatingJobs; }
    public Map<BlockPos, BlockState>                getHibernatingJobs()        { Map<BlockPos, BlockState> r = new HashMap<>(); hibernatingJobs.values().forEach(r::putAll); return r; }
    public Map<BlockPos, BlockState>                getJobFinalStates()         { return jobFinalStates; }
    public Map<BlockPos, UUID>                      getAssignments()            { return assignedPositions; }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private void markDataDirty() {
        if (savedData != null) savedData.setDirty();
    }
}
