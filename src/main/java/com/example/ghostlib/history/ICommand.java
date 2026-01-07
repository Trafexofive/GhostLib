package com.example.ghostlib.history;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;

// A record to represent a reversible action
public interface ICommand {
    void execute(Level level);
    void undo(Level level);
}
