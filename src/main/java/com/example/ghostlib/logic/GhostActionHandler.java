package com.example.ghostlib.logic;

import com.example.ghostlib.block.GhostBlock;
import com.example.ghostlib.block.entity.GhostBlockEntity;
import com.example.ghostlib.history.GhostHistoryManager;
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
import java.util.List;

public class GhostActionHandler {

    public enum ActionType {
        PLACE, DECONSTRUCT
    }

    public static void handlePlacement(ServerLevel level, ServerPlayer player, BlockPos start, BlockPos end, int placementMode, CompoundTag patternTag) {
        if (patternTag == null || !patternTag.contains("Pattern")) return;
        
        ListTag patternList = patternTag.getList("Pattern", 10);
        List<GhostHistoryManager.StateChange> changes = new ArrayList<>();
        List<BlockPos> placementOrigins = new ArrayList<>();
        
        int sizeX = Math.max(1, patternTag.getInt("SizeX"));
        int sizeZ = Math.max(1, patternTag.getInt("SizeZ"));

        boolean isGrid = (placementMode & 1) != 0;
        boolean isForce = (placementMode & 4) != 0;

        // 1. Calculate Origins (Tiling)
        if (isGrid) { // Area Mode
             int xDir = end.getX() >= start.getX() ? 1 : -1;
             int zDir = end.getZ() >= start.getZ() ? 1 : -1;
             int xRange = Math.abs(end.getX() - start.getX());
             int zRange = Math.abs(end.getZ() - start.getZ());
             for (int x = 0; x <= xRange; x += sizeX) {
                 for (int z = 0; z <= zRange; z += sizeZ) {
                     placementOrigins.add(start.offset(x * xDir, 0, z * zDir));
                 }
             }
        } else { // Line Mode
             int dx = end.getX() - start.getX();
             int dz = end.getZ() - start.getZ();
             if (Math.abs(dx) >= Math.abs(dz)) {
                 int steps = Math.abs(dx) / sizeX;
                 int dir = dx >= 0 ? 1 : -1;
                 for (int i=0; i<=steps; i++) placementOrigins.add(start.offset(i * sizeX * dir, 0, 0));
             } else {
                 int steps = Math.abs(dz) / sizeZ;
                 int dir = dz >= 0 ? 1 : -1;
                 for (int i=0; i<=steps; i++) placementOrigins.add(start.offset(0, 0, i * sizeZ * dir));
             }
        }

        // 2. Execute Placement
        for (BlockPos origin : placementOrigins) {
            for (int i = 0; i < patternList.size(); i++) {
                CompoundTag blockTag = patternList.getCompound(i);
                BlockPos rel = NbtUtils.readBlockPos(blockTag, "Rel").orElse(BlockPos.ZERO);
                BlockState bpState = NbtUtils.readBlockState(level.holderLookup(net.minecraft.core.registries.Registries.BLOCK), blockTag.getCompound("State"));
                
                BlockPos target = origin.offset(rel);
                BlockState worldState = level.getBlockState(target);

                if (bpState != null && !bpState.isAir() && !worldState.equals(bpState)) {
                    // Logic:
                    // If Force: Place Ghost (Overriding whatever is there).
                    // If Safe (Default): 
                    //    If Air or Ghost -> Place Ghost.
                    //    If Solid -> Deconstruct.
                    
                    if (isForce) {
                        // Force Place: Sets Ghost Block immediately.
                        // If there was a block, we capture it in the Ghost Block so it renders Red (TO_REMOVE).
                        changes.add(new GhostHistoryManager.StateChange(target.immutable(), worldState, bpState)); // History tracks change
                        
                        level.setBlock(target, ModBlocks.GHOST_BLOCK.get().defaultBlockState(), 3);
                        if (level.getBlockEntity(target) instanceof GhostBlockEntity gbe) {
                            gbe.setTargetState(bpState);
                            // If worldState wasn't Air/Ghost, we mark it for removal
                            if (!worldState.isAir() && !(worldState.getBlock() instanceof GhostBlock)) {
                                gbe.setCapturedState(worldState); // Visual
                                gbe.setState(GhostBlockEntity.GhostState.TO_REMOVE); // Logic
                            } else {
                                gbe.setState(GhostBlockEntity.GhostState.UNASSIGNED);
                            }
                        }
                    } else {
                        // Safe Mode
                        if (!worldState.isAir() && !(worldState.getBlock() instanceof GhostBlock)) {
                            changes.add(new GhostHistoryManager.StateChange(target.immutable(), worldState, bpState));
                            GhostJobManager.get(level).registerDirectDeconstruct(target, bpState, level);
                        } else {
                            if (worldState.getBlock() == ModBlocks.GHOST_BLOCK.get()) {
                                if (level.getBlockEntity(target) instanceof GhostBlockEntity gbe && gbe.getTargetState().equals(bpState)) continue; 
                            }
                            changes.add(new GhostHistoryManager.StateChange(target.immutable(), worldState, bpState));
                            level.setBlock(target, ModBlocks.GHOST_BLOCK.get().defaultBlockState(), 3);
                            if (level.getBlockEntity(target) instanceof GhostBlockEntity gbe) {
                                gbe.setTargetState(bpState);
                                gbe.setState(GhostBlockEntity.GhostState.UNASSIGNED);
                            }
                        }
                    }
                }
            }
        }
        GhostHistoryManager.recordAction(player, changes);
    }

    public static void executeDeconstruction(ServerLevel level, ServerPlayer player, BlockPos start, BlockPos end) {
         BlockPos min = new BlockPos(Math.min(start.getX(), end.getX()), Math.min(start.getY(), end.getY()), Math.min(start.getZ(), end.getZ()));
         BlockPos max = new BlockPos(Math.max(start.getX(), end.getX()), Math.max(start.getY(), end.getY()), Math.max(start.getZ(), end.getZ()));
         
         List<GhostHistoryManager.StateChange> changes = new ArrayList<>();
         
         for (BlockPos p : BlockPos.betweenClosed(min, max)) {
             BlockState worldState = level.getBlockState(p);
             if (!worldState.isAir()) {
                 changes.add(new GhostHistoryManager.StateChange(p.immutable(), worldState, Blocks.AIR.defaultBlockState()));
                 GhostJobManager.get(level).registerDirectDeconstruct(p, Blocks.AIR.defaultBlockState(), level);
             }
         }
         GhostHistoryManager.recordAction(player, changes);
    }
}
