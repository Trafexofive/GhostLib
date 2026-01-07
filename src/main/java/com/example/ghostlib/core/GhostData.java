package com.example.ghostlib.core;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

/**
 * A simple data class representing the state of a ghost block.
 * This will be expanded later to include blueprint info, ownership, etc.
 */
public class GhostData {
    private BlockState targetState;
    private UUID owner;
    private UUID assignedTo;
    
    // Additional states for rendering and logic
    private boolean isIncoming; 
    
    public GhostData(BlockState targetState, UUID owner) {
        this.targetState = targetState;
        this.owner = owner;
    }
    
    // Getters and Setters will go here
}
