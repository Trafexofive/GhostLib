package com.example.ghostlib.history;

import com.example.ghostlib.GhostLib;
import com.example.ghostlib.history.BlockSnapshot;
import com.example.ghostlib.history.WorldHistoryManager;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@EventBusSubscriber(modid = GhostLib.MODID, bus = EventBusSubscriber.Bus.GAME)
public class HistoryEventSubscriber {

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled()) return;
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (GhostHistoryManager.isProcessingHistory) return;

        // IGNORE: Reconciler/Drones are not players. 
        // Manual interactions with ghosts (e.g. replacing a ghost with a real block) 
        // are allowed to be recorded as intent overrides.

        BlockPos pos = event.getPos().immutable();
        BlockState newState = event.getPlacedBlock();
        
        // Capture 'Before' state from the snapshot
        BlockState oldState = event.getBlockSnapshot().getState();
        CompoundTag oldNbt = null;
        // In 1.21, block snapshots usually contain the BE data if it existed
        // but we'll try to extract from the snapshot components if possible or assume AIR for fresh placements
        
        BlockSnapshot before = new BlockSnapshot(oldState, null);
        BlockSnapshot after = new BlockSnapshot(newState, null); // Manual placement NBT is usually uninitialized

        WorldHistoryManager.get((Level)event.getLevel()).pushAction(
            new WorldHistoryManager.HistoryAction("Manual Place", Map.of(pos, after)),
            (Level)event.getLevel(),
            Map.of(pos, before)
        );
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled()) return;
        if (event.getLevel().isClientSide()) return;
        Player player = event.getPlayer();
        if (player == null) return;
        if (GhostHistoryManager.isProcessingHistory) return;

        BlockPos pos = event.getPos().immutable();
        BlockState oldState = event.getState();
        CompoundTag oldNbt = null;
        var be = event.getLevel().getBlockEntity(pos);
        if (be != null) oldNbt = be.saveWithFullMetadata(event.getLevel().registryAccess());

        BlockSnapshot before = new BlockSnapshot(oldState, oldNbt);

        WorldHistoryManager.get((Level)event.getLevel()).pushAction(
            new WorldHistoryManager.HistoryAction("Manual Break", Map.of(pos, BlockSnapshot.AIR)),
            (Level)event.getLevel(),
            Map.of(pos, before)
        );
    }
}