package com.example.ghostlib.item;

import com.example.ghostlib.block.entity.DronePortControllerBlockEntity;
import com.example.ghostlib.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

public class DronePortSpawnerItem extends Item {
    public DronePortSpawnerItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) return InteractionResult.SUCCESS;

        BlockPos center = context.getClickedPos().relative(context.getClickedFace());
        
        // Create 3x3 base
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                BlockPos pos = center.offset(x, 0, z);
                if (x == 0 && z == 0) {
                    level.setBlock(pos, ModBlocks.DRONE_PORT_CONTROLLER.get().defaultBlockState(), 3);
                    if (level.getBlockEntity(pos) instanceof DronePortControllerBlockEntity controller) {
                        controller.getEnergyStorage().receiveEnergy(1000000, false);
                        controller.assemble();
                        controller.setChanged(); // Critical for saving NBT
                        if (context.getPlayer() != null) {
                            context.getPlayer().displayClientMessage(net.minecraft.network.chat.Component.literal("Drone Port Deployed & Charged!").withStyle(net.minecraft.ChatFormatting.GREEN), true);
                        }
                    }
                } else {
                    level.setBlock(pos, ModBlocks.DRONE_PORT_MEMBER.get().defaultBlockState(), 3);
                    if (level.getBlockEntity(pos) instanceof com.example.ghostlib.block.entity.DronePortMemberBlockEntity member) {
                        member.setControllerPos(center);
                    }
                }
            }
        }

        context.getItemInHand().shrink(1);
        return InteractionResult.CONSUME;
    }
}
