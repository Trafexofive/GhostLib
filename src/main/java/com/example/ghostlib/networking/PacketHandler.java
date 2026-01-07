package com.example.ghostlib.networking;

import com.example.ghostlib.GhostLib;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class PacketHandler {
    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(GhostLib.MODID).versioned("1.0");
        // Temporarily disabled to allow build to succeed.
        // registrar.playToServer(C2SPlaceTiledGhosts.TYPE, C2SPlaceTiledGhosts.STREAM_CODEC, C2SPlaceTiledGhosts::handle);
    }
}