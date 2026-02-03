package com.example.ghostlib.registry;

import com.example.ghostlib.GhostLib;
import com.example.ghostlib.block.entity.GhostBlockEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, GhostLib.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<GhostBlockEntity>> GHOST_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("ghost_block_entity", () ->
                    BlockEntityType.Builder.of(GhostBlockEntity::new, ModBlocks.GHOST_BLOCK.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.example.ghostlib.block.entity.MaterialStorageBlockEntity>> MATERIAL_STORAGE =
            BLOCK_ENTITIES.register("material_storage",
                    () -> BlockEntityType.Builder.of(com.example.ghostlib.block.entity.MaterialStorageBlockEntity::new, ModBlocks.MATERIAL_STORAGE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.example.ghostlib.block.entity.DronePortBlockEntity>> DRONE_PORT =
            BLOCK_ENTITIES.register("drone_port",
                    () -> BlockEntityType.Builder.of(com.example.ghostlib.block.entity.DronePortBlockEntity::new, ModBlocks.DRONE_PORT.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.example.ghostlib.block.entity.LogisticalChestBlockEntity>> LOGISTICAL_CHEST =
            BLOCK_ENTITIES.register("logistical_chest",
                    () -> BlockEntityType.Builder.of(com.example.ghostlib.block.entity.LogisticalChestBlockEntity::new, 
                        ModBlocks.LOGISTICAL_CHEST.get(),
                        ModBlocks.PASSIVE_PROVIDER_CHEST.get(),
                        ModBlocks.REQUESTER_CHEST.get(),
                        ModBlocks.STORAGE_CHEST.get(),
                        ModBlocks.ACTIVE_PROVIDER_CHEST.get(),
                        ModBlocks.BUFFER_CHEST.get()
                    ).build(null));

    public static void register(IEventBus eventBus) {
        BLOCK_ENTITIES.register(eventBus);
    }
}