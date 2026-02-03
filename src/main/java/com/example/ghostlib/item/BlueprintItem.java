package com.example.ghostlib.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

public class BlueprintItem extends Item {

    public BlueprintItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents,
            TooltipFlag tooltipFlag) {
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);

        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) {
            tooltipComponents
                    .add(Component.literal("Empty Blueprint").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            tooltipComponents
                    .add(Component.literal("Use Ctrl+C to copy a selection").withStyle(ChatFormatting.DARK_GRAY));
            return;
        }

        CompoundTag tag = customData.copyTag();
        if (!tag.contains("Pattern", Tag.TAG_LIST)) {
            tooltipComponents
                    .add(Component.literal("Empty Blueprint").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            return;
        }

        ListTag pattern = tag.getList("Pattern", Tag.TAG_COMPOUND);
        int blockCount = pattern.size();

        if (blockCount == 0) {
            tooltipComponents
                    .add(Component.literal("Empty Blueprint").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            return;
        }

        // Calculate dimensions
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        Map<String, Integer> blockCounts = new HashMap<>();

        for (int i = 0; i < pattern.size(); i++) {
            CompoundTag entry = pattern.getCompound(i);
            int x = entry.getInt("x");
            int y = entry.getInt("y");
            int z = entry.getInt("z");

            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);

            // Count block types
            if (entry.contains("state")) {
                String state = entry.getString("state");
                // Extract block name from state string (e.g., "Block{minecraft:stone}" ->
                // "stone")
                String blockName = state;
                if (state.contains("{") && state.contains(":")) {
                    int start = state.indexOf(":") + 1;
                    int end = state.indexOf("}");
                    if (end > start) {
                        blockName = state.substring(start, end);
                        // Remove properties if present
                        if (blockName.contains("[")) {
                            blockName = blockName.substring(0, blockName.indexOf("["));
                        }
                    }
                }
                blockCounts.merge(blockName, 1, Integer::sum);
            }
        }

        int width = maxX - minX + 1;
        int height = maxY - minY + 1;
        int depth = maxZ - minZ + 1;

        // Header
        tooltipComponents.add(Component.literal("Blueprint").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));

        // Dimensions
        tooltipComponents.add(Component.literal("Size: ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(width + "×" + height + "×" + depth)
                        .withStyle(ChatFormatting.WHITE)));

        // Block count
        tooltipComponents.add(Component.literal("Blocks: ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.valueOf(blockCount))
                        .withStyle(ChatFormatting.YELLOW)));

        // Show top 5 block types if advanced tooltip (F3+H)
        if (tooltipFlag.isAdvanced() && !blockCounts.isEmpty()) {
            tooltipComponents.add(Component.literal(""));
            tooltipComponents.add(Component.literal("Block Types:").withStyle(ChatFormatting.GRAY));

            blockCounts.entrySet().stream()
                    .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                    .limit(5)
                    .forEach(entry -> {
                        tooltipComponents.add(Component.literal("  " + entry.getKey() + ": ")
                                .withStyle(ChatFormatting.DARK_GRAY)
                                .append(Component.literal(String.valueOf(entry.getValue()))
                                        .withStyle(ChatFormatting.WHITE)));
                    });

            if (blockCounts.size() > 5) {
                tooltipComponents.add(Component.literal("  +" + (blockCounts.size() - 5) + " more...")
                        .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
            }
        }

        // Usage hint
        tooltipComponents.add(Component.literal(""));
        tooltipComponents.add(
                Component.literal("Right-click to load").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        // Make blueprints with patterns shimmer
        return hasPattern(stack);
    }

    @Override
    public int getMaxStackSize(ItemStack stack) {
        return hasPattern(stack) ? 1 : 64;
    }

    private boolean hasPattern(ItemStack stack) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData != null) {
            CompoundTag tag = customData.copyTag();
            if (tag.contains("Pattern", Tag.TAG_LIST)) {
                return tag.getList("Pattern", Tag.TAG_COMPOUND).size() > 0;
            }
        }
        return false;
    }
}
