package com.example.ghostlib.block;

import com.example.ghostlib.block.entity.LogisticalChestBlockEntity;
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
import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;

public class LogisticalChestBlock extends net.minecraft.world.level.block.Block implements net.minecraft.world.level.block.EntityBlock {
    public enum ChestType implements net.minecraft.util.StringRepresentable {
        PASSIVE_PROVIDER("passive_provider"),
        REQUESTER("requester"),
        STORAGE("storage"),
        ACTIVE_PROVIDER("active_provider"),
        BUFFER("buffer");

        private final String name;
        ChestType(String name) { this.name = name; }
        @Override public String getSerializedName() { return name; }
    }

    public static final net.minecraft.world.level.block.state.properties.EnumProperty<ChestType> TYPE = net.minecraft.world.level.block.state.properties.EnumProperty.create("type", ChestType.class);

    public LogisticalChestBlock() {
        super(net.minecraft.world.level.block.state.BlockBehaviour.Properties.of().mapColor(net.minecraft.world.level.material.MapColor.WOOD).strength(2.5f).noOcclusion());
        this.registerDefaultState(this.stateDefinition.any().setValue(TYPE, ChestType.STORAGE));
    }

    @Override
    protected void createBlockStateDefinition(net.minecraft.world.level.block.state.StateDefinition.Builder<net.minecraft.world.level.block.Block, net.minecraft.world.level.block.state.BlockState> builder) {
        builder.add(TYPE);
    }

    @org.jetbrains.annotations.Nullable
    @Override
    public net.minecraft.world.level.block.state.BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
        String name = context.getItemInHand().getItem().toString();
        ChestType type = ChestType.STORAGE;
        if (name.contains("passive_provider")) type = ChestType.PASSIVE_PROVIDER;
        else if (name.contains("requester")) type = ChestType.REQUESTER;
        else if (name.contains("active_provider")) type = ChestType.ACTIVE_PROVIDER;
        else if (name.contains("buffer")) type = ChestType.BUFFER;
        
        return this.defaultBlockState().setValue(TYPE, type);
    }

    @Override
    protected net.minecraft.world.InteractionResult useWithoutItem(net.minecraft.world.level.block.state.BlockState state, net.minecraft.world.level.Level level, BlockPos pos, net.minecraft.world.entity.player.Player player, net.minecraft.world.phys.BlockHitResult hitResult) {
        System.out.println("LogisticalChestBlock: Interacted at " + pos + " by " + player.getName().getString() + " (ClientSide: " + level.isClientSide + ")");
        if (!level.isClientSide && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            if (level.getBlockEntity(pos) instanceof com.example.ghostlib.block.entity.LogisticalChestBlockEntity chest) {
                serverPlayer.openMenu(chest, buf -> {
                    buf.writeBlockPos(pos);
                    com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType.BLOCK_STATE_STREAM_CODEC.encode(buf, state);
                });
                return net.minecraft.world.InteractionResult.CONSUME;
            }
        }
        return net.minecraft.world.InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public net.minecraft.world.level.block.RenderShape getRenderShape(net.minecraft.world.level.block.state.BlockState state) {
        return net.minecraft.world.level.block.RenderShape.MODEL;
    }

    @org.jetbrains.annotations.Nullable
    @Override
    public net.minecraft.world.level.block.entity.BlockEntity newBlockEntity(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        return new LogisticalChestBlockEntity(pos, state);
    }
}