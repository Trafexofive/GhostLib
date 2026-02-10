package com.example.ghostlib.history;

import com.example.ghostlib.util.GhostJobManager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * Modern implementation of History management.
 * Bridges player commands to the WorldHistoryManager ledger.
 */
public class GhostHistoryManager {
    public static boolean isProcessingHistory = false;

    public static void undo(Player player) {
        if (player.level().isClientSide) return;
        isProcessingHistory = true;
        try {
            WorldHistoryManager.get(player.level()).undo(player.level());
        } finally {
            isProcessingHistory = false;
        }
    }

    public static void redo(Player player) {
        if (player.level().isClientSide) return;
        isProcessingHistory = true;
        try {
            WorldHistoryManager.get(player.level()).redo(player.level());
        } finally {
            isProcessingHistory = false;
        }
    }

    // LEGACY: satisfies previous subscribers, but logic moved to Reconciler
    public record StateChange(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState oldState, net.minecraft.world.level.block.state.BlockState newState, net.minecraft.nbt.CompoundTag oldData, net.minecraft.nbt.CompoundTag newData) {}
    public static void recordAction(Player player, java.util.List<StateChange> changes) {} 
    public static void saveHistory(Level level) {}
    public static void loadHistory(Level level) {}
}
