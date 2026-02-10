package com.example.ghostlib.item;

import com.example.ghostlib.registry.ModAttributes;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;

import java.util.List;
import java.util.function.Supplier;

public class DroneItem extends DeferredSpawnEggItem {
    public DroneItem(Supplier<? extends EntityType<? extends Mob>> type, int backgroundColor, int highlightColor, Properties properties) {
        super(type, backgroundColor, highlightColor, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);

        tooltip.add(Component.literal("Construction Unit").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));

        net.minecraft.world.item.component.CustomData customData = stack.get(DataComponents.ENTITY_DATA);
        
        int energy = 10000;
        double maxEnergy = 10000;
        double workSpeed = 1.0;
        double interactRange = 4.5;
        double searchRange = 64.0;
        double efficiency = 1.0;

        if (customData != null) {
            CompoundTag tag = customData.copyTag();
            energy = tag.contains("Energy") ? tag.getInt("Energy") : 10000;

            if (tag.contains("Attributes", Tag.TAG_LIST)) {
                ListTag attributes = tag.getList("Attributes", Tag.TAG_COMPOUND);
                for (int i = 0; i < attributes.size(); i++) {
                    CompoundTag attr = attributes.getCompound(i);
                    String name = attr.getString("Name");
                    double val = attr.getDouble("Base");
                    
                    if (name.equals("ghostlib:max_energy")) maxEnergy = val;
                    else if (name.equals("ghostlib:work_speed")) workSpeed = val;
                    else if (name.equals("ghostlib:interaction_range")) interactRange = val;
                    else if (name.equals("ghostlib:search_range")) searchRange = val;
                    else if (name.equals("ghostlib:energy_efficiency")) efficiency = val;
                }
            }
        }

        // Energy Bar Style
        tooltip.add(Component.literal("Energy: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(energy + " / " + (int)maxEnergy + " FE")
                        .withStyle(energy > maxEnergy * 0.2 ? ChatFormatting.GREEN : ChatFormatting.RED)));

        // Stats - Always visible now as requested
        tooltip.add(Component.literal("Drone Stats:").withStyle(ChatFormatting.DARK_PURPLE));
        tooltip.add(Component.literal("  Work Speed: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.format("%.1f", workSpeed)).withStyle(ChatFormatting.WHITE)));
        tooltip.add(Component.literal("  Interact Range: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.format("%.1f", interactRange)).withStyle(ChatFormatting.WHITE)));
        tooltip.add(Component.literal("  Search Range: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.format("%.0f", searchRange)).withStyle(ChatFormatting.WHITE)));
        tooltip.add(Component.literal("  Efficiency: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.format("%.1f", efficiency) + "x").withStyle(ChatFormatting.WHITE)));

        if (customData != null && !isInventoryEmpty(customData.copyTag())) {
            tooltip.add(Component.literal("Contains Items").withStyle(ChatFormatting.YELLOW, ChatFormatting.ITALIC));
        }
    }

    private boolean isInventoryEmpty(CompoundTag tag) {
        if (!tag.contains("Inventory", Tag.TAG_LIST)) return true;
        return tag.getList("Inventory", Tag.TAG_COMPOUND).isEmpty();
    }
}