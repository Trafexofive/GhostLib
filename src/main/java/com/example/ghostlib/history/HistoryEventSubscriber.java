package com.example.ghostlib.history;

import com.example.ghostlib.GhostLib;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
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

@EventBusSubscriber(modid = GhostLib.MODID, bus = EventBusSubscriber.Bus.GAME)
public class HistoryEventSubscriber {

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled()) return;
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (GhostHistoryManager.isProcessingHistory) return;

        BlockState oldState = event.getBlockSnapshot().getState();
        BlockPos pos = event.getPos();
        
        // Capture old data from level
        net.minecraft.nbt.CompoundTag oldData = null;
        net.minecraft.world.level.block.entity.BlockEntity oldBe = event.getLevel().getBlockEntity(pos);
        if (oldBe != null) {
            oldData = oldBe.saveWithFullMetadata(event.getLevel().registryAccess());
        }

        if (oldState.canBeReplaced()) {
            oldState = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            oldData = null;
        }

        BlockState newState = event.getPlacedBlock();
        GhostHistoryManager.recordAction(player, Collections.singletonList(
            new GhostHistoryManager.StateChange(pos.immutable(), oldState, newState, oldData, null)
        ));
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled()) return;
        if (event.getLevel().isClientSide()) return;
        Player player = event.getPlayer();
        if (GhostHistoryManager.isProcessingHistory) return;

        BlockState oldState = event.getState();
        BlockPos pos = event.getPos();
        
        // Capture old data from level before it is gone
        net.minecraft.nbt.CompoundTag oldData = null;
        net.minecraft.world.level.block.entity.BlockEntity be = event.getLevel().getBlockEntity(pos);
        if (be != null) oldData = be.saveWithFullMetadata(event.getLevel().registryAccess());

        GhostHistoryManager.recordAction(player, Collections.singletonList(
            new GhostHistoryManager.StateChange(pos.immutable(), oldState, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), oldData, null)
        ));
    }
}