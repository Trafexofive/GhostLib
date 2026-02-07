package com.example.ghostlib.menu;

import com.lowdragmc.lowdraglib2.gui.factory.IContainerUIHolder;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIContainerMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

public class AssemblerTestMenu extends ModularUIContainerMenu {

    public AssemblerTestMenu(int containerId, Inventory playerInventory, IContainerUIHolder uiHolder) {
        super((MenuType)com.example.ghostlib.registry.ModMenus.ASSEMBLER_TEST_MENU.get(), containerId, playerInventory, uiHolder);
    }

    public AssemblerTestMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buf) {
        super((MenuType)com.example.ghostlib.registry.ModMenus.ASSEMBLER_TEST_MENU.get(), containerId, playerInventory, getHolder(playerInventory, buf));
    }

    private static IContainerUIHolder getHolder(Inventory inventory, RegistryFriendlyByteBuf buf) {
        boolean isMainHand = buf.readBoolean();
        ItemStack stack = isMainHand ? inventory.player.getMainHandItem() : inventory.player.getOffhandItem();
        if (stack.getItem() instanceof IContainerUIHolder holder) {
            return holder;
        }
        
        // Fallback to other hand
        stack = isMainHand ? inventory.player.getOffhandItem() : inventory.player.getMainHandItem();
        if (stack.getItem() instanceof IContainerUIHolder holder) {
            return holder;
        }

        // Final fallback: the singleton itself
        return (IContainerUIHolder) com.example.ghostlib.registry.ModItems.ASSEMBLER_TEST_ITEM.get();
    }
}
