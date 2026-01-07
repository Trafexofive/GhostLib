package com.example.ghostlib.networking;

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

public record C2SPlaceTiledGhosts(BlockPos start, BlockPos end) implements CustomPacketPayload {
    public static final Type<C2SPlaceTiledGhosts> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(GhostLib.MODID, "place_tiled_ghosts"));
    
    public static final StreamCodec<FriendlyByteBuf, C2SPlaceTiledGhosts> STREAM_CODEC = StreamCodec.of(
        (buf, msg) -> {
            buf.writeBlockPos(msg.start());
            buf.writeBlockPos(msg.end());
        },
        C2SPlaceTiledGhosts::new
    );

    public C2SPlaceTiledGhosts(FriendlyByteBuf buf) {
        this(buf.readBlockPos(), buf.readBlockPos());
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(C2SPlaceTiledGhosts msg, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            if (player == null) return;
            ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
            if (stack.getItem() instanceof GhostPlacerItem placer) {
                // This method does not exist on the stable GhostPlacerItem, commenting out.
                // placer.serverPlaceTiled(player.serverLevel(), player, stack, msg.start(), msg.end());
            }
        });
    }
}