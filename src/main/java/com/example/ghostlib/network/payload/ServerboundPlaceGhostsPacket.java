package com.example.ghostlib.network.payload;

import com.example.ghostlib.GhostLib;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Optional;

public record ServerboundPlaceGhostsPacket(BlockPos start, BlockPos end, int placementMode, int spacingX, int spacingZ, Optional<CompoundTag> pattern) implements CustomPacketPayload {
    public static final Type<ServerboundPlaceGhostsPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(GhostLib.MODID, "place_ghosts"));

    public static final StreamCodec<FriendlyByteBuf, ServerboundPlaceGhostsPacket> STREAM_CODEC = StreamCodec.ofMember(
        (packet, buf) -> {
            buf.writeBlockPos(packet.start);
            buf.writeBlockPos(packet.end);
            buf.writeInt(packet.placementMode);
            buf.writeInt(packet.spacingX);
            buf.writeInt(packet.spacingZ);
            buf.writeOptional(packet.pattern, (b, t) -> b.writeNbt(t));
        },
        buf -> new ServerboundPlaceGhostsPacket(buf.readBlockPos(), buf.readBlockPos(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readOptional(b -> b.readNbt()))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ServerboundPlaceGhostsPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                // 1. Explicit Pattern provided (Paste Mode)
                if (packet.pattern().isPresent()) {
                    com.example.ghostlib.logic.GhostActionHandler.handlePlacement(player.serverLevel(), player, packet.start(), packet.end(), packet.placementMode(), packet.spacingX(), packet.spacingZ(), packet.pattern().get());
                    return;
                }

                // 2. Mode 2 (Full Force/Deconstruct/Clear)
                if (packet.placementMode() == 2) {
                    com.example.ghostlib.logic.GhostActionHandler.executeDeconstruction(player.serverLevel(), player, packet.start(), packet.end());
                    return;
                }
            }
        });
    }
}
