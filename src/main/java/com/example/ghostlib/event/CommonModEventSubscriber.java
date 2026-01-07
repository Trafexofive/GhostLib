package com.example.ghostlib.event;

import com.example.ghostlib.GhostLib;
import com.example.ghostlib.registry.ModEntities;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;

@EventBusSubscriber(modid = GhostLib.MODID, bus = EventBusSubscriber.Bus.MOD)
public class CommonModEventSubscriber {

    @SubscribeEvent
    public static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(ModEntities.DRONE.get(), com.example.ghostlib.entity.DroneEntity.createAttributes().build());
    }
}
