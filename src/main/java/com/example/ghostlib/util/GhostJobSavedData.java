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

/**
 * Persistent storage for {@link GhostJobManager} across server restarts.
 *
 * <h2>Assignment persistence policy</h2>
 * Drone assignments ({@code assignedPositions}) are NOT persisted. After a restart all drones
 * are dead, so any saved assignment would permanently lock the job. Jobs are instead restored
 * as UNASSIGNED so that newly spawned drones can claim them immediately.
 *
 * <h2>Load-safe path</h2>
 * The {@code load()} method runs before any {@link net.minecraft.server.level.ServerLevel} is
 * fully available, so it must never call methods that trigger client syncing.  All registration
 * calls use the {@code *Safe} variants on {@link GhostJobManager} that skip the sync.
 */
public class GhostJobSavedData extends SavedData {

    private static final String DATA_NAME = "ghostlib_jobs";

    private final GhostJobManager manager;

    public GhostJobSavedData(GhostJobManager manager) {
        this.manager = manager;
    }

    // -------------------------------------------------------------------------
    // Load
    // -------------------------------------------------------------------------

    public static GhostJobSavedData load(CompoundTag tag, HolderLookup.Provider registries, GhostJobManager manager) {
        GhostJobSavedData data = new GhostJobSavedData(manager);

        // ── Construction jobs ─────────────────────────────────────────────────
        if (tag.contains("construction_jobs", Tag.TAG_LIST)) {
            ListTag list = tag.getList("construction_jobs", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag jobTag = list.getCompound(i);
                BlockPos pos = NbtUtils.readBlockPos(jobTag, "pos").orElse(null);
                if (pos == null || pos.equals(BlockPos.ZERO)) continue;
                BlockState state = NbtUtils.readBlockState(
                        registries.lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK),
                        jobTag.getCompound("state"));
                // registerJob is load-safe — it never calls syncToClients
                manager.registerJobSafe(pos, GhostBlockEntity.GhostState.UNASSIGNED, state);
            }
        }

        // ── Ghost removal jobs ────────────────────────────────────────────────
        if (tag.contains("ghost_removal_jobs", Tag.TAG_LIST)) {
            ListTag list = tag.getList("ghost_removal_jobs", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag jobTag = list.getCompound(i);
                BlockPos pos = NbtUtils.readBlockPos(jobTag, "pos").orElse(null);
                if (pos == null || pos.equals(BlockPos.ZERO)) continue;
                manager.registerJobSafe(pos, GhostBlockEntity.GhostState.TO_REMOVE, null);
            }
        }

