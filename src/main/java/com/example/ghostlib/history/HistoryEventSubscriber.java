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
        if (oldState.canBeReplaced()) {
            oldState = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        }

        BlockState newState = event.getPlacedBlock();
        BlockPos pos = event.getPos();
        
        System.out.println("GhostHistory Record: " + oldState + " -> " + newState + " at " + pos);

        GhostHistoryManager.recordAction(player, Collections.singletonList(
            new GhostHistoryManager.StateChange(pos.immutable(), oldState, newState)
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
        
        GhostHistoryManager.recordAction(player, Collections.singletonList(
            new GhostHistoryManager.StateChange(pos.immutable(), oldState, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState())
        ));
    }
}