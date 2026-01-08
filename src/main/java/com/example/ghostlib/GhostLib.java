package com.example.ghostlib;

import com.example.ghostlib.network.PacketHandler;
import com.example.ghostlib.registry.ModBlockEntities;
import com.example.ghostlib.registry.ModBlocks;
import com.example.ghostlib.registry.ModCreativeTabs;
import com.example.ghostlib.registry.ModEntities;
import com.example.ghostlib.registry.ModItems;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(GhostLib.MODID)
public class GhostLib {
    public static final String MODID = "ghostlib";
    public static final Logger LOGGER = LogUtils.getLogger();

    public GhostLib(IEventBus modEventBus) {
        LOGGER.info("GhostLib: Initializing Construcion Swarm...");
        com.example.ghostlib.config.GhostLibConfig.load();

        ModBlocks.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModItems.register(modEventBus);
        com.example.ghostlib.registry.ModSounds.register(modEventBus);
        ModCreativeTabs.register(modEventBus);
        ModEntities.register(modEventBus);

        modEventBus.addListener(this::registerCapabilities);
    }

    private void registerCapabilities(net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent event) {
        // Register Controller Capabilities
        event.registerBlockEntity(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, ModBlockEntities.DRONE_PORT_CONTROLLER.get(), (be, side) -> be.getDroneStorage());
        event.registerBlockEntity(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK, ModBlockEntities.DRONE_PORT_CONTROLLER.get(), (be, side) -> be.getEnergyStorage());

        // Register Member Proxies (The "Pipe" Logic)
        event.registerBlockEntity(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, ModBlockEntities.DRONE_PORT_MEMBER.get(), (be, side) -> {
            var controllerPos = be.getControllerPos();
            if (controllerPos.isPresent()) {
                if (be.getLevel().getBlockEntity(controllerPos.get()) instanceof com.example.ghostlib.block.entity.DronePortControllerBlockEntity controller) {
                    return controller.getDroneStorage();
                }
            }
            return null;
        });

        event.registerBlockEntity(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK, ModBlockEntities.DRONE_PORT_MEMBER.get(), (be, side) -> {
            var controllerPos = be.getControllerPos();
            if (controllerPos.isPresent()) {
                if (be.getLevel().getBlockEntity(controllerPos.get()) instanceof com.example.ghostlib.block.entity.DronePortControllerBlockEntity controller) {
                    return controller.getEnergyStorage();
                }
            }
            return null;
        });

        event.registerBlockEntity(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, ModBlockEntities.MATERIAL_STORAGE.get(), (be, side) -> be.getInventory());
    }
}