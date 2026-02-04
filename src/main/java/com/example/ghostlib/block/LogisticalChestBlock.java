package com.example.ghostlib.block;

import com.example.ghostlib.block.entity.LogisticalChestBlockEntity;
import com.example.ghostlib.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;

public class LogisticalChestBlock extends BaseEntityBlock {
    public enum ChestType implements StringRepresentable {
        PASSIVE_PROVIDER("passive_provider"),
        REQUESTER("requester"),
        STORAGE("storage"),
        ACTIVE_PROVIDER("active_provider"),
        BUFFER("buffer");

        private final String name;
        ChestType(String name) { this.name = name; }
        @Override public String getSerializedName() { return name; }
    }

    public static final EnumProperty<ChestType> TYPE = EnumProperty.create("type", ChestType.class);

    public LogisticalChestBlock() {
        super(BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.5f).noOcclusion());
        this.registerDefaultState(this.stateDefinition.any().setValue(TYPE, ChestType.STORAGE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(TYPE);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
        String name = context.getItemInHand().getItem().toString();
        ChestType type = ChestType.STORAGE;
        if (name.contains("passive_provider")) type = ChestType.PASSIVE_PROVIDER;
        else if (name.contains("requester")) type = ChestType.REQUESTER;
        else if (name.contains("active_provider")) type = ChestType.ACTIVE_PROVIDER;
        else if (name.contains("buffer")) type = ChestType.BUFFER;
        else if (name.contains("storage")) type = ChestType.STORAGE;
        
        return this.defaultBlockState().setValue(TYPE, type);
    }

    @Override
    protected net.minecraft.world.InteractionResult useWithoutItem(BlockState state, net.minecraft.world.level.Level level, BlockPos pos, net.minecraft.world.entity.player.Player player, net.minecraft.world.phys.BlockHitResult hitResult) {
        if (!level.isClientSide && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType.openUI(serverPlayer, pos);
        }
        return net.minecraft.world.InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected com.mojang.serialization.MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(p -> new LogisticalChestBlock());
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LogisticalChestBlockEntity(pos, state);
    }
}
