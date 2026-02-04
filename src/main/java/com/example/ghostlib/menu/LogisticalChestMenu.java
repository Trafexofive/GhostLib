package com.example.ghostlib.menu;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIContainerMenu;
import com.lowdragmc.lowdraglib2.gui.factory.IContainerUIHolder;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;

public class LogisticalChestMenu extends ModularUIContainerMenu {

    public LogisticalChestMenu(MenuType<?> type, int containerId, Inventory playerInventory, IContainerUIHolder uiHolder) {
        super((MenuType<ModularUIContainerMenu>)type, containerId, playerInventory, uiHolder);
    }
}