package com.example.ghostlib.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

import java.util.List;

public class DronePortItem extends BlockItem {
    public DronePortItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        
        tooltip.add(Component.literal("Central hub for Drone Swarms").withStyle(ChatFormatting.BLUE));
        tooltip.add(Component.literal("Max Drones: 64").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Active Limit: 16").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Consumption: 1,000 FE/t").withStyle(ChatFormatting.YELLOW));
        tooltip.add(Component.literal("Range: 64 blocks").withStyle(ChatFormatting.DARK_GRAY));
    }
}
