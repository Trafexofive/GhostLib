package com.example.ghostlib.logic;

import com.example.ghostlib.block.GhostBlock;
import com.example.ghostlib.block.entity.GhostBlockEntity;
import com.example.ghostlib.history.BlockSnapshot;
import com.example.ghostlib.history.GhostHistoryManager;
import com.example.ghostlib.history.WorldHistoryManager;
import com.example.ghostlib.registry.ModBlocks;
import com.example.ghostlib.util.GhostJobManager;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GhostActionHandler {

    public enum ActionType {
        PLACE, DECONSTRUCT
    }

    public static void handlePlacement(ServerLevel level, ServerPlayer player, BlockPos start, BlockPos end,
            int placementMode, int spacingX, int spacingZ, CompoundTag patternTag) {
        if (patternTag == null || !patternTag.contains("Pattern"))
            return;

        ListTag patternList = patternTag.getList("Pattern", 10);
        List<BlockPos> placementOrigins = new ArrayList<>();

        int bpSizeX = patternTag.contains("SizeX") ? Math.max(1, patternTag.getInt("SizeX")) : 0;
        int bpSizeZ = patternTag.contains("SizeZ") ? Math.max(1, patternTag.getInt("SizeZ")) : 0;

        if (bpSizeX <= 1 || bpSizeZ <= 1) {
            int minX = 0, minZ = 0;
            int maxX = 0, maxZ = 0;
            boolean hasRel = false;
            for (int i = 0; i < patternList.size(); i++) {
                CompoundTag blockTag = patternList.getCompound(i);
                if (blockTag.contains("Rel")) {
                    BlockPos rel = NbtUtils.readBlockPos(blockTag, "Rel").orElse(BlockPos.ZERO);
                    minX = Math.min(minX, rel.getX());
                    minZ = Math.min(minZ, rel.getZ());
                    maxX = Math.max(maxX, rel.getX());
                    maxZ = Math.max(maxZ, rel.getZ());
                    hasRel = true;
                }
            }
            if (hasRel) {
                if (bpSizeX <= 1) bpSizeX = Math.max(1, maxX - minX + 1);
                if (bpSizeZ <= 1) bpSizeZ = Math.max(1, maxZ - minZ + 1);
            }
        }

        int stepX = bpSizeX + spacingX;
        int stepZ = bpSizeZ + spacingZ;

        boolean isGrid = (placementMode & 1) != 0;
        boolean isForce = (placementMode & 4) != 0;

        if (isGrid) {
            int xDir = end.getX() >= start.getX() ? 1 : -1;
            int zDir = end.getZ() >= start.getZ() ? 1 : -1;
            int xRange = Math.abs(end.getX() - start.getX());
            int zRange = Math.abs(end.getZ() - start.getZ());
            for (int x = 0; x <= xRange; x += Math.max(1, stepX)) {
                for (int z = 0; z <= zRange; z += Math.max(1, stepZ)) {
                    placementOrigins.add(start.offset(x * xDir, 0, z * zDir));
                }
            }
        } else {
            int dx = end.getX() - start.getX();
            int dz = end.getZ() - start.getZ();
            if (Math.abs(dx) >= Math.abs(dz)) {
                int steps = Math.abs(dx) / Math.max(1, stepX);
                int dir = dx >= 0 ? 1 : -1;
                for (int i = 0; i <= steps; i++) placementOrigins.add(start.offset(i * stepX * dir, 0, 0));
            } else {
                int steps = Math.abs(dz) / Math.max(1, stepZ);
                int dir = dz >= 0 ? 1 : -1;
                for (int i = 0; i <= steps; i++) placementOrigins.add(start.offset(0, 0, i * stepZ * dir));
            }
        }

        Map<BlockPos, BlockSnapshot> blueprintChanges = new HashMap<>();
        for (BlockPos origin : placementOrigins) {
            for (int i = 0; i < patternList.size(); i++) {
                CompoundTag blockTag = patternList.getCompound(i);
                BlockPos rel = NbtUtils.readBlockPos(blockTag, "Rel").orElse(BlockPos.ZERO);
                BlockState bpState = NbtUtils.readBlockState(level.holderLookup(net.minecraft.core.registries.Registries.BLOCK), blockTag.getCompound("State"));
                BlockPos target = origin.offset(rel).immutable();
                CompoundTag capturedNbt = blockTag.contains("Data") ? blockTag.getCompound("Data") : null;

                if (bpState != null && !bpState.isAir()) {
                    blueprintChanges.put(target, new BlockSnapshot(bpState, capturedNbt));
                }
            }
        }
        WorldHistoryManager.get(level).pushAction(new WorldHistoryManager.HistoryAction("Blueprint Placement", blueprintChanges), level);
    }

    public static void executeDeconstruction(ServerLevel level, ServerPlayer player, BlockPos start, BlockPos end) {
        BlockPos min = new BlockPos(Math.min(start.getX(), end.getX()), Math.min(start.getY(), end.getY()), Math.min(start.getZ(), end.getZ()));
        BlockPos max = new BlockPos(Math.max(start.getX(), end.getX()), Math.max(start.getY(), end.getY()), Math.max(start.getZ(), end.getZ()));

        Map<BlockPos, BlockSnapshot> changes = new HashMap<>();
        for (BlockPos p : BlockPos.betweenClosed(min, max)) {
            changes.put(p.immutable(), BlockSnapshot.AIR);
        }
        WorldHistoryManager.get(level).pushAction(new WorldHistoryManager.HistoryAction("Deconstruction Area", changes), level);
    }
}