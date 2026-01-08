package com.example.ghostlib.event;

import com.example.ghostlib.GhostLib;
import com.example.ghostlib.entity.DroneEntity;
import com.example.ghostlib.registry.ModEntities;
import com.example.ghostlib.registry.ModItems;
import com.example.ghostlib.block.GhostBlock;
import com.example.ghostlib.history.GhostHistoryManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.*;
import java.util.stream.StreamSupport;

/**
 * Handles server-side game events for GhostLib.
 * <p>Responsibilities:</p>
 * <ul>
 *   <li><b>Command Registration:</b> Registers the /ghost command.</li>
 *   <li><b>History Tracking:</b> Records block placements and breaks for Undo/Redo.</li>
 *   <li><b>Persistence:</b> Triggers history saving/loading on server lifecycle events.</li>
 *   <li><b>Drone Spawning:</b> Handles auto-deployment of personal drones from player inventory.</li>
 * </ul>
 */
@EventBusSubscriber(modid = GhostLib.MODID, bus = EventBusSubscriber.Bus.GAME)
public class CommonModEventSubscriber {

    private static final Map<UUID, List<GhostHistoryManager.StateChange>> TICK_CHANGES = new HashMap<>();

    @SubscribeEvent
    public static void onServerStarting(net.neoforged.neoforge.event.server.ServerStartingEvent event) {
        GhostHistoryManager.loadHistory(event.getServer().overworld());
    }

    @SubscribeEvent
    public static void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        GhostHistoryManager.saveHistory(event.getServer().overworld());
    }

    @SubscribeEvent
    public static void onRegisterCommands(net.neoforged.neoforge.event.RegisterCommandsEvent event) {
        com.example.ghostlib.command.GhostCommand.register(event.getDispatcher());
    }

    /**
     * Records manual block placements to history.
     * Uses the snapshot state (old state) to ensure Undo removes the block.
     */
    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof Player player && !event.getLevel().isClientSide() && !GhostHistoryManager.isProcessingHistory) {
            BlockState oldState = event.getBlockSnapshot().getState();
            TICK_CHANGES.computeIfAbsent(player.getUUID(), k -> new ArrayList<>()).add(
                new GhostHistoryManager.StateChange(event.getPos().immutable(), oldState, event.getPlacedBlock())
            );
        }
    }

    /**
     * Records manual block breaks to history.
     * Records the transition from the existing block state to AIR.
     */
    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        Player player = event.getPlayer();
        if (player != null && !player.level().isClientSide() && !GhostHistoryManager.isProcessingHistory) {
            // We record the transition from the CURRENT state to AIR
            TICK_CHANGES.computeIfAbsent(player.getUUID(), k -> new ArrayList<>()).add(
                new GhostHistoryManager.StateChange(event.getPos().immutable(), event.getState(), Blocks.AIR.defaultBlockState())
            );
        }
    }

    /**
     * Aggregates tick changes and commits them to history.
     * This prevents fragmentation of atomic actions (like multi-block placements).
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!TICK_CHANGES.isEmpty()) {
            for (var entry : TICK_CHANGES.entrySet()) {
                Player player = event.getServer().getPlayerList().getPlayer(entry.getKey());
                if (player != null) {
                    GhostHistoryManager.recordAction(player, entry.getValue());
                }
            }
            TICK_CHANGES.clear();
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide || player.level().getGameTime() % 40 != 0) return;

        // Auto-deploy Drones from Inventory
        ItemStack eggStack = new ItemStack(ModItems.DRONE_SPAWN_EGG.get());
        int eggSlot = player.getInventory().findSlotMatchingItem(eggStack);
        
        if (eggSlot != -1) {
            BlockPos pPos = player.blockPosition();
            boolean ghostsNearby = false;
            // Check radius for any construction or deconstruction markers
            for (BlockPos pos : BlockPos.betweenClosed(pPos.offset(-16, -8, -16), pPos.offset(16, 8, 16))) {
                if (player.level().getBlockState(pos).getBlock() instanceof GhostBlock || 
                    com.example.ghostlib.util.GhostJobManager.get(player.level()).isDeconstructAt(pos)) {
                    ghostsNearby = true;
                    break;
                }
            }

            if (ghostsNearby) {
                long droneCount = StreamSupport.stream(((ServerLevel)player.level()).getEntities().getAll().spliterator(), false)
                    .filter(e -> e instanceof DroneEntity)
                    .filter(e -> e.distanceTo(player) < 64)
                    .count();

                if (droneCount < 3) {
                    player.getInventory().getItem(eggSlot).shrink(1);
                    DroneEntity drone = new DroneEntity(ModEntities.DRONE.get(), player.level());
                    drone.setPos(player.getX(), player.getY() + 2.0, player.getZ());
                    player.level().addFreshEntity(drone);
                }
            }
        }
    }
}
