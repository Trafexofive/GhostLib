package com.example.ghostlib.event;

import com.example.ghostlib.GhostLib;
import com.example.ghostlib.util.GhostJobManager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Handles server-side level ticks to drive the GhostJobManager.
 */
@EventBusSubscriber(modid = GhostLib.MODID)
public class LevelTickHandler {

    /**
     * Ticks the job manager for the current level.
     */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        GhostJobManager.get(event.getLevel()).tick(event.getLevel());
    }
}
