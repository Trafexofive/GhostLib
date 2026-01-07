package com.example.ghostlib;

import com.example.ghostlib.networking.PacketHandler;
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
        LOGGER.info("GhostLib Initializing...");
        
        ModBlocks.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModItems.register(modEventBus);
        ModCreativeTabs.register(modEventBus);
        ModEntities.register(modEventBus);

        modEventBus.addListener(PacketHandler::register);
    }
}