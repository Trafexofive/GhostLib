package com.example.ghostlib.network;

import com.example.ghostlib.GhostLib;
import com.example.ghostlib.network.payload.ServerboundPlaceGhostsPacket;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = GhostLib.MODID, bus = EventBusSubscriber.Bus.MOD)
public class PacketHandler {
    public static final ResourceLocation CHANNEL_ID = ResourceLocation.fromNamespaceAndPath(GhostLib.MODID, "main");

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(GhostLib.MODID).versioned("1.0.0");

        registrar.playToServer(
            ServerboundPlaceGhostsPacket.TYPE,
            ServerboundPlaceGhostsPacket.STREAM_CODEC,
            ServerboundPlaceGhostsPacket::handle
        );

        registrar.playToServer(
            com.example.ghostlib.network.payload.ServerboundUndoPacket.TYPE,
            com.example.ghostlib.network.payload.ServerboundUndoPacket.STREAM_CODEC,
            com.example.ghostlib.network.payload.ServerboundUndoPacket::handle
        );

        registrar.playToServer(
            com.example.ghostlib.network.payload.ServerboundRedoPacket.TYPE,
            com.example.ghostlib.network.payload.ServerboundRedoPacket.STREAM_CODEC,
            com.example.ghostlib.network.payload.ServerboundRedoPacket::handle
        );

        registrar.playToClient(
            com.example.ghostlib.network.payload.S2CSyncDeconstructionPacket.TYPE,
            com.example.ghostlib.network.payload.S2CSyncDeconstructionPacket.STREAM_CODEC,
            com.example.ghostlib.network.payload.S2CSyncDeconstructionPacket::handle
        );
    }
}
