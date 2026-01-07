package com.example.ghostlib.network.payload;

import com.example.ghostlib.GhostLib;
import com.example.ghostlib.util.GhostJobManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Block;

import java.util.HashMap;
import java.util.Map;

public record S2CSyncDeconstructionPacket(Map<BlockPos, Boolean> activeJobs) implements CustomPacketPayload {
    public static final Type<S2CSyncDeconstructionPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(GhostLib.MODID, "sync_deconstruction"));

    public static final StreamCodec<FriendlyByteBuf, S2CSyncDeconstructionPacket> STREAM_CODEC = StreamCodec.ofMember(
        (packet, buf) -> {
            buf.writeInt(packet.activeJobs.size());
            for (Map.Entry<BlockPos, Boolean> entry : packet.activeJobs.entrySet()) {
                buf.writeBlockPos(entry.getKey());
                buf.writeBoolean(entry.getValue());
            }
        },
        buf -> {
            int size = buf.readInt();
            Map<BlockPos, Boolean> map = new HashMap<>();
            for (int i = 0; i < size; i++) {
                map.put(buf.readBlockPos(), buf.readBoolean());
            }
            return new S2CSyncDeconstructionPacket(map);
        }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(S2CSyncDeconstructionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            net.minecraft.world.level.Level level = context.player().level();
            GhostJobManager manager = GhostJobManager.get(level);
            // Clear current and repopulate with dummy target states just for client rendering
            manager.getDirectDeconstructJobs().clear();
            for (Map.Entry<BlockPos, Boolean> entry : packet.activeJobs().entrySet()) {
                manager.registerDirectDeconstruct(entry.getKey(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), level);
            }
        });
    }
}
