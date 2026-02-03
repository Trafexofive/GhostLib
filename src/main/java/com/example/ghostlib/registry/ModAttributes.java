package com.example.ghostlib.registry;

import com.example.ghostlib.GhostLib;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModAttributes {
    public static final DeferredRegister<Attribute> ATTRIBUTES = DeferredRegister.create(Registries.ATTRIBUTE, GhostLib.MODID);

    public static final DeferredHolder<Attribute, Attribute> INTERACTION_RANGE = ATTRIBUTES.register("interaction_range",
            () -> new RangedAttribute("attribute.name.ghostlib.interaction_range", 4.5D, 0.0D, 64.0D).setSyncable(true));

    public static final DeferredHolder<Attribute, Attribute> SEARCH_RANGE = ATTRIBUTES.register("search_range",
            () -> new RangedAttribute("attribute.name.ghostlib.search_range", 64.0D, 0.0D, 2048.0D).setSyncable(true));

    public static final DeferredHolder<Attribute, Attribute> WORK_SPEED = ATTRIBUTES.register("work_speed",
            () -> new RangedAttribute("attribute.name.ghostlib.work_speed", 1.0D, 0.1D, 20.0D).setSyncable(true));

    public static final DeferredHolder<Attribute, Attribute> MAX_ENERGY = ATTRIBUTES.register("max_energy",
            () -> new RangedAttribute("attribute.name.ghostlib.max_energy", 10000.0D, 100.0D, 1000000.0D).setSyncable(true));

    public static final DeferredHolder<Attribute, Attribute> ENERGY_EFFICIENCY = ATTRIBUTES.register("energy_efficiency",
            () -> new RangedAttribute("attribute.name.ghostlib.energy_efficiency", 1.0D, 0.1D, 10.0D).setSyncable(true));

    public static final DeferredHolder<Attribute, Attribute> SILK_TOUCH = ATTRIBUTES.register("silk_touch",
            () -> new RangedAttribute("attribute.name.ghostlib.silk_touch", 1.0D, 0.0D, 1.0D).setSyncable(true));

    public static void register(IEventBus eventBus) {
        ATTRIBUTES.register(eventBus);
    }
}
