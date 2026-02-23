package com.example.ghostlib.history;

import com.example.ghostlib.util.GhostJobManager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * Modern implementation of History management.
 * Bridges player commands to the WorldHistoryManager ledger.
 *
 * THREAD SAFETY: The isProcessingHistory flag is protected by try-finally blocks.
 * If an exception occurs during undo/redo, the flag is guaranteed to be reset.
 */
public class GhostHistoryManager {
    /**
     * Global flag to prevent history events from being recorded while processing undo/redo.
     * This prevents infinite loops when the reconciler places/breaks blocks.
     */
    public static volatile boolean isProcessingHistory = false;

    /**
     * Watchdog counter to detect stuck flags. If an undo/redo operation takes
     * more than this many ticks, the flag will be forcibly reset.
     */
    private static int processingTickCounter = 0;
    private static final int MAX_PROCESSING_TICKS = 100; // 5 seconds at 20 tick/sec

    public static void undo(Player player) {
        if (player.level().isClientSide) return;
        com.example.ghostlib.util.GhostLogger.logistics("UNDO: Starting undo for " + player.getName().getString());
        com.example.ghostlib.util.GhostLogger.logistics("UNDO: isProcessingHistory was " + isProcessingHistory);
        isProcessingHistory = true;
        processingTickCounter = 0;
        try {
            WorldHistoryManager manager = WorldHistoryManager.get(player.level());
            com.example.ghostlib.util.GhostLogger.logistics("UNDO: undoStack size before = " + manager.getUndoStack().size());
            manager.undo(player.level());
            com.example.ghostlib.util.GhostLogger.logistics("UNDO: undoStack size after = " + manager.getUndoStack().size());
            com.example.ghostlib.util.GhostLogger.logistics("UNDO: redoStack size after = " + manager.getRedoStack().size());
            com.example.ghostlib.util.GhostLogger.logistics("UNDO: dirtyPositions count = " + manager.getDirtyPositions().size());
        } catch (Exception e) {
            com.example.ghostlib.util.GhostLogger.logistics("ERROR during undo: " + e.getMessage());
            e.printStackTrace();
        } finally {
            isProcessingHistory = false;
            processingTickCounter = 0;
            com.example.ghostlib.util.GhostLogger.logistics("UNDO: Complete, isProcessingHistory reset to false");
        }
    }

    public static void redo(Player player) {
        if (player.level().isClientSide) return;
        com.example.ghostlib.util.GhostLogger.logistics("REDO: Starting redo for " + player.getName().getString());
        isProcessingHistory = true;
        processingTickCounter = 0;
        try {
            WorldHistoryManager manager = WorldHistoryManager.get(player.level());
            com.example.ghostlib.util.GhostLogger.logistics("REDO: redoStack size before = " + manager.getRedoStack().size());
            manager.redo(player.level());
            com.example.ghostlib.util.GhostLogger.logistics("REDO: redoStack size after = " + manager.getRedoStack().size());
            com.example.ghostlib.util.GhostLogger.logistics("REDO: undoStack size after = " + manager.getUndoStack().size());
            com.example.ghostlib.util.GhostLogger.logistics("REDO: dirtyPositions count = " + manager.getDirtyPositions().size());
        } catch (Exception e) {
            com.example.ghostlib.util.GhostLogger.logistics("ERROR during redo: " + e.getMessage());
            e.printStackTrace();
        } finally {
            isProcessingHistory = false;
            processingTickCounter = 0;
            com.example.ghostlib.util.GhostLogger.logistics("REDO: Complete, isProcessingHistory reset to false");
        }
    }

    /**
     * Called every tick to increment the watchdog counter.
     * If processing takes too long, the flag is forcibly reset.
     */
    public static void tick() {
        if (isProcessingHistory) {
            processingTickCounter++;
            if (processingTickCounter > MAX_PROCESSING_TICKS) {
                com.example.ghostlib.util.GhostLogger.logistics("WATCHDOG: Reset stuck isProcessingHistory flag after " + processingTickCounter + " ticks");
                isProcessingHistory = false;
                processingTickCounter = 0;
            }
        }
    }

    // LEGACY: satisfies previous subscribers, but logic moved to Reconciler
    public record StateChange(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState oldState, net.minecraft.world.level.block.state.BlockState newState, net.minecraft.nbt.CompoundTag oldData, net.minecraft.nbt.CompoundTag newData) {}
    public static void recordAction(Player player, java.util.List<StateChange> changes) {} 
    public static void saveHistory(Level level) {}
    public static void loadHistory(Level level) {}
}
