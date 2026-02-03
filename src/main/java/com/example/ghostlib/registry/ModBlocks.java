package com.example.ghostlib.registry;

import com.example.ghostlib.GhostLib;
import com.example.ghostlib.block.GhostBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(GhostLib.MODID);

    public static final DeferredBlock<Block> GHOST_BLOCK = BLOCKS.register("ghost_block",
        () -> new GhostBlock(BlockBehaviour.Properties.of()
                .mapColor(MapColor.NONE)
                .noCollission()
                .noOcclusion()
                .instabreak()
                .replaceable()
                .forceSolidOff()));

    public static final DeferredBlock<Block> MATERIAL_STORAGE = BLOCKS.register("material_storage",
            () -> new com.example.ghostlib.block.MaterialStorageBlock(BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.0f).sound(SoundType.METAL)));

    public static final DeferredBlock<Block> DRONE_PORT = BLOCKS.register("drone_port",
            () -> new com.example.ghostlib.block.DronePortBlock());

    public static final DeferredBlock<Block> LOGISTICAL_CHEST = BLOCKS.register("logistical_chest",
            () -> new com.example.ghostlib.block.LogisticalChestBlock());

    public static final DeferredBlock<Block> PASSIVE_PROVIDER_CHEST = BLOCKS.register("passive_provider_chest",
            () -> new com.example.ghostlib.block.LogisticalChestBlock());
    
    public static final DeferredBlock<Block> REQUESTER_CHEST = BLOCKS.register("requester_chest",
            () -> new com.example.ghostlib.block.LogisticalChestBlock());
    
    public static final DeferredBlock<Block> STORAGE_CHEST = BLOCKS.register("storage_chest",
            () -> new com.example.ghostlib.block.LogisticalChestBlock());
    
    public static final DeferredBlock<Block> ACTIVE_PROVIDER_CHEST = BLOCKS.register("active_provider_chest",
            () -> new com.example.ghostlib.block.LogisticalChestBlock());
    
    public static final DeferredBlock<Block> BUFFER_CHEST = BLOCKS.register("buffer_chest",
            () -> new com.example.ghostlib.block.LogisticalChestBlock());

    public static void register(IEventBus eventBus) {
        BLOCKS.register(eventBus);
    }
}