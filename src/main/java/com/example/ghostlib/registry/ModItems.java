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

    public static final DeferredItem<Item> DRONE_PORT = ITEMS.register("drone_port",
            () -> new BlockItem(ModBlocks.DRONE_PORT.get(), new Item.Properties()));

    public static final DeferredItem<Item> LOGISTICAL_CHEST = ITEMS.register("logistical_chest",
            () -> new BlockItem(ModBlocks.LOGISTICAL_CHEST.get(), new Item.Properties()));

    public static final DeferredItem<Item> PASSIVE_PROVIDER_CHEST = ITEMS.register("passive_provider_chest",
            () -> new BlockItem(ModBlocks.PASSIVE_PROVIDER_CHEST.get(), new Item.Properties()));
    
    public static final DeferredItem<Item> REQUESTER_CHEST = ITEMS.register("requester_chest",
            () -> new BlockItem(ModBlocks.REQUESTER_CHEST.get(), new Item.Properties()));
    
    public static final DeferredItem<Item> STORAGE_CHEST = ITEMS.register("storage_chest",
            () -> new BlockItem(ModBlocks.STORAGE_CHEST.get(), new Item.Properties()));
    
    public static final DeferredItem<Item> ACTIVE_PROVIDER_CHEST = ITEMS.register("active_provider_chest",
            () -> new BlockItem(ModBlocks.ACTIVE_PROVIDER_CHEST.get(), new Item.Properties()));
    
    public static final DeferredItem<Item> BUFFER_CHEST = ITEMS.register("buffer_chest",
            () -> new BlockItem(ModBlocks.BUFFER_CHEST.get(), new Item.Properties()));

    public static final DeferredItem<Item> BLUEPRINT = ITEMS.register("blueprint",
            () -> new com.example.ghostlib.item.BlueprintItem(new Item.Properties().stacksTo(64)));

    public static final DeferredItem<Item> TEST_ITEM = ITEMS.register("test_item",
            () -> new com.example.ghostlib.item.TestGuiItem(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<Item> ASSEMBLER_TEST_ITEM = ITEMS.register("assembler_test_tool",
            () -> new com.example.ghostlib.item.AssemblerTestItem(new Item.Properties().stacksTo(1)));

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}