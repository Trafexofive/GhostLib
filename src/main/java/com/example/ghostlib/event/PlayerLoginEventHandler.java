package com.example.ghostlib.event;

import com.example.ghostlib.GhostLib;
import com.example.ghostlib.entity.DroneEntity;
import com.example.ghostlib.registry.ModEntities;
import com.example.ghostlib.registry.ModItems;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

@EventBusSubscriber(modid = GhostLib.MODID, bus = EventBusSubscriber.Bus.GAME)
public class PlayerLoginEventHandler {

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        Player player = event.getEntity();
        
        // Use a persistent NBT tag to ensure we only give items once
        String tag = "ghostlib_received_start_items";
        if (!player.getPersistentData().contains(tag)) {
            player.getPersistentData().putBoolean(tag, true);
            
            // Give Solar Panel (from VoltLink)
            player.getInventory().add(new ItemStack(com.example.voltlink.registry.ModItems.SOLAR_PANEL_ITEM.get(), 4));
            
            // Give Electric Furnace Blueprint
            player.getInventory().add(new ItemStack(ModItems.BLUEPRINT_FURNACE.get(), 1));
            
            // Give 16 Iron Blocks for building it
            player.getInventory().add(new ItemStack(net.minecraft.world.item.Items.IRON_BLOCK, 16));
            
            // Give 1 Controller
            player.getInventory().add(new ItemStack(ModItems.ELECTRIC_FURNACE_CONTROLLER.get(), 1));

            // Give 16 Drone Eggs
            player.getInventory().add(new ItemStack(ModItems.DRONE_SPAWN_EGG.get(), 16));

            // Give 16 Electric Poles
            player.getInventory().add(new ItemStack(com.example.voltlink.registry.ModItems.ELECTRIC_POLE_ITEM.get(), 16));

            // Give Deconstruction Stick
            player.getInventory().add(new ItemStack(ModItems.DECONSTRUCTION_STICK.get(), 1));

            player.displayClientMessage(net.minecraft.network.chat.Component.literal("GhostLib Construction System: ACTIVE").withStyle(net.minecraft.ChatFormatting.AQUA), false);
            player.displayClientMessage(net.minecraft.network.chat.Component.literal("VoltLink Industrial Grid: ONLINE").withStyle(net.minecraft.ChatFormatting.GOLD), false);

            // Spawn 3 Personal Drones
            if (!player.level().isClientSide) {
                for (int i = 0; i < 3; i++) {
                    DroneEntity drone = new DroneEntity(ModEntities.DRONE.get(), player.level());
                    // Position around the player
                    double angle = i * (2 * Math.PI / 3);
                    double x = player.getX() + Math.cos(angle) * 2;
                    double z = player.getZ() + Math.sin(angle) * 2;
                    drone.setPos(x, player.getY() + 2.5, z);
                    player.level().addFreshEntity(drone);
                }
            }
        }
    }
}
