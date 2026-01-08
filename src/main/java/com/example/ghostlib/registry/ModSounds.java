package com.example.ghostlib.registry;

import com.example.ghostlib.GhostLib;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS = DeferredRegister.create(BuiltInRegistries.SOUND_EVENT, GhostLib.MODID);

    public static final DeferredHolder<SoundEvent, SoundEvent> DRONE_FLY = SOUND_EVENTS.register("drone_fly",
            () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(GhostLib.MODID, "drone_fly")));

    public static final DeferredHolder<SoundEvent, SoundEvent> DRONE_WORK = SOUND_EVENTS.register("drone_work",
            () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(GhostLib.MODID, "drone_work")));

    public static void register(IEventBus eventBus) {
        SOUND_EVENTS.register(eventBus);
    }
}
