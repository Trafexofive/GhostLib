package com.example.ghostlib.history;

import com.example.ghostlib.GhostLib;
import com.example.ghostlib.block.GhostBlock;
import com.example.ghostlib.block.entity.GhostBlockEntity;
import com.example.ghostlib.registry.ModBlocks;
import com.example.ghostlib.util.GhostJobManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * THE RECONCILIATION ENGINE
 * 
 * This engine is the only bridge between the Command Ledger (Intent) and 
 * the Physical World (Level). It operates on a "Pull" model:
 * 
 * 1. SCAN: Every tick, it checks the "Dirty Set" from WorldHistoryManager.
 * 
 * 2. COMPARE: It compares Top-of-Stack Intent vs. Actual BlockState.
 * 
 * 3. COMMAND: 
 *    - If intent is Air but reality is Block -> Register PHYSICAL DECONSTRUCTION Job.
 *    - If intent is Block but reality is Air/Ghost -> Place GHOST marker with Intent NBT.
 *    - If intent matches reality -> Mark coordinate as CLEAN.
 * 
 * This ensures "Zero Instant Magic". Only ghost markers appear instantly; 
 * physical changes are always delegated to the Drone Swarm.
 */
public class WorldReconciler {
    private static final Map<Level, WorldReconciler> INSTANCES = new ConcurrentHashMap<>();

    public static WorldReconciler get(Level level) {
        return INSTANCES.computeIfAbsent(level, k -> new WorldReconciler());
    }

    public void tick(ServerLevel level) {
        WorldHistoryManager history = WorldHistoryManager.get(level);
        GhostJobManager jobManager = GhostJobManager.get(level);

        Set<BlockPos> dirty = new HashSet<>(history.getDirtyPositions());
        if (dirty.isEmpty()) return;

        for (BlockPos pos : dirty) {
            if (reconcileCoordinate(level, pos, history, jobManager)) {
                history.markClean(pos);
            }
        }
    }

    /**
     * @return true if the coordinate is now fulfilled or correctly marked with a job.
     */
    public boolean reconcileCoordinate(ServerLevel level, BlockPos pos, WorldHistoryManager history, GhostJobManager jobManager) {
        BlockSnapshot intent = history.getIntendedState(pos);
        if (intent == null) return true;

        BlockState worldState = level.getBlockState(pos);
        
        // CASE 1: Desired is AIR
        if (intent.state().isAir()) {
            if (worldState.isAir()) {
                jobManager.removeJob(pos);
                return true; 
            } else if (worldState.getBlock() instanceof GhostBlock) {
                // Instantly remove ghost marker
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                jobManager.removeJob(pos);
                return true;
            } else {
                // Reality is a block, but intent is Air -> PHYSICAL DECONSTRUCTION
                jobManager.registerDirectDeconstruct(pos, Blocks.AIR.defaultBlockState(), level);
                return true; // Job issued, reconciler is done until drone finishes
            }
        } 
        // CASE 2: Desired is a REAL BLOCK
        else {
            if (worldState.equals(intent.state())) {
                // Fulfilled.
                jobManager.removeJob(pos);
                return true;
            } else {
                // Mismatch!
                if (worldState.isAir() || worldState.canBeReplaced() || worldState.getBlock() instanceof GhostBlock) {
                    // Place/Update Ghost marker to match intent
                    if (!(worldState.getBlock() instanceof GhostBlock)) {
                        level.setBlock(pos, ModBlocks.GHOST_BLOCK.get().defaultBlockState(), 3);
                    }
                    
                    if (level.getBlockEntity(pos) instanceof GhostBlockEntity gbe) {
                        if (!gbe.getTargetState().equals(intent.state())) {
                            gbe.setTargetState(intent.state());
                            if (intent.nbt() != null) gbe.setCapturedNbt(intent.nbt());
                            gbe.setState(GhostBlockEntity.GhostState.UNASSIGNED);
                        }
                    }
                    return true;
                } else {
                    // Obstruction! Reality is a DIFFERENT real block.
                    jobManager.registerDirectDeconstruct(pos, ModBlocks.GHOST_BLOCK.get().defaultBlockState(), intent.state(), level);
                    return true;
                }
            }
        }
    }
}