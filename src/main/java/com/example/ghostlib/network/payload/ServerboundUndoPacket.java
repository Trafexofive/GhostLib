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

public record ServerboundUndoPacket() implements CustomPacketPayload {
    public static final Type<ServerboundUndoPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(GhostLib.MODID, "undo_ghosts"));

    public static final StreamCodec<FriendlyByteBuf, ServerboundUndoPacket> STREAM_CODEC = StreamCodec.unit(new ServerboundUndoPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ServerboundUndoPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            com.example.ghostlib.util.GhostLogger.logistics("UNDO packet received from " + context.player().getName().getString());
            if (context.player() instanceof ServerPlayer player) {
                com.example.ghostlib.util.GhostLogger.logistics("UNDO: Player is ServerPlayer, calling undo()");
                com.example.ghostlib.util.GhostLogger.logistics("UNDO: undoStack size = " +
                    WorldHistoryManager.get(player.level()).getUndoStack().size());
                GhostHistoryManager.undo(player);
                com.example.ghostlib.util.GhostLogger.logistics("UNDO: Complete");
            } else {
                com.example.ghostlib.util.GhostLogger.logistics("UNDO: Player is NOT ServerPlayer");
            }
        });
    }
}
