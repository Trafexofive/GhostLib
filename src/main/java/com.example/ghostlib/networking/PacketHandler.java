package com.example.ghostlib.networking;

import com.example.ghostlib.GhostLib;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlerEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class PacketHandler {
    public static final ResourceLocation CHANNEL_NAME = ResourceLocation.fromNamespaceAndPath(GhostLib.MODID, "main_channel");
    private static PayloadRegistrar REGISTRAR; // Store the registrar

    public static void register(RegisterPayloadHandlerEvent event) {
        REGISTRAR = event.registrar(CHANNEL_NAME);
        
        // Correct way to register a payload with its handler
        REGISTRAR.play(C2SRequestRepeatPaste.TYPE, C2SRequestRepeatPaste.STREAM_CODEC)
                 .server(new C2SRequestRepeatPaste.Handler());
    }

    // Static method to send payload to server from client
    public static void sendToServer(CustomPacketPayload message) {
        if (Minecraft.getInstance().getConnection() != null) {
            Minecraft.getInstance().getConnection().send(message);
        }
    }

    // Packet definitions
    public record C2SRequestRepeatPaste(boolean isControlDown) implements CustomPacketPayload {
        public static final Type<C2SRequestRepeatPaste> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(GhostLib.MODID, "request_repeat_paste"));
        public static final StreamCodec<FriendlyByteBuf, C2SRequestRepeatPaste> STREAM_CODEC = StreamCodec.of(
            (val, buf) -> buf.writeBoolean(val.isControlDown()), // Encoder
            buf -> new C2SRequestRepeatPaste(buf.readBoolean())  // Decoder
        );

        public C2SRequestRepeatPaste(FriendlyByteBuf buf) {
            this(buf.readBoolean());
        }

        public static void encode(C2SRequestRepeatPaste msg, FriendlyByteBuf buf) {
            buf.writeBoolean(msg.isControlDown());
        }

        public static C2SRequestRepeatPaste decode(FriendlyByteBuf buf) {
            return new C2SRequestRepeatPaste(buf.readBoolean());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static class Handler implements IPayloadHandler<C2SRequestRepeatPaste> {
            @Override
            public void handle(C2SRequestRepeatPaste payload, IPayloadContext context) {
                context.enqueueWork(() -> {
                    ServerPlayer sender = (ServerPlayer) context.player();
                    if (sender != null) {
                        if (sender.getItemInHand(InteractionHand.MAIN_HAND).getItem() instanceof com.example.ghostlib.item.GhostPlacerItem placerItem) {
                            placerItem.tryRepeatPaste(sender.serverLevel(), sender, sender.getItemInHand(InteractionHand.MAIN_HAND), payload.isControlDown());
                        }
                    }
                });
            }
        }
    }
}
