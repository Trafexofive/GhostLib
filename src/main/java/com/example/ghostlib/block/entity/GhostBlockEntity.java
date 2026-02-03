package com.example.ghostlib.block.entity;

import com.example.ghostlib.registry.ModBlockEntities;
import com.example.ghostlib.util.GhostJobManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Represents the intelligence behind a Ghost Block marker.
 * This BlockEntity tracks the construction lifecycle, target state, and drone assignment.
 */
public class GhostBlockEntity extends BlockEntity {

    /**
     * Defines the visual and logical lifecycle of a Ghost block.
     */
    public enum GhostState {
        /** Default state. Job is in the construction queue, waiting for a drone. (Deep Blue) */
        UNASSIGNED(0),      
        /** A drone has claimed the job and is currently pathfinding. (Light Blue) */
        ASSIGNED(1),        
        /** Drone is currently at the player retrieving required items. (Dark Blue) */
        FETCHING(2),        
        /** Drone has the items and is flying to the build site. (Yellow) */
        INCOMING(3),        
        /** Position is marked for deconstruction/removal. (Red Wireframe) */
        TO_REMOVE(4),       
        /** Job failed (e.g., items missing). The job is hibernating. (Purple) */
        MISSING_ITEMS(5),   
        /** A drone is actively breaking the block at this position. (Red Wireframe) */
        REMOVING(6);        

        public final int id;
        GhostState(int id) { this.id = id; }
        public static GhostState fromId(int id) {
            for (GhostState state : values()) if (state.id == id) return state;
            return UNASSIGNED;
        }

        /**
         * Returns true if this state belongs in a searchable job queue.
         */
        public boolean isQueueState() {
            return this == UNASSIGNED || this == TO_REMOVE || this == MISSING_ITEMS;
        }
    }

    private BlockState targetState = Blocks.AIR.defaultBlockState();
    private BlockState capturedState = Blocks.AIR.defaultBlockState();
    private CompoundTag capturedNbt = null;
    private GhostState currentState = GhostState.UNASSIGNED;
    private UUID assignedTo = null;

    public GhostBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.GHOST_BLOCK_ENTITY.get(), pos, state);
    }

    public void setCapturedNbt(CompoundTag nbt) {
        this.capturedNbt = nbt;
        setChanged();
    }

    public CompoundTag getCapturedNbt() {
        return capturedNbt;
    }


    public BlockState getTargetState() { return this.targetState; }
    public BlockState getCapturedState() { return this.capturedState; }
    public GhostState getCurrentState() { return this.currentState; }
    public UUID getAssignedTo() { return this.assignedTo; }

    /**
     * Updates the target block to be built and syncs to clients.
     */
    public void setTargetState(BlockState state) {
        this.targetState = state;
        sync();
    }

    /**
     * Updates the captured original block (for Red Wireframe rendering) and syncs.
     */
    public void setCapturedState(BlockState state) {
        this.capturedState = state;
        sync();
    }

    /**
     * Changes the current lifecycle state and updates the Job Manager queues.
     */
    public void setState(GhostState state) {
        this.currentState = state;
        if (level != null && !level.isClientSide) {
            // Only register if we have a target or are deconstructing
            if (!targetState.isAir() || currentState == GhostState.TO_REMOVE || currentState == GhostState.REMOVING) {
                GhostJobManager.get(level).registerJob(getBlockPos(), state, targetState);
            }
        }
        sync();
    }

    /**
     * Called when the BlockEntity is loaded into the world.
     * Ensures the job is registered in the JobManager's volatile memory.
     */
    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            if (!targetState.isAir() || currentState == GhostState.TO_REMOVE || currentState == GhostState.REMOVING) {
                GhostJobManager.get(level).registerJob(getBlockPos(), this.currentState, targetState);
            }
        }
    }

    public void setAssignedTo(@Nullable UUID assignedTo) {
        this.assignedTo = assignedTo;
        if (assignedTo != null) {
            // Transition from Queue -> Active
            if (this.currentState == GhostState.TO_REMOVE) {
                this.currentState = GhostState.REMOVING;
            } else {
                this.currentState = GhostState.ASSIGNED;
            }
        } else {
            // Unassigning - return to the appropriate queue state
            if (this.currentState == GhostState.REMOVING) {
                this.currentState = GhostState.TO_REMOVE;
            } else if (this.currentState != GhostState.MISSING_ITEMS) {
                this.currentState = GhostState.UNASSIGNED;
            }
        }
        
        if (level != null && !level.isClientSide) {
            if (!targetState.isAir() || currentState == GhostState.TO_REMOVE || currentState == GhostState.REMOVING) {
                GhostJobManager.get(level).registerJob(getBlockPos(), this.currentState, targetState);
            }
        }
        sync();
    }

    /**
     * Ensures the job is removed from the manager when the block is broken.
     */
    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide) {
            GhostJobManager.get(level).removeJob(getBlockPos());
        }
        super.setRemoved();
    }

    /**
     * Triggers a block update packet to synchronize data to nearby clients.
     */
    private void sync() {
        if (level != null && !level.isClientSide) {
            setChanged();
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("target", net.minecraft.nbt.NbtUtils.writeBlockState(targetState));
        tag.put("captured", net.minecraft.nbt.NbtUtils.writeBlockState(capturedState));
        if (capturedNbt != null) {
            tag.put("capturedNbt", capturedNbt);
        }
        tag.putString("state", currentState.name());
        if (assignedTo != null) {
            tag.putUUID("assigned", assignedTo);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("target")) {
            targetState = net.minecraft.nbt.NbtUtils.readBlockState(registries.lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK), tag.getCompound("target"));
        }
        if (tag.contains("captured")) {
            capturedState = net.minecraft.nbt.NbtUtils.readBlockState(registries.lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK), tag.getCompound("captured"));
        }
        if (tag.contains("capturedNbt")) {
            capturedNbt = tag.getCompound("capturedNbt");
        }
        if (tag.contains("state")) {
            currentState = GhostState.valueOf(tag.getString("state"));
        }
        if (tag.contains("assigned")) {
            assignedTo = tag.getUUID("assigned");
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveWithoutMetadata(registries); }
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }

    @Override
    public void onDataPacket(net.minecraft.network.Connection net, ClientboundBlockEntityDataPacket pkt, HolderLookup.Provider lookupProvider) {
        if (pkt.getTag() != null) loadAdditional(pkt.getTag(), lookupProvider);
    }
}
