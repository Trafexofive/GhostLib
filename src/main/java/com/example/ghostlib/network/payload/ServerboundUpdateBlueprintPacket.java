package com.example.ghostlib.network.payload;

import com.example.ghostlib.GhostLib;
import com.example.ghostlib.item.BlueprintItem;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ServerboundUpdateBlueprintPacket(CompoundTag tag) implements CustomPacketPayload {
    public static final Type<ServerboundUpdateBlueprintPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(GhostLib.MODID, "update_blueprint"));

    public static final StreamCodec<FriendlyByteBuf, ServerboundUpdateBlueprintPacket> STREAM_CODEC = StreamCodec.ofMember(
        (packet, buf) -> buf.writeNbt(packet.tag),
        buf -> new ServerboundUpdateBlueprintPacket(buf.readNbt())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ServerboundUpdateBlueprintPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                ItemStack stack = player.getMainHandItem();
                if (!(stack.getItem() instanceof BlueprintItem)) {
                    stack = player.getOffhandItem();
                }
                
                if (stack.getItem() instanceof BlueprintItem) {
                    stack.set(DataComponents.CUSTOM_DATA, CustomData.of(packet.tag()));
                }
            }
        });
    }
}
