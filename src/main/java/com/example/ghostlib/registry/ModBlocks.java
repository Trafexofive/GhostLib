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

    public static final DeferredBlock<Block> DRONE_PORT_CONTROLLER = BLOCKS.register("drone_port_controller",
        () -> new com.example.ghostlib.block.DronePortControllerBlock(BlockBehaviour.Properties.of().strength(5.0f).noOcclusion()));

    public static final DeferredBlock<Block> DRONE_PORT_MEMBER = BLOCKS.register("drone_port_member",
        () -> new com.example.ghostlib.block.DronePortMemberBlock(BlockBehaviour.Properties.of().strength(5.0f)));

    public static final DeferredBlock<Block> MATERIAL_STORAGE = BLOCKS.register("material_storage",
            () -> new com.example.ghostlib.block.MaterialStorageBlock(BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.0f).sound(SoundType.METAL)));

    public static void register(IEventBus eventBus) {
        BLOCKS.register(eventBus);
    }
}
