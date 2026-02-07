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

    @SubscribeEvent
    public static void onPlayerLogin(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        Player player = event.getEntity();
        String tag = "ghostlib_received_v14";
        if (!player.getPersistentData().contains(tag)) {
            player.getPersistentData().putBoolean(tag, true);
            
            player.getInventory().add(new ItemStack(ModItems.DRONE_SPAWN_EGG.get(), 64));
            player.getInventory().add(new ItemStack(ModItems.MATERIAL_STORAGE.get(), 64));
            player.getInventory().add(new ItemStack(ModItems.DRONE_PORT.get(), 64));
            player.getInventory().add(new ItemStack(ModItems.BLUEPRINT.get(), 16));
            
            player.getInventory().add(new ItemStack(ModItems.LOGISTICAL_CHEST.get(), 64));
            player.getInventory().add(new ItemStack(ModItems.PASSIVE_PROVIDER_CHEST.get(), 64));
            player.getInventory().add(new ItemStack(ModItems.REQUESTER_CHEST.get(), 64));
            player.getInventory().add(new ItemStack(ModItems.STORAGE_CHEST.get(), 64));
            player.getInventory().add(new ItemStack(ModItems.ACTIVE_PROVIDER_CHEST.get(), 64));
            player.getInventory().add(new ItemStack(ModItems.BUFFER_CHEST.get(), 64));

            // 2. Give Drone Port Blueprint
            player.getInventory().add(createBlueprint("Drone Port Array", createDronePortPattern()));

            player.displayClientMessage(net.minecraft.network.chat.Component.literal("Ghost Swarm Logistics Protocol Initialized (V14)").withStyle(net.minecraft.ChatFormatting.GOLD), false);
        }
    }

    private static ItemStack createBlueprint(String name, net.minecraft.nbt.CompoundTag patternTag) {
        ItemStack stack = new ItemStack(ModItems.BLUEPRINT.get());
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(patternTag));
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal(name).withStyle(net.minecraft.ChatFormatting.YELLOW));
        return stack;
    }

    private static net.minecraft.nbt.CompoundTag createDronePortPattern() {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        net.minecraft.nbt.ListTag patternList = new net.minecraft.nbt.ListTag();
        // Shift entire 3x3x3 structure to sit on ground (y starts at 0)
        // Controller is at (0,2,0) - TOP CENTER
        for (int x = -1; x <= 1; x++) {
            for (int y = 0; y <= 2; y++) {
                for (int z = -1; z <= 1; z++) {
                    BlockState state;
                    if (x == 0 && y == 2 && z == 0) {
                        state = com.example.ghostlib.registry.ModBlocks.DRONE_PORT.get().defaultBlockState();
                    } else {
                        state = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("factorycore", "machine_casing")).defaultBlockState();
                    }
                    
                    net.minecraft.nbt.CompoundTag blockTag = new net.minecraft.nbt.CompoundTag();
                    blockTag.put("Rel", net.minecraft.nbt.NbtUtils.writeBlockPos(new BlockPos(x, y, z)));
                    blockTag.put("State", net.minecraft.nbt.NbtUtils.writeBlockState(state));
                    patternList.add(blockTag);
                }
            }
        }
        tag.put("Pattern", patternList);
        tag.putInt("SizeX", 3); tag.putInt("SizeY", 3); tag.putInt("SizeZ", 3);
        return tag;
    }

    /**
     * Records manual block placements to history.
     * Uses the snapshot state (old state) to ensure Undo removes the block.
     */
    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof Player player && !event.getLevel().isClientSide() && !GhostHistoryManager.isProcessingHistory) {
            BlockPos pos = event.getPos().immutable();
            // Clear any pending automated work here
            com.example.ghostlib.util.GhostJobManager.get(player.level()).removeJob(pos);

            BlockState oldState = event.getBlockSnapshot().getState();
            TICK_CHANGES.computeIfAbsent(player.getUUID(), k -> new ArrayList<>()).add(
                new GhostHistoryManager.StateChange(pos, oldState, event.getPlacedBlock())
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
            BlockPos pos = event.getPos().immutable();
            // Clear any pending automated work here
            com.example.ghostlib.util.GhostJobManager.get(player.level()).removeJob(pos);

            TICK_CHANGES.computeIfAbsent(player.getUUID(), k -> new ArrayList<>()).add(
                new GhostHistoryManager.StateChange(pos, event.getState(), Blocks.AIR.defaultBlockState())
            );
        }
    }

    /**
     * Aggregates tick changes and commits them to history.
     * This prevents fragmentation of atomic actions (like multi-block placements).
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        // 1. Tick Job Manager
        for (ServerLevel sl : event.getServer().getAllLevels()) {
            com.example.ghostlib.util.GhostJobManager.get(sl).tick(sl);
        }

        // 2. Commit History Changes
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

                if (droneCount < 10) {
                    player.getInventory().getItem(eggSlot).shrink(1);
                    DroneEntity drone = new DroneEntity(ModEntities.DRONE.get(), player.level());
                    drone.setPos(player.getX(), player.getY() + 2.0, player.getZ());
                    drone.setOwner(player); // Fix: Assign owner so it can return!
                    player.level().addFreshEntity(drone);
                }
            }
        }
    }
}
