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

public record S2CSyncDeconstructionPacket(Map<BlockPos, Integer> activeJobs) implements CustomPacketPayload {
    public static final Type<S2CSyncDeconstructionPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(GhostLib.MODID, "sync_deconstruction"));

    public static final StreamCodec<FriendlyByteBuf, S2CSyncDeconstructionPacket> STREAM_CODEC = StreamCodec.ofMember(
        (packet, buf) -> {
            buf.writeInt(packet.activeJobs.size());
            for (Map.Entry<BlockPos, Integer> entry : packet.activeJobs.entrySet()) {
                buf.writeBlockPos(entry.getKey());
                buf.writeInt(entry.getValue());
            }
        },
        buf -> {
            int size = buf.readInt();
            Map<BlockPos, Integer> map = new HashMap<>();
            for (int i = 0; i < size; i++) {
                map.put(buf.readBlockPos(), buf.readInt());
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
            Map<Long, Map<BlockPos, BlockState>> clientJobs = manager.getDirectDeconstructJobs();
            
            // 1. Remove jobs not in packet
            clientJobs.values().forEach(map -> map.keySet().removeIf(pos -> !packet.activeJobs.containsKey(pos)));
            clientJobs.values().removeIf(Map::isEmpty);

            // 2. Add/Update jobs from packet
            for (Map.Entry<BlockPos, Integer> entry : packet.activeJobs().entrySet()) {
                BlockState targetState = Block.stateById(entry.getValue());
                manager.registerDirectDeconstruct(entry.getKey(), targetState, level);
            }
        });
    }
}
