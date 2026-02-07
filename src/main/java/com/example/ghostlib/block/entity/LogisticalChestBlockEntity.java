package com.example.ghostlib.block.entity;

import com.example.ghostlib.block.LogisticalChestBlock;
import com.example.ghostlib.util.LogisticsNetworkManager;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.inventory.InventorySlots;
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
import net.minecraft.world.inventory.MenuType;

public class LogisticalChestBlockEntity extends BlockEntity implements net.minecraft.world.MenuProvider, com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType.BlockUI, com.lowdragmc.lowdraglib2.gui.factory.IContainerUIHolder {
    private final ItemStackHandler inventory = new ItemStackHandler(27) {
        @Override
        protected void onContentsChanged(int slot) { setChanged(); }
    };

    public LogisticalChestBlockEntity(BlockPos pos, BlockState state) {
        super(com.example.ghostlib.registry.ModBlockEntities.LOGISTICAL_CHEST.get(), pos, state);
    }

    @Override
    public net.minecraft.world.inventory.AbstractContainerMenu createMenu(int windowId, net.minecraft.world.entity.player.Inventory inventory, net.minecraft.world.entity.player.Player player) {
        return new com.example.ghostlib.menu.LogisticalChestMenu((net.minecraft.world.inventory.MenuType)com.example.ghostlib.registry.ModMenus.LOGISTICAL_CHEST_MENU.get(), windowId, inventory, this);
    }

    @Override
    public ModularUI createUI(Player player) {
        UI ui = UI.empty();
        ui.getRootElement().addChild(new Label().setValue(getDisplayName()).layout(l -> l.marginTop(5).marginLeft(5)));
        
        // 3x9 Inventory
        for (int i = 0; i < 3; i++) {
            final int row = i;
            for (int j = 0; j < 9; j++) {
                final int col = j;
                int index = i * 9 + j;
                ui.getRootElement().addChild(new ItemSlot().bind(inventory, index).layout(l -> l.left(8f + col * 18f).top(17f + row * 18f)));
            }
        }
        
        ui.getRootElement().addChild(new InventorySlots().layout(l -> l.bottom(5).left(8)));
        return ModularUI.of(ui, player);
    }

    @Override
    public ModularUI createUI(com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType.BlockUIHolder holder) {
        return createUI(holder.player);
    }

    @Override
    public boolean isStillValid(Player player) {
        return !isRemoved();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            LogisticsNetworkManager.get(level).joinOrCreateNetwork(worldPosition, level);
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