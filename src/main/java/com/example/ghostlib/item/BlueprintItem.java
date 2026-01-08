package com.example.ghostlib.item;

import com.example.ghostlib.registry.ModBlocks;
import com.example.ghostlib.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public class BlueprintItem extends GhostPlacerItem {
    
    public enum BlueprintType {
        ELECTRIC_FURNACE
    }

    private final BlueprintType type;

    public BlueprintItem(BlueprintType type, Properties properties) {
        super(properties);
        this.type = type;
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (!level.isClientSide && !hasPattern(stack)) {
            setupPattern(stack);
        }
    }

    private boolean hasPattern(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().contains("Pattern");
    }

    private void setupPattern(ItemStack stack) {
        CompoundTag tag = new CompoundTag();
        ListTag patternList = new ListTag();

        if (type == BlueprintType.ELECTRIC_FURNACE) {
            // Define 3x3 Iron Platform
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    // All Iron Blocks except center is Controller
                    BlockState state = (x == 0 && z == 0) 
                        ? ModBlocks.ELECTRIC_FURNACE_CONTROLLER.get().defaultBlockState() 
                        : Blocks.IRON_BLOCK.defaultBlockState();
                    
                    CompoundTag blockTag = new CompoundTag();
                    blockTag.put("Rel", NbtUtils.writeBlockPos(new BlockPos(x + 1, 0, z + 1)));
                    blockTag.put("State", NbtUtils.writeBlockState(state));
                    patternList.add(blockTag);
                }
            }
            tag.put("Pattern", patternList);
            tag.putInt("SizeX", 3);
            tag.putInt("SizeY", 1);
            tag.putInt("SizeZ", 3);
        }

        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }
}
