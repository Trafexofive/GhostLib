package com.example.ghostlib.history;

import com.example.ghostlib.util.GhostJobManager;
import com.example.ghostlib.block.entity.GhostBlockEntity;
import com.example.ghostlib.registry.ModBlocks;
import com.example.ghostlib.block.GhostBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class GhostHistoryManager {
    
    public record GhostRecord(BlockPos pos, BlockState previousWorldState, BlockState targetBlueprintState) {}

    private static final Map<UUID, Deque<List<GhostRecord>>> UNDO_STACKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Deque<List<GhostRecord>>> REDO_STACKS = new ConcurrentHashMap<>();
    private static final int MAX_HISTORY = 30;

    public static void recordPlacement(Player player, List<GhostRecord> records) {
        if (records == null || records.isEmpty()) return;
        UNDO_STACKS.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>()).push(new ArrayList<>(records));
        REDO_STACKS.remove(player.getUUID()); 
        if (UNDO_STACKS.get(player.getUUID()).size() > MAX_HISTORY) UNDO_STACKS.get(player.getUUID()).removeLast();
    }

    public static void undo(Player player) {
        Level level = player.level();
        if (level.isClientSide) return;

        Deque<List<GhostRecord>> stack = UNDO_STACKS.get(player.getUUID());
        if (stack == null || stack.isEmpty()) return;

        List<GhostRecord> lastAction = stack.pop();
        GhostJobManager manager = GhostJobManager.get(level);

        for (GhostRecord record : lastAction) {
            // CRITICAL FIX: Force remove any pending job at this position immediately.
            // This cancels any drone currently flying here to build/deconstruct.
            manager.removeJob(record.pos);

            BlockState current = level.getBlockState(record.pos);
            
            // If the world state already matches what we want to restore, we're done.
            if (current.equals(record.previousWorldState)) {
                continue;
            }

            // Clean up any ghost block entity data if it exists
            if (current.getBlock() instanceof GhostBlock) {
                level.removeBlockEntity(record.pos);
                level.setBlock(record.pos, Blocks.AIR.defaultBlockState(), 3);
                current = Blocks.AIR.defaultBlockState();
            }

            if (record.previousWorldState.isAir()) {
                // RESTORE AIR
                if (!current.isAir()) {
                    // Solid block exists (it was built), mark for deconstruction to AIR
                    manager.registerDirectDeconstruct(record.pos, Blocks.AIR.defaultBlockState(), level);
                }
            } else {
                // RESTORE SOLID BLOCK
                if (current.isAir()) {
                    // Safe to place ghost for restoration
                    level.setBlock(record.pos, ModBlocks.GHOST_BLOCK.get().defaultBlockState(), 3);
                    if (level.getBlockEntity(record.pos) instanceof GhostBlockEntity gbe) {
                        gbe.setTargetState(record.previousWorldState);
                        gbe.setState(GhostBlockEntity.GhostState.UNASSIGNED);
                    }
                } else {
                    // Obstructed by wrong block, deconstruct then restore
                    manager.registerDirectDeconstruct(record.pos, record.previousWorldState, level);
                }
            }
        }
        
        // Force a sync to ensure clients remove any stale ghost renderers
        manager.syncToClients(level);
        
        REDO_STACKS.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>()).push(lastAction);
    }

    public static void redo(Player player) {
        Level level = player.level();
        if (level.isClientSide) return;

        Deque<List<GhostRecord>> stack = REDO_STACKS.get(player.getUUID());
        if (stack == null || stack.isEmpty()) return;

        List<GhostRecord> nextAction = stack.pop();
        GhostJobManager manager = GhostJobManager.get(level);

        for (GhostRecord record : nextAction) {
            // CRITICAL FIX: Force remove jobs here too
            manager.removeJob(record.pos);

            BlockState current = level.getBlockState(record.pos);
            
            if (current.equals(record.targetBlueprintState)) {
                continue;
            }

            if (current.getBlock() instanceof GhostBlock) {
                level.removeBlockEntity(record.pos);
                level.setBlock(record.pos, Blocks.AIR.defaultBlockState(), 3);
                current = Blocks.AIR.defaultBlockState();
            }

            if (record.targetBlueprintState.isAir()) {
                if (!current.isAir()) {
                    manager.registerDirectDeconstruct(record.pos, Blocks.AIR.defaultBlockState(), level);
                }
            } else {
                if (current.isAir()) {
                    level.setBlock(record.pos, ModBlocks.GHOST_BLOCK.get().defaultBlockState(), 3);
                    if (level.getBlockEntity(record.pos) instanceof GhostBlockEntity gbe) {
                        gbe.setTargetState(record.targetBlueprintState);
                        gbe.setState(GhostBlockEntity.GhostState.UNASSIGNED);
                    }
                } else {
                    manager.registerDirectDeconstruct(record.pos, record.targetBlueprintState, level);
                }
            }
        }
        manager.syncToClients(level);
        UNDO_STACKS.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>()).push(nextAction);
    }
    
    public static void clear(Player player) {
        UNDO_STACKS.remove(player.getUUID());
        REDO_STACKS.remove(player.getUUID());
    }
}
