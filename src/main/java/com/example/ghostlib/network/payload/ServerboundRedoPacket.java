package com.example.ghostlib.network.payload;

import com.example.ghostlib.GhostLib;
import com.example.ghostlib.history.GhostHistoryManager;
import com.example.ghostlib.history.WorldHistoryManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ServerboundRedoPacket() implements CustomPacketPayload {
    public static final Type<ServerboundRedoPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(GhostLib.MODID, "redo_ghosts"));

    public static final StreamCodec<FriendlyByteBuf, ServerboundRedoPacket> STREAM_CODEC = StreamCodec.unit(new ServerboundRedoPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ServerboundRedoPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            com.example.ghostlib.util.GhostLogger.logistics("REDO packet received from " + context.player().getName().getString());
            if (context.player() instanceof ServerPlayer player) {
                com.example.ghostlib.util.GhostLogger.logistics("REDO: Player is ServerPlayer, calling redo()");
                com.example.ghostlib.util.GhostLogger.logistics("REDO: redoStack size = " +
                    WorldHistoryManager.get(player.level()).getRedoStack().size());
                GhostHistoryManager.redo(player);
                com.example.ghostlib.util.GhostLogger.logistics("REDO: Complete");
            } else {
                com.example.ghostlib.util.GhostLogger.logistics("REDO: Player is NOT ServerPlayer");
            }
        });
    }
}
