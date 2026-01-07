package com.example.ghostlib.history;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;
import com.example.ghostlib.block.entity.GhostBlockEntity;
import com.example.ghostlib.registry.ModBlocks;

// Command for placing one or more ghost blocks
public record PlaceGhostsCommand(java.util.Map<BlockPos, BlockState> placements) implements ICommand {
    
    @Override
    public void execute(Level level) {
        placements.forEach((pos, targetState) -> {
            level.setBlock(pos, ModBlocks.GHOST_BLOCK.get().defaultBlockState(), 3);
            if (level.getBlockEntity(pos) instanceof GhostBlockEntity gbe) {
                gbe.setTargetState(targetState);
            }
        });
    }

    @Override
    public void undo(Level level) {
        placements.keySet().forEach(pos -> level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3));
    }
}
