package com.example.ghostlib.registry;

import com.example.ghostlib.GhostLib;
import com.example.ghostlib.menu.DronePortMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, GhostLib.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<com.lowdragmc.lowdraglib2.gui.holder.ModularUIContainerMenu>> DRONE_PORT_MENU = MENUS.register("drone_port",
            () -> com.lowdragmc.lowdraglib2.gui.factory.LDMenuTypes.BLOCK_UI.get());

    public static final DeferredHolder<MenuType<?>, MenuType<com.lowdragmc.lowdraglib2.gui.holder.ModularUIContainerMenu>> LOGISTICAL_CHEST_MENU = MENUS.register("logistical_chest",
            () -> com.lowdragmc.lowdraglib2.gui.factory.LDMenuTypes.BLOCK_UI.get());

    public static void register(IEventBus eventBus) {
        MENUS.register(eventBus);
    }
}
