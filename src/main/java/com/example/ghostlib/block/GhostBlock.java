package com.example.ghostlib.block;

import com.example.ghostlib.block.entity.GhostBlockEntity;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class GhostBlock extends BaseEntityBlock {
    public static final MapCodec<GhostBlock> CODEC = simpleCodec(GhostBlock::new);

    public GhostBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public void onRemove(BlockState state, net.minecraft.world.level.Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            if (!level.isClientSide && !com.example.ghostlib.history.GhostHistoryManager.isProcessingHistory) {
                com.example.ghostlib.util.GhostJobManager.get(level).removeJob(pos);
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GhostBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }
}
