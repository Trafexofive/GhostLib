package com.example.ghostlib.client;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIContainerMenu;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class ModularUIScreenProxy {
    @SuppressWarnings("unchecked")
    public static <M extends ModularUIContainerMenu, U extends Screen & MenuAccess<M>> MenuScreens.ScreenConstructor<M, U> create() {
        return (menu, inventory, title) -> {
            ModularUI ui = menu.getModularUI();
            return (U) new WrappedModularUIScreen<>(ui, title, menu);
        };
    }

    public static class WrappedModularUIScreen<M extends ModularUIContainerMenu> extends ModularUIScreen implements MenuAccess<M> {
        private final M menu;

        public WrappedModularUIScreen(ModularUI ui, Component title, M menu) {
            super(ui, title);
            this.menu = menu;
        }

        @Override
        public M getMenu() {
            return menu;
        }
    }
}
