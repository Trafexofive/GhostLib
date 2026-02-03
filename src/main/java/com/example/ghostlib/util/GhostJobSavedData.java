package com.example.ghostlib.util;

import com.example.ghostlib.GhostLib;
import com.example.ghostlib.block.entity.GhostBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Persistent storage for GhostJobManager state.
 * Ensures jobs survive server restarts.
 */
public class GhostJobSavedData extends SavedData {
    private static final String DATA_NAME = "ghostlib_jobs";

    private final GhostJobManager manager;

    public GhostJobSavedData(GhostJobManager manager) {
        this.manager = manager;
    }

    /**
     * Load saved job data from NBT.
     */
    public static GhostJobSavedData load(CompoundTag tag, HolderLookup.Provider registries, GhostJobManager manager) {
        GhostJobSavedData data = new GhostJobSavedData(manager);

        // Load construction jobs
        if (tag.contains("construction_jobs", Tag.TAG_LIST)) {
            ListTag list = tag.getList("construction_jobs", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag jobTag = list.getCompound(i);
                BlockPos pos = NbtUtils.readBlockPos(jobTag, "pos").orElse(BlockPos.ZERO);
                BlockState state = NbtUtils.readBlockState(
                        registries.lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK),
                        jobTag.getCompound("state"));
                manager.registerJob(pos, GhostBlockEntity.GhostState.UNASSIGNED, state);
            }
        }

        // Load ghost removal jobs
        if (tag.contains("ghost_removal_jobs", Tag.TAG_LIST)) {
            ListTag list = tag.getList("ghost_removal_jobs", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag jobTag = list.getCompound(i);
                BlockPos pos = NbtUtils.readBlockPos(jobTag, "pos").orElse(BlockPos.ZERO);
                manager.registerJob(pos, GhostBlockEntity.GhostState.TO_REMOVE, null);
            }
        }

