package com.example.ghostlib.history;

import com.example.ghostlib.GhostLib;
import com.example.ghostlib.util.GhostJobManager;
import com.example.ghostlib.block.entity.GhostBlockEntity;
import com.example.ghostlib.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.neoforged.fml.loading.FMLPaths;
import java.io.File;

/**
 * High-Coherence Global History Manager.
 * Reuses existing ghost markers to maintain logical continuity and construction progress.
 * High-fidelity: Persists BlockEntity data (delivered items, machine state) to prevent 'slurping' and state loss.
 */
public class GhostHistoryManager {
    
    public record StateChange(BlockPos pos, BlockState oldState, BlockState newState, @org.jetbrains.annotations.Nullable CompoundTag oldData, @org.jetbrains.annotations.Nullable CompoundTag newData) {}

    public record HistoryEntry(
        List<StateChange> changes
    ) {}

    private static final Map<UUID, Deque<HistoryEntry>> UNDO_STACKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Deque<HistoryEntry>> REDO_STACKS = new ConcurrentHashMap<>();
    private static final int MAX_HISTORY = 1000;
    
    public static boolean isProcessingHistory = false;

    public static void saveHistory(Level level) {
        if (level.isClientSide) return;
        try {
            File file = FMLPaths.CONFIGDIR.get().resolve("ghostlib/history.dat").toFile();
            if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
            
            CompoundTag root = new CompoundTag();
            saveStack(root, "Undo", UNDO_STACKS);
            saveStack(root, "Redo", REDO_STACKS);
            
            NbtIo.writeCompressed(root, file.toPath());
        } catch (Exception e) {
            GhostLib.LOGGER.error("Failed to save history", e);
        }
    }

    public static void loadHistory(Level level) {
        if (level.isClientSide) return;
        try {
            File file = FMLPaths.CONFIGDIR.get().resolve("ghostlib/history.dat").toFile();
            if (!file.exists()) return;
            
            CompoundTag root = NbtIo.readCompressed(file.toPath(), net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            loadStack(root, "Undo", UNDO_STACKS, level);
            loadStack(root, "Redo", REDO_STACKS, level);
        } catch (Exception e) {
            GhostLib.LOGGER.error("Failed to load history", e);
        }
    }

    private static void saveStack(CompoundTag root, String key, Map<UUID, Deque<HistoryEntry>> map) {
        ListTag userList = new ListTag();
        for (Map.Entry<UUID, Deque<HistoryEntry>> entry : map.entrySet()) {
            CompoundTag userTag = new CompoundTag();
            userTag.putUUID("UUID", entry.getKey());
            
            ListTag historyList = new ListTag();
            for (HistoryEntry historyEntry : entry.getValue()) {
                CompoundTag entryTag = new CompoundTag();
                ListTag changeTagList = new ListTag();
                for (StateChange change : historyEntry.changes) {
                    CompoundTag cTag = new CompoundTag();
                    cTag.put("Pos", NbtUtils.writeBlockPos(change.pos));
                    cTag.put("Old", NbtUtils.writeBlockState(change.oldState));
                    cTag.put("New", NbtUtils.writeBlockState(change.newState));
                    if (change.oldData != null) cTag.put("OldData", change.oldData);
                    if (change.newData != null) cTag.put("NewData", change.newData);
                    changeTagList.add(cTag);
                }
                entryTag.put("Changes", changeTagList);
                historyList.add(entryTag);
            }
            userTag.put("History", historyList);
            userList.add(userTag);
        }
        root.put(key, userList);
    }

    private static void loadStack(CompoundTag root, String key, Map<UUID, Deque<HistoryEntry>> map, Level level) {
        map.clear();
        if (!root.contains(key)) return;
        ListTag userList = root.getList(key, 10);
        var blockRegistries = level.holderLookup(net.minecraft.core.registries.Registries.BLOCK);
        
        for (int i = 0; i < userList.size(); i++) {
            CompoundTag userTag = userList.getCompound(i);
            UUID uuid = userTag.getUUID("UUID");
            Deque<HistoryEntry> history = new ArrayDeque<>();
            
            ListTag historyList = userTag.getList("History", 10);
            for (int j = 0; j < historyList.size(); j++) {
                CompoundTag entryTag = historyList.getCompound(j);
                ListTag changeTagList = entryTag.getList("Changes", 10);
                List<StateChange> changes = new ArrayList<>();
                for (int k = 0; k < changeTagList.size(); k++) {
                    CompoundTag cTag = changeTagList.getCompound(k);
                    changes.add(new StateChange(
                        NbtUtils.readBlockPos(cTag, "Pos").orElse(BlockPos.ZERO),
                        NbtUtils.readBlockState(blockRegistries, cTag.getCompound("Old")),
                        NbtUtils.readBlockState(blockRegistries, cTag.getCompound("New")),
                        cTag.contains("OldData") ? cTag.getCompound("OldData") : null,
                        cTag.contains("NewData") ? cTag.getCompound("NewData") : null
                    ));
                }
                history.addLast(new HistoryEntry(changes));
            }
            map.put(uuid, history);
        }
    }

    public static void recordAction(Player player, List<StateChange> changes) {
        if (isProcessingHistory || changes == null || changes.isEmpty()) return;
        UNDO_STACKS.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>()).push(new HistoryEntry(new ArrayList<>(changes)));
        REDO_STACKS.remove(player.getUUID()); 
        if (UNDO_STACKS.get(player.getUUID()).size() > MAX_HISTORY) UNDO_STACKS.get(player.getUUID()).removeLast();
    }

