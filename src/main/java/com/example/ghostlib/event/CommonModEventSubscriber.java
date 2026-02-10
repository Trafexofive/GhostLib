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
            
            // player.getInventory().add(new ItemStack(ModItems.DRONE_SPAWN_EGG.get(), 64));
            // player.getInventory().add(new ItemStack(ModItems.MATERIAL_STORAGE.get(), 64));
            // player.getInventory().add(new ItemStack(ModItems.DRONE_PORT.get(), 64));
            player.getInventory().add(new ItemStack(ModItems.BLUEPRINT.get(), 16));
            
            // give player transmutation tablette at login for progression
            // player.getInventory().add(new ItemStack(

            // player.getInventory().add(new ItemStack(ModItems.LOGISTICAL_CHEST.get(), 64));
            // player.getInventory().add(new ItemStack(ModItems.PASSIVE_PROVIDER_CHEST.get(), 64));
            // player.getInventory().add(new ItemStack(ModItems.REQUESTER_CHEST.get(), 64));
            // player.getInventory().add(new ItemStack(ModItems.STORAGE_CHEST.get(), 64));
            // player.getInventory().add(new ItemStack(ModItems.ACTIVE_PROVIDER_CHEST.get(), 64));
            // player.getInventory().add(new ItemStack(ModItems.BUFFER_CHEST.get(), 64));

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

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof Player player && !event.getLevel().isClientSide() && !GhostHistoryManager.isProcessingHistory) {
            BlockPos pos = event.getPos().immutable();
            com.example.ghostlib.util.GhostJobManager.get(player.level()).removeJob(pos);

            BlockState oldState = event.getBlockSnapshot().getState();
            TICK_CHANGES.computeIfAbsent(player.getUUID(), k -> new ArrayList<>()).add(
                new GhostHistoryManager.StateChange(pos, oldState, event.getPlacedBlock(), null, null)
            );
        }
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        Player player = event.getPlayer();
        if (player != null && !player.level().isClientSide() && !GhostHistoryManager.isProcessingHistory) {
            BlockPos pos = event.getPos().immutable();
            com.example.ghostlib.util.GhostJobManager.get(player.level()).removeJob(pos);

            TICK_CHANGES.computeIfAbsent(player.getUUID(), k -> new ArrayList<>()).add(
                new GhostHistoryManager.StateChange(pos, event.getState(), Blocks.AIR.defaultBlockState(), null, null)
            );
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        for (ServerLevel sl : event.getServer().getAllLevels()) {
            com.example.ghostlib.util.GhostJobManager.get(sl).tick(sl);
        }

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
        if (player.level().isClientSide) return;

        // Magic Charging: 100 FE every 40 ticks
        if (player.level().getGameTime() % 40 == 0) {
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (stack.is(ModItems.DRONE_SPAWN_EGG.get())) {
                    net.minecraft.world.item.component.CustomData data = stack.get(net.minecraft.core.component.DataComponents.ENTITY_DATA);
                    net.minecraft.nbt.CompoundTag tag = data != null ? data.copyTag() : new net.minecraft.nbt.CompoundTag();
                    
                    // Ensure ID is present if we are adding data to a fresh egg
                    if (!tag.contains("id")) {
                        tag.putString("id", ModEntities.DRONE.getId().toString());
                    }

                    int energy = tag.getInt("Energy");
                    // Base max energy is 10000
                    if (energy < 10000) {
                        tag.putInt("Energy", Math.min(energy + 100, 10000));
                        stack.set(net.minecraft.core.component.DataComponents.ENTITY_DATA, net.minecraft.world.item.component.CustomData.of(tag));
                    }
                }
            }
        }

        if (player.level().getGameTime() % 40 != 0) return;

        ItemStack eggStack = new ItemStack(ModItems.DRONE_SPAWN_EGG.get());
        int eggSlot = player.getInventory().findSlotMatchingItem(eggStack);
        
        if (eggSlot != -1) {
            BlockPos pPos = player.blockPosition();
            boolean ghostsNearby = false;
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
                    ItemStack item = player.getInventory().getItem(eggSlot);
                    DroneEntity drone = new DroneEntity(ModEntities.DRONE.get(), player.level());
                    
                    net.minecraft.world.item.component.CustomData customData = item.get(net.minecraft.core.component.DataComponents.ENTITY_DATA);
                    if (customData != null) {
                        customData.loadInto(drone);
                    }

                    item.shrink(1);
                    drone.setPos(player.getX(), player.getY() + 2.0, player.getZ());
                    drone.setOwner(player);
                    player.level().addFreshEntity(drone);
                }
            }
        }
    }
}
