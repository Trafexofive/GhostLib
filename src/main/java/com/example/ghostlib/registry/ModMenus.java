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

    public static final DeferredHolder<MenuType<?>, MenuType<com.example.ghostlib.menu.DronePortMenu>> DRONE_PORT_MENU = MENUS.register("drone_port",
            () -> net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(com.example.ghostlib.menu.DronePortMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<com.example.ghostlib.menu.LogisticalChestMenu>> LOGISTICAL_CHEST_MENU = MENUS.register("logistical_chest",
            () -> net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(com.example.ghostlib.menu.LogisticalChestMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<com.example.ghostlib.menu.TestMenu>> TEST_MENU = MENUS.register("test_menu",
            () -> net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(com.example.ghostlib.menu.TestMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<com.example.ghostlib.menu.AssemblerTestMenu>> ASSEMBLER_TEST_MENU = MENUS.register("assembler_test_menu",
            () -> net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(com.example.ghostlib.menu.AssemblerTestMenu::new));

    public static void register(IEventBus eventBus) {
        MENUS.register(eventBus);
    }
}
