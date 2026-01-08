package com.example.ghostlib.block;

import com.example.ghostlib.block.entity.DronePortMemberBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

public class DronePortMemberBlock extends BaseEntityBlock {
    public static final com.mojang.serialization.MapCodec<DronePortMemberBlock> CODEC = simpleCodec(DronePortMemberBlock::new);

    public DronePortMemberBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected com.mojang.serialization.MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DronePortMemberBlockEntity(pos, state);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof DronePortMemberBlockEntity member) {
                member.getControllerPos().ifPresent(cPos -> {
                    if (level.getBlockEntity(cPos) instanceof com.example.ghostlib.block.entity.DronePortControllerBlockEntity controller) {
                        controller.disassemble();
                    }
                });
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof DronePortMemberBlockEntity member) {
            return member.delegateInteraction(player, InteractionHand.MAIN_HAND);
        }
        return InteractionResult.PASS;
    }
}
