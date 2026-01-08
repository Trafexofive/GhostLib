package com.example.ghostlib.block;

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
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

public class ElectricFurnaceControllerBlock extends BaseEntityBlock {
    public static final com.mojang.serialization.MapCodec<ElectricFurnaceControllerBlock> CODEC = simpleCodec(ElectricFurnaceControllerBlock::new);

    public ElectricFurnaceControllerBlock(Properties properties) {
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

    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof ElectricFurnaceControllerBlockEntity controller) {
                if (!controller.isAssembled()) {
                    if (controller.validateStructure()) {
                        controller.assemble();
                        player.displayClientMessage(net.minecraft.network.chat.Component.literal("Electric Furnace Assembled!").withStyle(net.minecraft.ChatFormatting.GREEN), true);
                    } else {
                        player.displayClientMessage(net.minecraft.network.chat.Component.literal("Structure Invalid! (Needs 3x3x3 iron-chest cube)").withStyle(net.minecraft.ChatFormatting.RED), true);
                    }
                } else {
                    // Interaction logic: Insert/Take items
                    return controller.handlePlayerInteraction(player, InteractionHand.MAIN_HAND);
                }
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new com.example.ghostlib.block.entity.ElectricFurnaceControllerBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, ModBlockEntities.ELECTRIC_FURNACE_CONTROLLER.get(), com.example.ghostlib.block.entity.ElectricFurnaceControllerBlockEntity::tick);
    }
}
