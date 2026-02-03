package com.example.ghostlib.util;

import com.example.ghostlib.GhostLib;
import com.example.ghostlib.block.entity.GhostBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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

    // Scalability-optimized job storage using chunk-based spatial partitioning
    private final Map<Long, Map<BlockPos, BlockState>> constructionJobs = new ConcurrentHashMap<>();
    private final Map<Long, Set<BlockPos>> ghostRemovalJobs = new ConcurrentHashMap<>();
    private final Map<Long, Map<BlockPos, BlockState>> directDeconstructJobs = new ConcurrentHashMap<>();
    private final Map<Long, Map<BlockPos, BlockState>> hibernatingJobs = new ConcurrentHashMap<>();

    // Tracks the final construction intent for a deconstruction job
    private final Map<BlockPos, BlockState> jobFinalStates = new ConcurrentHashMap<>();

    // Scalability-optimized assignment tracking with chunk-based indexing
    private final Map<BlockPos, UUID> assignedPositions = new ConcurrentHashMap<>();
    private final Map<Long, Set<BlockPos>> assignedInChunk = new ConcurrentHashMap<>(); // Track assignments per chunk for efficient cleanup

    // Lock-free job assignment optimization
    private final java.util.concurrent.atomic.AtomicReference<java.util.Queue<JobAssignment>> assignmentQueue =
        new java.util.concurrent.atomic.AtomicReference<>(new java.util.concurrent.ConcurrentLinkedQueue<>());

    // Internal class for tracking job assignments
    private static class JobAssignment {
        final BlockPos pos;
        final UUID droneId;
        final long timestamp;

        JobAssignment(BlockPos pos, UUID droneId) {
            this.pos = pos;
            this.droneId = droneId;
            this.timestamp = System.nanoTime();
        }
    }

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
        boolean clearAssignment = (state == GhostBlockEntity.GhostState.UNASSIGNED
                || state == GhostBlockEntity.GhostState.TO_REMOVE
                || state == GhostBlockEntity.GhostState.MISSING_ITEMS);

        removeFromAllMaps(pos, clearAssignment);

        if (state == GhostBlockEntity.GhostState.TO_REMOVE) {
            ghostRemovalJobs.computeIfAbsent(key, k -> Collections.synchronizedSet(new LinkedHashSet<>())).add(pos);
            // PERSIST INTENT: If we are removing a ghost that has a target, keep that target!
            if (target != null && !target.isAir()) constructionJobs.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(pos, target);
            dirty = true;
            markDataDirty();
        } else if (state == GhostBlockEntity.GhostState.UNASSIGNED) {
            constructionJobs.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(pos, target);
            markDataDirty();
        } else if (state == GhostBlockEntity.GhostState.MISSING_ITEMS) {
            hibernatingJobs.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(pos, target);
            markDataDirty();
        }
    }

    private void removeFromAllMaps(BlockPos pos, boolean clearAssignment) {
        long key = ChunkPos.asLong(pos);
        if (constructionJobs.containsKey(key)) constructionJobs.get(key).remove(pos);
        if (ghostRemovalJobs.containsKey(key)) { if (ghostRemovalJobs.get(key).remove(pos)) dirty = true; }
        if (directDeconstructJobs.containsKey(key)) { if (directDeconstructJobs.get(key).remove(pos) != null) dirty = true; }
        if (hibernatingJobs.containsKey(key)) hibernatingJobs.get(key).remove(pos);
        if (clearAssignment) {
            assignedPositions.remove(pos);
            // Remove from chunk assignment tracking
            Set<BlockPos> chunkAssignments = assignedInChunk.get(key);
            if (chunkAssignments != null) {
                chunkAssignments.remove(pos);
                if (chunkAssignments.isEmpty()) {
                    assignedInChunk.remove(key);
                }
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
        
        // ZERO FORCE: Do NOT call setBlock here. 
        // The original block stays in the world. 
        // The drone will physically handle the transition.

        removeFromAllMaps(pos, true);
        directDeconstructJobs.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(pos, targetAfter);
        if (finalState != null) jobFinalStates.put(pos, finalState);
        dirty = true;
        markDataDirty();
        
        com.example.ghostlib.util.GhostLogger.log("JOB", "Registered Deconstruction at " + pos + " -> Target: " + targetAfter + " (Final: " + finalState + ")");
    }

    public void removeJob(BlockPos pos) {
        removeFromAllMaps(pos, true);
        markDataDirty();
    }

    /**
     * Safely complete a job by removing it and updating the ghost block entity state
     */
    public void completeJob(BlockPos pos, Level level) {
        // Remove the job from all queues
        removeFromAllMaps(pos, true);

        // Update the ghost block entity state to reflect completion
        if (level != null && level.getBlockEntity(pos) instanceof GhostBlockEntity gbe) {
            // Only update if the ghost block still exists
            gbe.setRemoved(); // This will remove the job again, but safely
        }

        markDataDirty();
    }

    /**
     * Check if a job still exists at the given position
     */
    public boolean jobExistsAt(BlockPos pos) {
        long key = ChunkPos.asLong(pos);

        // Check all job types
        if (constructionJobs.containsKey(key) && constructionJobs.get(key).containsKey(pos)) {
            return true;
        }
        if (ghostRemovalJobs.containsKey(key) && ghostRemovalJobs.get(key).contains(pos)) {
            return true;
        }
        if (directDeconstructJobs.containsKey(key) && directDeconstructJobs.get(key).containsKey(pos)) {
            return true;
        }
        if (hibernatingJobs.containsKey(key) && hibernatingJobs.get(key).containsKey(pos)) {
            return true;
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
        return map.keySet().stream()
            .filter(p -> !assignedPositions.containsKey(p))
            .sorted(Comparator.comparingDouble(p -> p.distSqr(dronePos)))
            .filter(p -> {
                // Use atomic operation for thread safety
                UUID previous = assignedPositions.putIfAbsent(p, droneId);
                if (previous == null) {
                    // Successfully assigned, also track in chunk
                    long chunkKey = ChunkPos.asLong(p);
                    assignedInChunk.computeIfAbsent(chunkKey, k -> ConcurrentHashMap.newKeySet()).add(p);
                    // Add to lock-free assignment queue for performance tracking
                    addToAssignmentQueue(p, droneId);
                    return true;
                }
                return false; // Already assigned to another drone
            })
            .findFirst()
            .map(p -> new Job(p, type, map.get(p), jobFinalStates.get(p)))
            .orElse(null);
    }

    private Job findInSet(Set<BlockPos> set, BlockPos dronePos, UUID droneId, JobType type) {
        if (set == null || set.isEmpty()) return null;
        List<BlockPos> candidates;
        synchronized (set) {
            candidates = set.stream().filter(p -> !assignedPositions.containsKey(p))
                .sorted(Comparator.comparingDouble(p -> p.distSqr(dronePos))).toList();
        }
        return candidates.stream()
            .filter(p -> {
                // Use atomic operation for thread safety
                UUID previous = assignedPositions.putIfAbsent(p, droneId);
                if (previous == null) {
                    // Successfully assigned, also track in chunk
                    long chunkKey = ChunkPos.asLong(p);
                    assignedInChunk.computeIfAbsent(chunkKey, k -> ConcurrentHashMap.newKeySet()).add(p);
                    // Add to lock-free assignment queue for performance tracking
                    addToAssignmentQueue(p, droneId);
                    return true;
                }
                return false; // Already assigned to another drone
            })
            .findFirst()
            .map(p -> new Job(p, type, Blocks.AIR.defaultBlockState(), null))
            .orElse(null);
    }

    /**
     * Adds assignment to the lock-free queue for performance tracking
     */
    private void addToAssignmentQueue(BlockPos pos, UUID droneId) {
        Queue<JobAssignment> currentQueue = assignmentQueue.get();
        currentQueue.offer(new JobAssignment(pos, droneId));
    }

    /**
     * Batch process assignments to reduce overhead
     */
    public void processAssignmentQueue() {
        Queue<JobAssignment> currentQueue = assignmentQueue.get();
        JobAssignment assignment;
        int processed = 0;

        // Process up to 100 assignments per tick to avoid lag spikes
        while ((assignment = currentQueue.poll()) != null && processed < 100) {
            // Could add additional processing here if needed
            processed++;
        }
    }

    public void tick(Level level) {
        if (level.isClientSide) return;

        // Performance monitoring - log stats every 10 seconds (200 ticks)
        if (level.getGameTime() % 200 == 0) {
            int totalJobs = getTotalJobCount();
            int assignedJobs = getAssignedJobCount();
            int chunksWithJobs = getTotalChunksWithJobs();

            if (totalJobs > 1000) { // Only log if we have significant load
                com.example.ghostlib.util.GhostLogger.performance("JobManager Stats - Total: " + totalJobs + ", Assigned: " + assignedJobs + ", Chunks: " + chunksWithJobs);
            }

            wakeUpHibernatingJobs(level);
        }

        // Process assignment queue periodically to reduce overhead
        if (level.getGameTime() % 5 == 0) {
            processAssignmentQueue();
        }

        if (dirty || level.getGameTime() % 100 == 0) {
            syncToClients(level);
            dirty = false;
        }
    }

    private void wakeUpHibernatingJobs(Level level) {
        for (var jobs : hibernatingJobs.values()) {
            for (BlockPos pos : jobs.keySet()) {
                if (level.getBlockEntity(pos) instanceof GhostBlockEntity gbe) {
                    gbe.setState(GhostBlockEntity.GhostState.UNASSIGNED);
                } else {
                    removeFromAllMaps(pos, true);
                }
            }
        }
    }

    public void syncToClients(Level level) {
        if (level.isClientSide) return;
        Map<BlockPos, Integer> active = new HashMap<>();
        for (var map : directDeconstructJobs.values()) {
            for (var entry : map.entrySet()) active.put(entry.getKey(), Block.getId(entry.getValue()));
        }
        for (var set : ghostRemovalJobs.values()) {
            synchronized (set) { for (BlockPos p : set) active.put(p, Block.getId(Blocks.AIR.defaultBlockState())); }
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToAllPlayers(new com.example.ghostlib.network.payload.S2CSyncDeconstructionPacket(active));
    }

    public boolean isDeconstructAt(BlockPos pos) {
        long key = ChunkPos.asLong(pos);
        return directDeconstructJobs.containsKey(key) && directDeconstructJobs.get(key).containsKey(pos);
    }

    public BlockState getTargetAfterDeconstruct(BlockPos pos) {
        long key = ChunkPos.asLong(pos);
        Map<BlockPos, BlockState> map = directDeconstructJobs.get(key);
        return map != null ? map.get(pos) : null;
    }

    public boolean isAssignedTo(BlockPos pos, UUID droneId) {
        UUID current = assignedPositions.get(pos);
        return current != null && current.equals(droneId);
    }

    public void releaseJob(BlockPos pos, UUID droneId) {
        UUID current = assignedPositions.get(pos);
        if (current != null && current.equals(droneId)) {
            assignedPositions.remove(pos);
            // Also remove from chunk assignment tracking
            long chunkKey = ChunkPos.asLong(pos);
            Set<BlockPos> chunkAssignments = assignedInChunk.get(chunkKey);
            if (chunkAssignments != null) {
                chunkAssignments.remove(pos);
                if (chunkAssignments.isEmpty()) {
                    assignedInChunk.remove(chunkKey);
                }
            }
        }
    }

    public boolean hasAvailableJob(BlockPos center, int radius) {
        int cx = SectionPos.blockToSectionCoord(center.getX());
        int cz = SectionPos.blockToSectionCoord(center.getZ());
        int chunkRadius = (radius >> 4) + 1;
        for (int r = 0; r <= chunkRadius; r++) {
            for (int x = cx - r; x <= cx + r; x++) {
                for (int z = cz - r; z <= cz + r; z++) {
                    if (r > 0 && Math.abs(x - cx) < r && Math.abs(z - cz) < r) continue;
                    long key = ChunkPos.asLong(x, z);
                    if (directDeconstructJobs.containsKey(key) && directDeconstructJobs.get(key).keySet().stream().anyMatch(p -> !assignedPositions.containsKey(p))) return true;
                    if (constructionJobs.containsKey(key) && constructionJobs.get(key).keySet().stream().anyMatch(p -> !assignedPositions.containsKey(p))) return true;
                }
            }
        }
        return false;
    }

    public void releaseAssignmentsInChunk(long chunkPos) {
        // Use the chunk assignment tracking for efficient cleanup
        Set<BlockPos> positionsToRelease = assignedInChunk.remove(chunkPos);
        if (positionsToRelease != null) {
            for (BlockPos pos : positionsToRelease) {
                assignedPositions.remove(pos);
            }
        }
    }

    public void restoreAssignment(BlockPos pos, UUID droneId) {
        assignedPositions.put(pos, droneId);
        // Also track in chunk assignment tracking
        long chunkKey = ChunkPos.asLong(pos);
        assignedInChunk.computeIfAbsent(chunkKey, k -> ConcurrentHashMap.newKeySet()).add(pos);
    }

    public Map<BlockPos, BlockState> getConstructionJobs() {
        Map<BlockPos, BlockState> res = new HashMap<>();
        for (var m : constructionJobs.values()) res.putAll(m);
        return res;
    }

    public Map<Long, Map<BlockPos, BlockState>> getDirectDeconstructJobs() {
        return directDeconstructJobs;
    }

    public Map<BlockPos, BlockState> getHibernatingJobs() {
        Map<BlockPos, BlockState> res = new HashMap<>();
        for (var m : hibernatingJobs.values()) res.putAll(m);
        return res;
    }

    private void markDataDirty() { if (savedData != null) savedData.markDirty(); }
    
    public Map<Long, Map<BlockPos, BlockState>> getConstructionJobsMap() { return constructionJobs; }
    public Map<Long, Set<BlockPos>> getGhostRemovalJobsMap() { return ghostRemovalJobs; }
    public Map<Long, Map<BlockPos, BlockState>> getHibernatingJobsMap() { return hibernatingJobs; }
    public Map<BlockPos, UUID> getAssignments() { return new HashMap<>(assignedPositions); }
    public Map<BlockPos, BlockState> getJobFinalStates() { return jobFinalStates; }

    // Performance monitoring methods
    public int getTotalJobCount() {
        int count = 0;
        for (var map : constructionJobs.values()) count += map.size();
        for (var set : ghostRemovalJobs.values()) count += set.size();
        for (var map : directDeconstructJobs.values()) count += map.size();
        for (var map : hibernatingJobs.values()) count += map.size();
        return count;
    }

    public int getAssignedJobCount() {
        return assignedPositions.size();
    }

    public int getTotalChunksWithJobs() {
        Set<Long> allChunks = new HashSet<>();
        allChunks.addAll(constructionJobs.keySet());
        allChunks.addAll(ghostRemovalJobs.keySet());
        allChunks.addAll(directDeconstructJobs.keySet());
        allChunks.addAll(hibernatingJobs.keySet());
        return allChunks.size();
    }
}
    