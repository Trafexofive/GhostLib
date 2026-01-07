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

public class GhostBlockEntity extends BlockEntity {

    public enum GhostState {
        UNASSIGNED(0),      // Deep Blue
        ASSIGNED(1),        // Light Blue
        FETCHING(2),        // Dark Blue (En route to player)
        INCOMING(3),        // Yellow (En route to build)
        TO_REMOVE(4),       // Red
        MISSING_ITEMS(5),   // Deep Blue (Error)
        REMOVING(6);        // Red (Assigned)

        public final int id;
        GhostState(int id) { this.id = id; }
        public static GhostState fromId(int id) {
            for (GhostState state : values()) if (state.id == id) return state;
            return UNASSIGNED;
        }

        public boolean isQueueState() {
            return this == UNASSIGNED || this == TO_REMOVE || this == MISSING_ITEMS;
        }
    }

    private BlockState targetState = Blocks.AIR.defaultBlockState();
    private BlockState capturedState = Blocks.AIR.defaultBlockState(); 
    @Nullable
    private UUID assignedTo;
    private GhostState currentState = GhostState.UNASSIGNED;

    public GhostBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.GHOST_BLOCK_ENTITY.get(), pos, blockState);
    }

    public BlockState getTargetState() { return this.targetState; }
    public BlockState getCapturedState() { return this.capturedState; }
    public GhostState getCurrentState() { return this.currentState; }
    public UUID getAssignedTo() { return this.assignedTo; }

    public void setTargetState(BlockState state) {
        this.targetState = state;
        sync();
    }

    public void setCapturedState(BlockState state) {
        this.capturedState = state;
        sync();
    }

    public void setState(GhostState state) {
        this.currentState = state;
        if (level != null && !level.isClientSide) {
            // Only update the Job Manager if this is a state that affects the job queues
            GhostJobManager.get(level).registerJob(getBlockPos(), state, targetState);
        }
        sync();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            GhostJobManager.get(level).registerJob(getBlockPos(), this.currentState, targetState);
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
            if (level != null && !level.isClientSide) {
                // registerJob(state) with active state will remove it from search queues
                // but crucially removeFromAllMaps(pos, false) will KEEP the assignment record.
                GhostJobManager.get(level).registerJob(getBlockPos(), this.currentState, targetState);
            }
        } else {
            // Unassigning - return to the appropriate queue state
            this.currentState = (this.currentState == GhostState.REMOVING) ? GhostState.TO_REMOVE : GhostState.UNASSIGNED;
            if (level != null && !level.isClientSide) {
                GhostJobManager.get(level).registerJob(getBlockPos(), this.currentState, targetState);
            }
        }
        sync();
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide) {
            GhostJobManager.get(level).removeJob(getBlockPos());
        }
        super.setRemoved();
    }

    private void sync() {
        if (level != null && !level.isClientSide) {
            setChanged();
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("target_state", NbtUtils.writeBlockState(this.targetState));
        tag.put("captured_state", NbtUtils.writeBlockState(this.capturedState));
        if (assignedTo != null) tag.putUUID("assigned_to", assignedTo);
        tag.putInt("current_state", this.currentState.id);
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.targetState = NbtUtils.readBlockState(registries.lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK), tag.getCompound("target_state"));
        if (tag.contains("captured_state")) this.capturedState = NbtUtils.readBlockState(registries.lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK), tag.getCompound("captured_state"));
        this.assignedTo = tag.hasUUID("assigned_to") ? tag.getUUID("assigned_to") : null;
        this.currentState = GhostState.fromId(tag.getInt("current_state"));
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveWithoutMetadata(registries); }
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}