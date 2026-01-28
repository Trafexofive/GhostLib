package com.example.ghostlib.registry;

import com.example.ghostlib.GhostLib;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(BuiltInRegistries.CREATIVE_MODE_TAB, GhostLib.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> GHOSTLIB_TAB = CREATIVE_MODE_TABS.register("ghostlib_tab",
            () -> CreativeModeTab.builder()
                    .title(Component.literal("GhostLib"))
                    .icon(() -> new ItemStack(ModItems.DRONE_SPAWN_EGG.get()))
                    .displayItems((params, output) -> {
                        output.accept(ModItems.DRONE_SPAWN_EGG.get());
                        output.accept(ModItems.MATERIAL_STORAGE.get());
                        output.accept(ModItems.BLUEPRINT.get());
                    })
                    .build());

    public static void register(IEventBus eventBus) {
        CREATIVE_MODE_TABS.register(eventBus);
    }
}
