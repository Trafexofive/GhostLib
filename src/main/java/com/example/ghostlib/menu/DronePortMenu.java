package com.example.ghostlib.menu;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIContainerMenu;
import com.lowdragmc.lowdraglib2.gui.factory.IContainerUIHolder;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;

public class DronePortMenu extends ModularUIContainerMenu {

    public DronePortMenu(MenuType<?> type, int containerId, Inventory playerInventory, IContainerUIHolder uiHolder) {
        super((MenuType)type, containerId, playerInventory, uiHolder);
    }

    public DronePortMenu(int containerId, Inventory playerInventory, net.minecraft.network.RegistryFriendlyByteBuf buf) {
        super((MenuType)com.example.ghostlib.registry.ModMenus.DRONE_PORT_MENU.get(), containerId, playerInventory, getHolder(playerInventory, buf));
    }

    private static IContainerUIHolder getHolder(Inventory playerInventory, net.minecraft.network.RegistryFriendlyByteBuf buf) {
        net.minecraft.core.BlockPos pos = buf.readBlockPos();
        com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType.BLOCK_STATE_STREAM_CODEC.decode(buf);
        net.minecraft.world.level.block.entity.BlockEntity be = playerInventory.player.level().getBlockEntity(pos);
        if (be instanceof IContainerUIHolder holder) {
            return holder;
        }
        // Fallback to prevent crash, returning a dummy UI holder with matching slot count (9 machine + 36 player)
        return new IContainerUIHolder() {
            @Override
            public com.lowdragmc.lowdraglib2.gui.ui.ModularUI createUI(net.minecraft.world.entity.player.Player player) {
                com.lowdragmc.lowdraglib2.gui.ui.UI ui = com.lowdragmc.lowdraglib2.gui.ui.UI.empty();
                // Add 9 dummy slots for the machine
                for (int i = 0; i < 9; i++) {
                    ui.getRootElement().addChild(new com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot().bind(new net.neoforged.neoforge.items.ItemStackHandler(9), i));
                }
                // Add player inventory (36 slots)
                ui.getRootElement().addChild(new com.lowdragmc.lowdraglib2.gui.ui.elements.inventory.InventorySlots());
                return com.lowdragmc.lowdraglib2.gui.ui.ModularUI.of(ui, player);
            }
            @Override
            public boolean isStillValid(net.minecraft.world.entity.player.Player player) {
                return false; // Close as soon as possible
            }
        };
    }
}
