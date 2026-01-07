package com.example.ghostlib.network.payload;

import com.example.ghostlib.GhostLib;
import com.example.ghostlib.item.GhostPlacerItem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ServerboundPlaceGhostsPacket(BlockPos start, BlockPos end, int placementMode) implements CustomPacketPayload {
    public static final Type<ServerboundPlaceGhostsPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(GhostLib.MODID, "place_ghosts"));

    // Modes: 0 = Normal, 1 = Semi-Force (Ctrl), 2 = Full-Force (Ctrl+Shift)
    public static final StreamCodec<FriendlyByteBuf, ServerboundPlaceGhostsPacket> STREAM_CODEC = StreamCodec.ofMember(
        (packet, buf) -> {
            buf.writeBlockPos(packet.start);
            buf.writeBlockPos(packet.end);
            buf.writeInt(packet.placementMode);
        },
        buf -> new ServerboundPlaceGhostsPacket(buf.readBlockPos(), buf.readBlockPos(), buf.readInt())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ServerboundPlaceGhostsPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
                if (stack.getItem() instanceof GhostPlacerItem placer) {
                    placer.handlePlacementPacket(player.serverLevel(), player, stack, packet.start(), packet.end(), packet.placementMode());
                }
            }
        });
    }
}
