package com.example.ghostlib.block.entity;

import com.example.ghostlib.block.LogisticalChestBlock;
import com.example.ghostlib.registry.ModBlockEntities;
import com.example.ghostlib.util.LogisticsNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;

import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

public class LogisticalChestBlockEntity extends BlockEntity implements MenuProvider {
    private final ItemStackHandler inventory = new ItemStackHandler(27) {
        @Override
        protected void onContentsChanged(int slot) { setChanged(); }
    };

    public LogisticalChestBlockEntity(BlockPos pos, BlockState state) {
        super(com.example.ghostlib.registry.ModBlockEntities.LOGISTICAL_CHEST.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            LogisticsNetworkManager.get(level).joinNetwork(worldPosition, 1);
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide) {
            LogisticsNetworkManager.get(level).leaveNetwork(worldPosition);
        }
        super.setRemoved();
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Logistical Chest (" + getChestType().getSerializedName() + ")");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new com.example.ghostlib.menu.LogisticalChestMenu(containerId, inventory, this);
    }

    public LogisticalChestBlock.ChestType getChestType() {
        return getBlockState().getValue(LogisticalChestBlock.TYPE);
    }

    public ItemStackHandler getInventory() { return inventory; }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Inventory", inventory.serializeNBT(registries));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Inventory")) inventory.deserializeNBT(registries, tag.getCompound("Inventory"));
    }
}
