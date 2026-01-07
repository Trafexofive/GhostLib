package com.example.ghostlib.registry;

import com.example.ghostlib.GhostLib;
import com.example.ghostlib.item.GhostPlacerItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(GhostLib.MODID);

    public static final DeferredItem<Item> GHOST_PLACER = ITEMS.register("ghost_placer",
            () -> new GhostPlacerItem(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<Item> DRONE_SPAWN_EGG = ITEMS.register("drone_spawn_egg",
            () -> new DeferredSpawnEggItem(ModEntities.DRONE, 0x000000, 0x00FFFF, new Item.Properties()));

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}