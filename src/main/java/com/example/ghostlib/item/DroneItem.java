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
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);

        net.minecraft.world.item.component.CustomData customData = stack.get(DataComponents.ENTITY_DATA);
        if (customData != null) {
            CompoundTag tag = customData.copyTag();
            
            // Energy
            int energy = tag.getInt("Energy");
            // Try to find max energy in attributes list if present
            double maxEnergy = 10000;
            if (tag.contains("Attributes", Tag.TAG_LIST)) {
                ListTag attributes = tag.getList("Attributes", Tag.TAG_COMPOUND);
                for (int i = 0; i < attributes.size(); i++) {
                    CompoundTag attr = attributes.getCompound(i);
                    if (attr.getString("Name").equals("ghostlib:max_energy")) {
                        maxEnergy = attr.getDouble("Base");
                        break;
                    }
                }
            }

            tooltip.add(Component.literal("Energy: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(energy + " / " + (int)maxEnergy + " FE")
                            .withStyle(energy > maxEnergy * 0.2 ? ChatFormatting.GREEN : ChatFormatting.RED)));

            // Attributes
            if (flag.isAdvanced()) {
                tooltip.add(Component.literal("Drone Stats:").withStyle(ChatFormatting.DARK_PURPLE));
                if (tag.contains("Attributes", Tag.TAG_LIST)) {
                    ListTag attributes = tag.getList("Attributes", Tag.TAG_COMPOUND);
                    for (int i = 0; i < attributes.size(); i++) {
                        CompoundTag attr = attributes.getCompound(i);
                        String name = attr.getString("Name");
                        if (name.startsWith("ghostlib:")) {
                            String shortName = name.replace("ghostlib:", "").replace("_", " ");
                            double val = attr.getDouble("Base");
                            tooltip.add(Component.literal("  " + capitalize(shortName) + ": ").withStyle(ChatFormatting.GRAY)
                                    .append(Component.literal(String.format("%.1f", val)).withStyle(ChatFormatting.WHITE)));
                        }
                    }
                }
            } else {
                tooltip.add(Component.literal("Hold [Shift] for stats").withStyle(ChatFormatting.DARK_GRAY));
            }
        } else {
            tooltip.add(Component.literal("New Factory Unit").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
            tooltip.add(Component.literal("Energy: 10000 / 10000 FE").withStyle(ChatFormatting.GRAY));
        }
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
}
