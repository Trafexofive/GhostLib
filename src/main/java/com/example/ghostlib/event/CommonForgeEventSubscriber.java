package com.example.ghostlib.event;

import com.example.ghostlib.GhostLib;
import com.example.ghostlib.history.GhostHistoryManager;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

@EventBusSubscriber(modid = GhostLib.MODID)
public class CommonForgeEventSubscriber {
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        GhostHistoryManager.clear(event.getEntity());
    }

    @SubscribeEvent
    public static void onLevelTick(net.neoforged.neoforge.event.tick.LevelTickEvent.Post event) {
        com.example.ghostlib.util.GhostJobManager.get(event.getLevel()).tick(event.getLevel());
    }
}
