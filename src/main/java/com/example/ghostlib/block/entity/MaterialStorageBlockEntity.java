package com.example.ghostlib.block.entity;

import com.example.ghostlib.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;

public class MaterialStorageBlockEntity extends BlockEntity {
    private final ItemStackHandler inventory = new ItemStackHandler(27);
    private BlockPos controllerPos = null;

    public MaterialStorageBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MATERIAL_STORAGE.get(), pos, state);
    }

    public ItemStackHandler getInventory() { return inventory; }
    public void setControllerPos(BlockPos pos) { this.controllerPos = pos; setChanged(); }
    public java.util.Optional<BlockPos> getControllerPos() { return java.util.Optional.ofNullable(controllerPos); }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("inventory", inventory.serializeNBT(registries));
        if (controllerPos != null) tag.putLong("controllerPos", controllerPos.asLong());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        inventory.deserializeNBT(registries, tag.getCompound("inventory"));
        if (tag.contains("controllerPos")) controllerPos = BlockPos.of(tag.getLong("controllerPos"));
    }
}
