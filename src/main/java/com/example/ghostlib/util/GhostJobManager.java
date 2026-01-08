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

/**
 * The GhostJobManager is the central coordination engine for the construction system.
 * It manages multiple task queues (Construction, Deconstruction, Removal) and handles
 * spatial-based job distribution to drones.
 *
 * This class uses ConcurrentHashMaps for thread-safe operations, allowing drones and
 * world events to interact with the job system simultaneously.
 */
public class GhostJobManager {
    /** Global instances mapped by Level to ensure independent job tracking per dimension. */
    private static final Map<Level, GhostJobManager> INSTANCES = new ConcurrentHashMap<>();

    /** Defines the specific task type for a drone. */
    public enum JobType { 
        /** Building a new block from a ghost marker. */
        CONSTRUCTION, 
        /** Deleting a ghost marker without placing a block. */
        GHOST_REMOVAL, 
        /** Physically breaking a world block (e.g., removing an obstruction). */
        DIRECT_DECONSTRUCT 
    }

    /** A data record representing a task assigned to a drone. */
    public record Job(BlockPos pos, JobType type, BlockState targetAfter) {}

    /** Indexed by ChunkPos (long). Stores positions and target states for new construction. */
    private final Map<Long, Map<BlockPos, BlockState>> constructionJobs = new ConcurrentHashMap<>();
    
    /** Indexed by ChunkPos (long). Stores positions of ghost blocks to be removed. */
    private final Map<Long, Set<BlockPos>> ghostRemovalJobs = new ConcurrentHashMap<>(); 
    
    /** Indexed by ChunkPos (long). Stores blocks marked for Silk-Touch deconstruction. */
    private final Map<Long, Map<BlockPos, BlockState>> directDeconstructJobs = new ConcurrentHashMap<>();
    
    /** Temporary storage for jobs that failed (e.g., missing items) before they are re-queued. */
    private final Map<Long, Map<BlockPos, BlockState>> hibernatingJobs = new ConcurrentHashMap<>();
    
    /** Tracks which drone UUID is currently working on which BlockPos to prevent duplicate assignments. */
    private final Map<BlockPos, UUID> assignedPositions = new ConcurrentHashMap<>();
    
    /** Flag used to trigger network synchronization of deconstruction overlays to clients. */
    private boolean dirty = false;

    /**
     * Retrieves or creates the job manager for a specific dimension.
     */
    public static GhostJobManager get(Level level) {
        return INSTANCES.computeIfAbsent(level, k -> new GhostJobManager());
    }

