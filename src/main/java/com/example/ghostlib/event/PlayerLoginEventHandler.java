package com.example.ghostlib.event;

import com.example.ghostlib.GhostLib;
import com.example.ghostlib.entity.DroneEntity;
import com.example.ghostlib.registry.ModBlocks;
import com.example.ghostlib.registry.ModEntities;
import com.example.ghostlib.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

@EventBusSubscriber(modid = GhostLib.MODID, bus = EventBusSubscriber.Bus.GAME)
public class PlayerLoginEventHandler {

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        Player player = event.getEntity();
        
        String tag = "ghostlib_received_v3";
        if (!player.getPersistentData().contains(tag)) {
            player.getPersistentData().putBoolean(tag, true);
            
            // Give Drone Port Materials
            player.getInventory().add(new ItemStack(ModItems.DRONE_PORT_CONTROLLER_ITEM.get(), 1));
            player.getInventory().add(new ItemStack(ModItems.DRONE_PORT_MEMBER_ITEM.get(), 8));
            
            // Give Drone Port Blueprint
            player.getInventory().add(createBlueprint("Drone Port", createDronePortPattern()));

            // Give Drones
            player.getInventory().add(new ItemStack(ModItems.DRONE_SPAWN_EGG.get(), 16));

            player.displayClientMessage(net.minecraft.network.chat.Component.literal("GhostLib Systems Online").withStyle(net.minecraft.ChatFormatting.AQUA), false);

            // Spawn 3 Personal Drones
            if (!player.level().isClientSide) {
                for (int i = 0; i < 3; i++) {
                    DroneEntity drone = new DroneEntity(ModEntities.DRONE.get(), player.level());
                    double angle = i * (2 * Math.PI / 3);
                    double x = player.getX() + Math.cos(angle) * 2;
                    double z = player.getZ() + Math.sin(angle) * 2;
                    drone.setPos(x, player.getY() + 2.5, z);
                    player.level().addFreshEntity(drone);
                }
            }
        }
    }

    private static ItemStack createBlueprint(String name, CompoundTag patternTag) {
        ItemStack stack = new ItemStack(ModItems.BLUEPRINT.get());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(patternTag));
        stack.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal(name).withStyle(net.minecraft.ChatFormatting.YELLOW));
        return stack;
    }

    private static CompoundTag createDronePortPattern() {
        CompoundTag tag = new CompoundTag();
        ListTag patternList = new ListTag();
        
        // 3x3 Platform
        // Rel coordinates: 0..2, 0, 0..2
        // Controller at Center (1, 0, 1)
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                BlockState state;
                if (x == 1 && z == 1) {
                    state = ModBlocks.DRONE_PORT_CONTROLLER.get().defaultBlockState();
                } else {
                    state = ModBlocks.DRONE_PORT_MEMBER.get().defaultBlockState();
                }

                CompoundTag blockTag = new CompoundTag();
                blockTag.put("Rel", NbtUtils.writeBlockPos(new BlockPos(x, 0, z)));
                blockTag.put("State", NbtUtils.writeBlockState(state));
                patternList.add(blockTag);
            }
        }
        tag.put("Pattern", patternList);
        tag.putInt("SizeX", 3);
        tag.putInt("SizeY", 1);
        tag.putInt("SizeZ", 3);
        return tag;
    }
}