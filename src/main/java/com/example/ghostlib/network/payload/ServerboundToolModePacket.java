package com.example.ghostlib.network.payload;

import com.example.ghostlib.GhostLib;
import com.example.ghostlib.item.GhostPlacerItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ServerboundToolModePacket(int mode) implements CustomPacketPayload {
    public static final Type<ServerboundToolModePacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(GhostLib.MODID, "tool_mode"));

    public static final StreamCodec<FriendlyByteBuf, ServerboundToolModePacket> STREAM_CODEC = StreamCodec.ofMember(
        (packet, buf) -> buf.writeInt(packet.mode),
        buf -> new ServerboundToolModePacket(buf.readInt())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ServerboundToolModePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
                if (stack.getItem() instanceof GhostPlacerItem placer) {
                    placer.setMode(stack, packet.mode);
                    player.displayClientMessage(net.minecraft.network.chat.Component.literal("Tool Mode: " + GhostPlacerItem.ToolMode.values()[packet.mode].name()), true);
                }
            }
        });
    }
}
