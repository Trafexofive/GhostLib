package com.example.ghostlib.multiblock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Defines a pattern for multiblock structures.
 * Uses relative coordinates from the controller.
 */
public record MultiblockPattern(Map<BlockPos, Block> pattern) {
    
    public boolean matches(Level level, BlockPos controllerPos, Direction facing) {
        for (Map.Entry<BlockPos, Block> entry : pattern.entrySet()) {
            BlockPos worldPos = controllerPos.offset(rotate(entry.getKey(), facing));
            if (!level.getBlockState(worldPos).is(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    public List<BlockPos> getMemberPositions(BlockPos controllerPos, Direction facing) {
        List<BlockPos> positions = new ArrayList<>();
        for (BlockPos relPos : pattern.keySet()) {
            if (relPos.equals(BlockPos.ZERO)) continue;
            positions.add(controllerPos.offset(rotate(relPos, facing)));
        }
        return positions;
    }

    private BlockPos rotate(BlockPos pos, Direction facing) {
        return switch (facing) {
            case NORTH -> pos;
            case SOUTH -> new BlockPos(-pos.getX(), pos.getY(), -pos.getZ());
            case WEST -> new BlockPos(pos.getZ(), pos.getY(), -pos.getX());
            case EAST -> new BlockPos(-pos.getZ(), pos.getY(), pos.getX());
            default -> pos;
        };
    }
}
