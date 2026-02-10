package com.example.ghostlib.event;

import com.example.ghostlib.GhostLib;
import com.example.ghostlib.util.GhostJobManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Handles server-side level ticks and chunk events to drive the
 * GhostJobManager.
 */
@EventBusSubscriber(modid = GhostLib.MODID)
public class LevelTickHandler {

    /**
     * Ticks the job manager for the current level.
     */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof Level level && !level.isClientSide) {
            com.example.ghostlib.util.GhostJobManager.get(level).tick(level);
            com.example.ghostlib.history.WorldReconciler.get(level).tick((net.minecraft.server.level.ServerLevel)level);
        }
    }

    /**
     * Cleans up jobs when chunks unload to prevent memory leaks.
     * This is critical for long-running servers.
     */
    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel().isClientSide())
            return;

        long chunkKey = event.getChunk().getPos().toLong();
        GhostJobManager manager = GhostJobManager.get((Level) event.getLevel());

        // CRITICAL: Clean up volatile job maps to prevent memory leak.
        // Persistence is handled separately by GhostJobSavedData.
        manager.getConstructionJobsMap().remove(chunkKey);
        manager.getGhostRemovalJobsMap().remove(chunkKey);
        manager.getDirectDeconstructJobs().remove(chunkKey);
        manager.getHibernatingJobsMap().remove(chunkKey);
        manager.releaseAssignmentsInChunk(chunkKey);

        GhostLib.LOGGER.debug("Cleaned up jobs and assignments in chunk {} during unload", event.getChunk().getPos());
    }
}