        // Load direct deconstruct jobs
        if (tag.contains("direct_deconstruct_jobs", Tag.TAG_LIST)) {
            ListTag list = tag.getList("direct_deconstruct_jobs", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag jobTag = list.getCompound(i);
                BlockPos pos = NbtUtils.readBlockPos(jobTag, "pos").orElse(BlockPos.ZERO);
                BlockState targetAfter = NbtUtils.readBlockState(
                        registries.lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK),
                        jobTag.getCompound("target_after"));
                manager.registerDirectDeconstruct(pos, targetAfter, null);
            }
        }

        // Load final states (chained jobs)
        if (tag.contains("job_final_states", Tag.TAG_LIST)) {
            ListTag list = tag.getList("job_final_states", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag stateTag = list.getCompound(i);
                BlockPos pos = NbtUtils.readBlockPos(stateTag, "pos").orElse(BlockPos.ZERO);
                BlockState state = NbtUtils.readBlockState(
                        registries.lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK),
                        stateTag.getCompound("state"));
                // Update existing deconstruction job with its final state
                if (manager.isDeconstructAt(pos)) {
                    BlockState targetAfter = manager.getTargetAfterDeconstruct(pos);
                    manager.registerDirectDeconstruct(pos, targetAfter, state, null);
                }
            }
        }

        // Load hibernating jobs
        if (tag.contains("hibernating_jobs", Tag.TAG_LIST)) {
            ListTag list = tag.getList("hibernating_jobs", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag jobTag = list.getCompound(i);
                BlockPos pos = NbtUtils.readBlockPos(jobTag, "pos").orElse(BlockPos.ZERO);
                BlockState state = NbtUtils.readBlockState(
                        registries.lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK),
                        jobTag.getCompound("state"));
                manager.registerJob(pos, GhostBlockEntity.GhostState.MISSING_ITEMS, state);
            }
        }

        // Load assignments
        if (tag.contains("assignments", Tag.TAG_LIST)) {
            ListTag list = tag.getList("assignments", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag assignTag = list.getCompound(i);
                BlockPos pos = NbtUtils.readBlockPos(assignTag, "pos").orElse(BlockPos.ZERO);
                UUID droneId = assignTag.getUUID("drone_id");
                manager.restoreAssignment(pos, droneId);
            }
        }

        GhostLib.LOGGER.info("Loaded {} construction jobs, {} deconstruct jobs, {} hibernating jobs from SavedData",
                manager.getConstructionJobs().size(),
                manager.getDirectDeconstructJobs().size(),
                manager.getHibernatingJobs().size());

        return data;
    }

    /**
     * Save job data to NBT.
     */
    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        // Save construction jobs - flatten structure
        ListTag constructionList = new ListTag();
        for (Map<BlockPos, BlockState> chunkMap : manager.getConstructionJobsMap().values()) { // Need raw access or
                                                                                               // safe iteration
            for (Map.Entry<BlockPos, BlockState> entry : chunkMap.entrySet()) {
                CompoundTag jobTag = new CompoundTag();
                jobTag.put("pos", NbtUtils.writeBlockPos(entry.getKey()));
                jobTag.put("state", NbtUtils.writeBlockState(entry.getValue()));
                constructionList.add(jobTag);
            }
        }
        tag.put("construction_jobs", constructionList);

        // Save ghost removal jobs
        ListTag removalList = new ListTag();
        for (Set<BlockPos> chunkSet : manager.getGhostRemovalJobsMap().values()) {
            synchronized (chunkSet) {
                for (BlockPos pos : chunkSet) {
                    CompoundTag jobTag = new CompoundTag();
                    jobTag.put("pos", NbtUtils.writeBlockPos(pos));
                    removalList.add(jobTag);
                }
            }
        }
        tag.put("ghost_removal_jobs", removalList);

        // Save direct deconstruct jobs
        ListTag deconstructList = new ListTag();
        for (Map<BlockPos, BlockState> chunkMap : manager.getDirectDeconstructJobs().values()) {
            for (Map.Entry<BlockPos, BlockState> entry : chunkMap.entrySet()) {
                CompoundTag jobTag = new CompoundTag();
                jobTag.put("pos", NbtUtils.writeBlockPos(entry.getKey()));
                jobTag.put("target_after", NbtUtils.writeBlockState(entry.getValue()));
                deconstructList.add(jobTag);
            }
        }
        tag.put("direct_deconstruct_jobs", deconstructList);

        // Save final states
        ListTag finalStatesList = new ListTag();
        for (Map.Entry<BlockPos, BlockState> entry : manager.getJobFinalStates().entrySet()) {
            CompoundTag stateTag = new CompoundTag();
            stateTag.put("pos", NbtUtils.writeBlockPos(entry.getKey()));
            stateTag.put("state", NbtUtils.writeBlockState(entry.getValue()));
            finalStatesList.add(stateTag);
        }
        tag.put("job_final_states", finalStatesList);

        // Save hibernating jobs
        ListTag hibernatingList = new ListTag();
        for (Map<BlockPos, BlockState> chunkMap : manager.getHibernatingJobsMap().values()) {
            for (Map.Entry<BlockPos, BlockState> entry : chunkMap.entrySet()) {
                CompoundTag jobTag = new CompoundTag();
                jobTag.put("pos", NbtUtils.writeBlockPos(entry.getKey()));
                jobTag.put("state", NbtUtils.writeBlockState(entry.getValue()));
                hibernatingList.add(jobTag);
            }
        }
        tag.put("hibernating_jobs", hibernatingList);

        // Save assignments
        ListTag assignmentsList = new ListTag();
        for (Map.Entry<BlockPos, UUID> entry : manager.getAssignments().entrySet()) {
            CompoundTag assignTag = new CompoundTag();
            assignTag.put("pos", NbtUtils.writeBlockPos(entry.getKey()));
            assignTag.putUUID("drone_id", entry.getValue());
            assignmentsList.add(assignTag);
        }
        tag.put("assignments", assignmentsList);

        GhostLib.LOGGER.debug("Saved jobs to SavedData");

        return tag;
    }

    /**
     * Get or create SavedData for a level.
     */
    public static GhostJobSavedData getOrCreate(ServerLevel level, GhostJobManager manager) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(
                        () -> new GhostJobSavedData(manager),
                        (tag, registries) -> load(tag, registries, manager)),
                DATA_NAME);
    }

    /**
     * Mark data as dirty to trigger save.
     */
    public void markDirty() {
        this.setDirty();
    }
}
