package com.example.ghostlib.registry;

import com.example.ghostlib.GhostLib;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(GhostLib.MODID);

    public static final DeferredItem<Item> DRONE_SPAWN_EGG = ITEMS.register("drone_spawn_egg",
            () -> new DeferredSpawnEggItem(ModEntities.DRONE, 0x000000, 0x00FFFF, new Item.Properties()));

    public static final DeferredItem<Item> MATERIAL_STORAGE = ITEMS.register("material_storage",
            () -> new BlockItem(ModBlocks.MATERIAL_STORAGE.get(), new Item.Properties()));

    public static final DeferredItem<Item> BLUEPRINT = ITEMS.register("blueprint",
            () -> new com.example.ghostlib.item.BlueprintItem(new Item.Properties().stacksTo(1)));

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}