package com.example.ghostlib.menu;

import com.example.ghostlib.block.entity.LogisticalChestBlockEntity;
import com.example.ghostlib.registry.ModBlocks;
import com.example.ghostlib.registry.ModMenus;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

public class LogisticalChestMenu extends AbstractContainerMenu {
    private final LogisticalChestBlockEntity blockEntity;

    public LogisticalChestMenu(int containerId, Inventory inv, FriendlyByteBuf extraData) {
        this(containerId, inv, (LogisticalChestBlockEntity) inv.player.level().getBlockEntity(extraData.readBlockPos()));
    }

    public LogisticalChestMenu(int containerId, Inventory inv, LogisticalChestBlockEntity entity) {
        super(ModMenus.LOGISTICAL_CHEST_MENU.get(), containerId);
        this.blockEntity = entity;

        ItemStackHandler handler = entity.getInventory();

        // 3x9 Grid
        for (int i = 0; i < 3; ++i) {
            for (int j = 0; j < 9; ++j) {
                this.addSlot(new SlotItemHandler(handler, j + i * 9, 8 + j * 18, 18 + i * 18));
            }
        }

        // Player Inventory
        for (int i = 0; i < 3; ++i) {
            for (int j = 0; j < 9; ++j) {
                this.addSlot(new Slot(inv, j + i * 9 + 9, 8 + j * 18, 84 + i * 18));
            }
        }

        // Hotbar
        for (int k = 0; k < 9; ++k) {
            this.addSlot(new Slot(inv, k, 8 + k * 18, 142));
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack itemstack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack itemstack1 = slot.getItem();
            itemstack = itemstack1.copy();
            if (index < 27) { // Chest Slots
                if (!this.moveItemStackTo(itemstack1, 27, 63, true)) {
                    return ItemStack.EMPTY;
                }
            } else { // Player Inventory
                if (!this.moveItemStackTo(itemstack1, 0, 27, false)) {
                    return ItemStack.EMPTY;
                }
            }

            if (itemstack1.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }

            if (itemstack1.getCount() == itemstack.getCount()) {
                return ItemStack.EMPTY;
            }

            slot.onTake(player, itemstack1);
        }

        return itemstack;
    }

    @Override
    public boolean stillValid(Player player) {
        return ContainerLevelAccess.create(blockEntity.getLevel(), blockEntity.getBlockPos())
                .evaluate((level, pos) -> level.getBlockState(pos).is(ModBlocks.LOGISTICAL_CHEST.get()), true);
    }
}
