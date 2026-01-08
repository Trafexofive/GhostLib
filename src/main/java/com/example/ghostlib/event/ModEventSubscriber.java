package com.example.ghostlib.event;

import com.example.ghostlib.GhostLib;
import com.example.ghostlib.entity.DroneEntity;
import com.example.ghostlib.entity.PortDroneEntity;
import com.example.ghostlib.registry.ModEntities;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;

@EventBusSubscriber(modid = GhostLib.MODID, bus = EventBusSubscriber.Bus.MOD)
public class ModEventSubscriber {

    @SubscribeEvent
    public static void onAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(ModEntities.DRONE.get(), DroneEntity.createAttributes().build());
        event.put(ModEntities.PORT_DRONE.get(), DroneEntity.createAttributes().build()); // Using same attributes for both for now
    }
}