        // ── Direct deconstruct jobs ───────────────────────────────────────────
        if (tag.contains("direct_deconstruct_jobs", Tag.TAG_LIST)) {
            ListTag list = tag.getList("direct_deconstruct_jobs", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag jobTag = list.getCompound(i);
                BlockPos pos = NbtUtils.readBlockPos(jobTag, "pos").orElse(null);
                if (pos == null || pos.equals(BlockPos.ZERO)) continue;
                BlockState targetAfter = NbtUtils.readBlockState(
                        registries.lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK),
                        jobTag.getCompound("target_after"));
                // SAFE variant — no Level, no client sync
                manager.registerDirectDeconstructSafe(pos, targetAfter, null);
            }
        }

        // ── Chained final states (must run after direct_deconstruct_jobs) ─────
        if (tag.contains("job_final_states", Tag.TAG_LIST)) {
            ListTag list = tag.getList("job_final_states", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag stateTag = list.getCompound(i);
                BlockPos pos = NbtUtils.readBlockPos(stateTag, "pos").orElse(null);
                if (pos == null || pos.equals(BlockPos.ZERO)) continue;
                if (!manager.isDeconstructAt(pos)) continue;
                BlockState state = NbtUtils.readBlockState(
                        registries.lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK),
                        stateTag.getCompound("state"));
                BlockState targetAfter = manager.getTargetAfterDeconstruct(pos);
                // Re-register with final state; still safe — no Level
                manager.registerDirectDeconstructSafe(pos, targetAfter, state);
            }
        }

        // ── Hibernating jobs ──────────────────────────────────────────────────
        if (tag.contains("hibernating_jobs", Tag.TAG_LIST)) {
            ListTag list = tag.getList("hibernating_jobs", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag jobTag = list.getCompound(i);
                BlockPos pos = NbtUtils.readBlockPos(jobTag, "pos").orElse(null);
                if (pos == null || pos.equals(BlockPos.ZERO)) continue;
                BlockState state = NbtUtils.readBlockState(
                        registries.lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK),
                        jobTag.getCompound("state"));
                manager.registerJobSafe(pos, GhostBlockEntity.GhostState.MISSING_ITEMS, state);
            }
        }

        // ── Assignments NOT restored — see class-level Javadoc ───────────────
        if (tag.contains("assignments", Tag.TAG_LIST)) {
            GhostLib.LOGGER.debug("[GhostJobSavedData] Skipping {} stale assignments from previous session.",
                    tag.getList("assignments", Tag.TAG_COMPOUND).size());
        }

        GhostLib.LOGGER.info("[GhostJobSavedData] Loaded: {} construction, {} deconstruct, {} hibernating",
                manager.getConstructionJobs().size(),
                manager.getDirectDeconstructJobs().size(),
                manager.getHibernatingJobs().size());

        return data;
    }

    // -------------------------------------------------------------------------
    // Save
    // -------------------------------------------------------------------------

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {

        // ── Construction jobs ─────────────────────────────────────────────────
        ListTag constructionList = new ListTag();
        for (Map<BlockPos, BlockState> chunkMap : manager.getConstructionJobsMap().values()) {
            for (Map.Entry<BlockPos, BlockState> entry : chunkMap.entrySet()) {
                CompoundTag jobTag = new CompoundTag();
                jobTag.put("pos",   NbtUtils.writeBlockPos(entry.getKey()));
                jobTag.put("state", NbtUtils.writeBlockState(entry.getValue()));
                constructionList.add(jobTag);
            }
        }
        tag.put("construction_jobs", constructionList);

        // ── Ghost removal jobs ────────────────────────────────────────────────
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

        // ── Direct deconstruct jobs ───────────────────────────────────────────
        ListTag deconstructList = new ListTag();
        for (Map<BlockPos, BlockState> chunkMap : manager.getDirectDeconstructJobs().values()) {
            for (Map.Entry<BlockPos, BlockState> entry : chunkMap.entrySet()) {
                CompoundTag jobTag = new CompoundTag();
                jobTag.put("pos",          NbtUtils.writeBlockPos(entry.getKey()));
                jobTag.put("target_after", NbtUtils.writeBlockState(entry.getValue()));
                deconstructList.add(jobTag);
            }
        }
        tag.put("direct_deconstruct_jobs", deconstructList);

        // ── Final states ──────────────────────────────────────────────────────
        ListTag finalStatesList = new ListTag();
        for (Map.Entry<BlockPos, BlockState> entry : manager.getJobFinalStates().entrySet()) {
            CompoundTag stateTag = new CompoundTag();
            stateTag.put("pos",   NbtUtils.writeBlockPos(entry.getKey()));
            stateTag.put("state", NbtUtils.writeBlockState(entry.getValue()));
            finalStatesList.add(stateTag);
        }
        tag.put("job_final_states", finalStatesList);

        // ── Hibernating jobs ──────────────────────────────────────────────────
        ListTag hibernatingList = new ListTag();
        for (Map<BlockPos, BlockState> chunkMap : manager.getHibernatingJobsMap().values()) {
            for (Map.Entry<BlockPos, BlockState> entry : chunkMap.entrySet()) {
                CompoundTag jobTag = new CompoundTag();
                jobTag.put("pos",   NbtUtils.writeBlockPos(entry.getKey()));
                jobTag.put("state", NbtUtils.writeBlockState(entry.getValue()));
                hibernatingList.add(jobTag);
            }
        }
        tag.put("hibernating_jobs", hibernatingList);

        // ── Assignments intentionally NOT saved — see class-level Javadoc ─────
        // We write an empty list so older saves don't cause unexpected load behaviour.
        tag.put("assignments", new ListTag());

        GhostLib.LOGGER.debug("[GhostJobSavedData] Saved jobs to disk.");
        return tag;
    }

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    public static GhostJobSavedData getOrCreate(ServerLevel level, GhostJobManager manager) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(
                        () -> new GhostJobSavedData(manager),
                        (nbt, registries) -> load(nbt, registries, manager)),
                DATA_NAME);
    }

    /** Proxy so GhostJobManager can trigger saves without holding a direct reference to the raw API. */
    public void markDirty() {
        this.setDirty();
    }
}
