package com.example.ghostlib.block;

import com.example.ghostlib.block.entity.MaterialStorageBlockEntity;
import com.example.ghostlib.block.entity.ElectricFurnaceControllerBlockEntity;
import com.example.ghostlib.registry.ModBlockEntities;
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

public class MaterialStorageBlock extends BaseEntityBlock {
    public static final com.mojang.serialization.MapCodec<MaterialStorageBlock> CODEC = simpleCodec(MaterialStorageBlock::new);

    public MaterialStorageBlock(BlockBehaviour.Properties properties) {
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
        return new MaterialStorageBlockEntity(pos, state);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof MaterialStorageBlockEntity member) {
                member.getControllerPos().ifPresent(cPos -> {
                    if (level.getBlockEntity(cPos) instanceof ElectricFurnaceControllerBlockEntity controller) {
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
        if (be instanceof MaterialStorageBlockEntity chest) {
            // Simple interaction: Open GUI or just dump items?
            // For now, let's just print contents or allow simple right-click insert?
            // Since we don't have a GUI yet, let's allow inserting held item.
            net.minecraft.world.item.ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
            if (!held.isEmpty()) {
                net.minecraft.world.item.ItemStack remaining = chest.getInventory().insertItem(0, held, false);
                player.setItemInHand(InteractionHand.MAIN_HAND, remaining);
                return InteractionResult.SUCCESS;
            }
        }
        return InteractionResult.PASS;
    }
}