    public static void undo(Player player) { process(player, UNDO_STACKS, REDO_STACKS, true); }
    public static void redo(Player player) { process(player, REDO_STACKS, UNDO_STACKS, false); }

    private static void process(Player player, Map<UUID, Deque<HistoryEntry>> sourceMap, Map<UUID, Deque<HistoryEntry>> targetMap, boolean isUndo) {
        Level level = player.level();
        if (level.isClientSide) return;
        Deque<HistoryEntry> stack = sourceMap.get(player.getUUID());
        if (stack == null || stack.isEmpty()) return;

        isProcessingHistory = true;
        HistoryEntry entry = stack.pop();
        GhostJobManager jobManager = GhostJobManager.get(level);

        try {
            for (StateChange change : entry.changes) {
                BlockState targetState = isUndo ? change.oldState : change.newState;
                CompoundTag targetData = isUndo ? change.oldData : change.newData;
                BlockPos pos = change.pos;
                BlockState currentWorldState = level.getBlockState(pos);

                // DEBRIS EXCLUSION
                if (isUndo && targetState.canBeReplaced(new net.minecraft.world.item.context.BlockPlaceContext(player, net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY, new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.ZERO, net.minecraft.core.Direction.UP, pos, false)))) {
                    targetState = Blocks.AIR.defaultBlockState();
                    targetData = null;
                }

                if (targetState.isAir()) {
                    if (currentWorldState.getBlock() instanceof com.example.ghostlib.block.GhostBlock) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                        jobManager.removeJob(pos);
                    } else if (!currentWorldState.isAir()) {
                        jobManager.registerDirectDeconstruct(pos, Blocks.AIR.defaultBlockState(), level);
                    } else {
                        jobManager.removeJob(pos);
                    }
                } else {
                    // EJECT DRONE: Clear stale claims to ensure they re-read updated intent/NBT
                    jobManager.removeJob(pos);

                    if (currentWorldState.getBlock() instanceof com.example.ghostlib.block.GhostBlock) {
                        // COHERENCE REUSE: Update marker, preserve progress
                        if (level.getBlockEntity(pos) instanceof GhostBlockEntity gbe) {
                            applyStateToGhost(gbe, targetState, targetData, level);
                        }
                    } else if (currentWorldState.isAir()) {
                        level.setBlock(pos, ModBlocks.GHOST_BLOCK.get().defaultBlockState(), 3);
                        if (level.getBlockEntity(pos) instanceof GhostBlockEntity gbe) {
                            applyStateToGhost(gbe, targetState, targetData, level);
                        }
                    } else if (!currentWorldState.equals(targetState)) {
                        jobManager.registerDirectDeconstruct(pos, ModBlocks.GHOST_BLOCK.get().defaultBlockState(), targetState, level);
                    }
                }
            }
            jobManager.syncToClients(level);
            targetMap.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>()).push(entry);
            
            // GLOBAL WAKE UP: Mobilize the swarm
            if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                for (net.minecraft.world.entity.Entity entity : serverLevel.getAllEntities()) {
                    if (entity instanceof com.example.ghostlib.entity.DroneEntity drone) drone.wakeUp();
                }
            }
        } catch (Exception e) {
            GhostLib.LOGGER.error("Critical error during history processing", e);
        } finally {
            isProcessingHistory = false;
        }
    }

    private static void applyStateToGhost(GhostBlockEntity gbe, BlockState targetState, CompoundTag targetData, Level level) {
        if (targetData != null) {
            CompoundTag cleanData = targetData.copy();
            // Protect volatile state from history overrides
            cleanData.remove("state"); cleanData.remove("assigned"); cleanData.remove("target");
            gbe.loadWithComponents(cleanData, level.registryAccess());
        }
        gbe.setTargetState(targetState);
        // registerJob happens here via setState
        gbe.setState(GhostBlockEntity.GhostState.UNASSIGNED);
    }
}