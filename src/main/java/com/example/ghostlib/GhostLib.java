package com.example.ghostlib;

import com.example.ghostlib.network.PacketHandler;
import com.example.ghostlib.registry.ModBlockEntities;
import com.example.ghostlib.registry.ModBlocks;
import com.example.ghostlib.registry.ModCreativeTabs;
import com.example.ghostlib.registry.ModEntities;
import com.example.ghostlib.registry.ModItems;
import com.example.ghostlib.registry.ModMenus;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(GhostLib.MODID)
public class GhostLib {
    public static final String MODID = "ghostlib";
    public static final Logger LOGGER = LogUtils.getLogger();

    public GhostLib(IEventBus modEventBus) {
        com.example.ghostlib.util.GhostLogger.init();
        LOGGER.info("GhostLib: Initializing Construction Swarm...");
        com.example.ghostlib.config.GhostLibConfig.load();

        ModBlocks.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModItems.register(modEventBus);
        ModMenus.register(modEventBus);
        com.example.ghostlib.registry.ModSounds.register(modEventBus);
        ModCreativeTabs.register(modEventBus);
        ModEntities.register(modEventBus);
        com.example.ghostlib.registry.ModAttributes.register(modEventBus);

        modEventBus.addListener(this::registerCapabilities);
    }

    private void registerCapabilities(net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
            net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
            com.example.ghostlib.registry.ModBlockEntities.MATERIAL_STORAGE.get(),
            (be, side) -> be.getInventory()
        );
        event.registerBlockEntity(
            net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
            com.example.ghostlib.registry.ModBlockEntities.LOGISTICAL_CHEST.get(),
            (be, side) -> be.getInventory()
        );
        event.registerBlockEntity(
            net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
            com.example.ghostlib.registry.ModBlockEntities.DRONE_PORT.get(),
            (be, side) -> be.getInventory()
        );
        event.registerBlockEntity(
            net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,
            com.example.ghostlib.registry.ModBlockEntities.DRONE_PORT.get(),
            (be, side) -> null // Energy not yet implemented
        );
    }
}