    /**
     * Registers or updates a job for a specific block position.
     * 
     * @param pos The position of the job.
     * @param state The current state of the ghost (determines which queue it enters).
     * @param target The BlockState to be placed upon completion (for construction).
     */
    public void registerJob(BlockPos pos, GhostBlockEntity.GhostState state, BlockState target) {
        long key = ChunkPos.asLong(pos);
        pos = pos.immutable();
        
        // Only clear assignment if the state implies no drone is working on it.
        // Active states (ASSIGNED, FETCHING, INCOMING) preserve their drone ID.
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

    /**
     * Internal cleanup to remove a position from all task queues.
     * @param clearAssignment If true, the drone lock on this position is released.
     */
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

    /**
     * Registers a physical block for deconstruction.
     * @param targetAfter The state to place after removal (usually AIR or a GHOST block).
     */
    public void registerDirectDeconstruct(BlockPos pos, BlockState targetAfter, Level level) {
        long key = ChunkPos.asLong(pos);
        pos = pos.immutable();
        removeFromAllMaps(pos, true);
        directDeconstructJobs.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(pos, targetAfter);
        dirty = true;
    }

    /**
     * Completely removes all records of a job at the given position.
     */
    public void removeJob(BlockPos pos) {
        removeFromAllMaps(pos, true);
    }

    /**
     * Releases a drone's claim on a position.
     */
    public void releaseJob(BlockPos pos, UUID droneId) {
        if (pos == null) return;
        UUID current = assignedPositions.get(pos);
        if (current != null && current.equals(droneId)) assignedPositions.remove(pos);
    }

    /**
     * Checks if a specific drone is assigned to a position.
     */
    public boolean isAssignedTo(BlockPos pos, UUID droneId) {
        if (pos == null || droneId == null) return false;
        UUID current = assignedPositions.get(pos);
        return current != null && current.equals(droneId);
    }

    /**
     * Attempts to find a job for a drone near its current position.
     * Uses a ring-based search pattern expanding outward up to 7 chunks.
     */
    public boolean hasAvailableJob(BlockPos center, int radius) {
        int cx = SectionPos.blockToSectionCoord(center.getX());
        int cz = SectionPos.blockToSectionCoord(center.getZ());
        int chunkRadius = (radius >> 4) + 1;

        for (int r = 0; r <= chunkRadius; r++) {
            for (int x = cx - r; x <= cx + r; x++) {
                for (int z = cz - r; z <= cz + r; z++) {
                    if (r > 0 && Math.abs(x - cx) < r && Math.abs(z - cz) < r) continue;
                    long key = ChunkPos.asLong(x, z);

                    // Check Deconstruction
                    if (directDeconstructJobs.containsKey(key)) {
                        for (BlockPos p : directDeconstructJobs.get(key).keySet()) {
                            if (!assignedPositions.containsKey(p)) return true;
                        }
                    }
                    // Check Construction
                    if (constructionJobs.containsKey(key)) {
                        for (BlockPos p : constructionJobs.get(key).keySet()) {
                            if (!assignedPositions.containsKey(p)) return true;
                        }
                    }
                }
            }
        }
        return false;
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

    /**
     * Internal search logic for a specific chunk ring distance.
     */
    private Job searchRing(int cx, int cz, int r, BlockPos dronePos, UUID droneId, boolean canBuild) {
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                // Skip inner rings already searched
                if (r > 0 && Math.abs(x - cx) < r && Math.abs(z - cz) < r) continue;
                long key = ChunkPos.asLong(x, z);

                // Priority 1: Direct Deconstruction (Clear the path)
                Job j = findInMap(directDeconstructJobs.get(key), dronePos, droneId, JobType.DIRECT_DECONSTRUCT);
                if (j != null) return j;

                // Priority 2: Cleanup (Ghost Removal)
                j = findInSet(ghostRemovalJobs.get(key), dronePos, droneId, JobType.GHOST_REMOVAL);
                if (j != null) return j;

                // Priority 3: Construction (Build)
                if (canBuild) {
                    j = findInMap(constructionJobs.get(key), dronePos, droneId, JobType.CONSTRUCTION);
                    if (j != null) return j;
                }
            }
        }
        return null;
    }

    /**
     * Selects the closest available job from a Map.
     */
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

    /**
     * Selects the closest available job from a Set.
     */
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

    /**
     * Ticking logic for maintenance tasks (Wakeup, Network Sync).
     */
    public void tick(Level level) {
        if (level.isClientSide) return;
        
        // Sync deconstruction overlays only if changes occurred or periodically as fallback.
        if (dirty || level.getGameTime() % 100 == 0) {
            syncToClients(level);
            dirty = false;
        }

        // Wake up hibernating jobs every 10 seconds (200 ticks)
        if (level.getGameTime() % 200 == 0) {
            wakeUpHibernatingJobs(level);
        }
    }

    /**
     * Moves jobs from hibernating status back to active status, triggering a retry by drones.
     */
    private void wakeUpHibernatingJobs(Level level) {
        for (Map.Entry<Long, Map<BlockPos, BlockState>> entry : hibernatingJobs.entrySet()) {
            Map<BlockPos, BlockState> jobs = entry.getValue();
            if (jobs.isEmpty()) continue;

            for (Map.Entry<BlockPos, BlockState> job : jobs.entrySet()) {
                BlockPos pos = job.getKey();
                if (level.getBlockEntity(pos) instanceof GhostBlockEntity gbe) {
                    gbe.setState(GhostBlockEntity.GhostState.UNASSIGNED);
                } else {
                    removeFromAllMaps(pos, true);
                }
            }
        }
    }

    /**
     * Synchronizes the deconstruction job list to all players in the dimension.
     * This allows the client-side renderer to draw the red wireframe overlays.
     */
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

    public boolean isDeconstructAt(BlockPos pos) {
        long key = ChunkPos.asLong(pos);
        return directDeconstructJobs.containsKey(key) && directDeconstructJobs.get(key).containsKey(pos);
    }

    public Map<Long, Map<BlockPos, BlockState>> getDirectDeconstructJobs() { return directDeconstructJobs; }
    public BlockState getTargetAfterDeconstruct(BlockPos pos) {
        long key = ChunkPos.asLong(pos);
        Map<BlockPos, BlockState> map = directDeconstructJobs.get(key);
        return map != null ? map.get(pos) : null;
    }
}
