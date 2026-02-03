package com.example.ghostlib.event;

import com.example.ghostlib.GhostLib;
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

        String tag = "ghostlib_received_v3";
        if (!player.getPersistentData().contains(tag)) {
            player.getPersistentData().putBoolean(tag, true);

            // Give Drones
            player.getInventory().add(new ItemStack(ModItems.DRONE_SPAWN_EGG.get(), 16));

            player.displayClientMessage(net.minecraft.network.chat.Component.literal("GhostLib Systems Online")
                    .withStyle(net.minecraft.ChatFormatting.AQUA), false);
            // Drones are given as items. Players must manually deploy them.
        }
    }
}
