package com.example.ghostlib.history;

import com.example.ghostlib.util.GhostJobManager;
import com.example.ghostlib.block.entity.GhostBlockEntity;
import com.example.ghostlib.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
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
 * Manages the global Undo/Redo history for all players.
 * 
 * <p>Key Responsibilities:</p>
 * <ul>
 *   <li><b>State Tracking:</b> Records blocks before and after changes (Construction, Deconstruction, Manual placement).</li>
 *   <li><b>Persistence:</b> Saves history to disk (NBT) on server stop and loads on start.</li>
 *   <li><b>Undo/Redo:</b> Reverts or reapplies changes by creating/removing Ghost Jobs.</li>
 * </ul>
 */
public class GhostHistoryManager {
    
    /** Represents a single block change event. */
    public record StateChange(BlockPos pos, BlockState oldState, BlockState newState) {}

    private static final Map<UUID, Deque<List<StateChange>>> UNDO_STACKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Deque<List<StateChange>>> REDO_STACKS = new ConcurrentHashMap<>();
    private static final int MAX_HISTORY = 50;
    
    /** Flag to prevent recursive recording when Undo/Redo is applying changes. */
    public static boolean isProcessingHistory = false;

    /** Saves all player history to `config/ghostlib/history.dat`. */
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
            e.printStackTrace();
        }
    }

    /** Loads player history from disk. */
    public static void loadHistory(Level level) {
        if (level.isClientSide) return;
        try {
            File file = FMLPaths.CONFIGDIR.get().resolve("ghostlib/history.dat").toFile();
            if (!file.exists()) return;
            
            CompoundTag root = NbtIo.readCompressed(file.toPath(), net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            loadStack(root, "Undo", UNDO_STACKS, level);
            loadStack(root, "Redo", REDO_STACKS, level);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void saveStack(CompoundTag root, String key, Map<UUID, Deque<List<StateChange>>> map) {
        ListTag userList = new ListTag();
        for (Map.Entry<UUID, Deque<List<StateChange>>> entry : map.entrySet()) {
            CompoundTag userTag = new CompoundTag();
            userTag.putUUID("UUID", entry.getKey());
            
            ListTag historyList = new ListTag();
            for (List<StateChange> changeList : entry.getValue()) {
                ListTag changeTagList = new ListTag();
                for (StateChange change : changeList) {
                    CompoundTag cTag = new CompoundTag();
                    cTag.put("Pos", NbtUtils.writeBlockPos(change.pos));
                    cTag.put("Old", NbtUtils.writeBlockState(change.oldState));
                    cTag.put("New", NbtUtils.writeBlockState(change.newState));
                    changeTagList.add(cTag);
                }
                historyList.add(changeTagList);
            }
            userTag.put("History", historyList);
            userList.add(userTag);
        }
        root.put(key, userList);
    }

    private static void loadStack(CompoundTag root, String key, Map<UUID, Deque<List<StateChange>>> map, Level level) {
        map.clear();
        if (!root.contains(key)) return;
        ListTag userList = root.getList(key, 10);
        for (int i = 0; i < userList.size(); i++) {
            CompoundTag userTag = userList.getCompound(i);
            UUID uuid = userTag.getUUID("UUID");
            Deque<List<StateChange>> history = new ArrayDeque<>();
            
            ListTag historyList = userTag.getList("History", 9); // 9 = ListTag type
            // To restore stack order (Last In, First Out), we read from the list (which was saved in iteration order)
            // But ArrayDeque.push adds to HEAD. ArrayDeque.addLast adds to TAIL.
            // If we saved [Action1, Action2, Action3] (Action1 is oldest)
            // We want stack pop() to return Action3.
            // So we should addLast(Action1), addLast(Action2)...
            
            for (int j = 0; j < historyList.size(); j++) {
                ListTag changeTagList = (ListTag) historyList.get(j);
                List<StateChange> changes = new ArrayList<>();
                for (int k = 0; k < changeTagList.size(); k++) {
                    CompoundTag cTag = changeTagList.getCompound(k);
                    changes.add(new StateChange(
                        NbtUtils.readBlockPos(cTag, "Pos").orElse(BlockPos.ZERO),
                        NbtUtils.readBlockState(level.holderLookup(net.minecraft.core.registries.Registries.BLOCK), cTag.getCompound("Old")),
                        NbtUtils.readBlockState(level.holderLookup(net.minecraft.core.registries.Registries.BLOCK), cTag.getCompound("New"))
                    ));
                }
                history.addLast(changes); // Restore oldest to bottom of stack
            }
            map.put(uuid, history);
        }
    }

    /**
     * Records a new action to the Undo stack. Clears the Redo stack.
     * @param player The player performing the action.
     * @param changes The list of block changes involved.
     */
    public static void recordAction(Player player, List<StateChange> changes) {
        if (isProcessingHistory || changes == null || changes.isEmpty()) return;

        UNDO_STACKS.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>()).push(new ArrayList<>(changes));
        REDO_STACKS.remove(player.getUUID()); 
        
        if (UNDO_STACKS.get(player.getUUID()).size() > MAX_HISTORY) {
            UNDO_STACKS.get(player.getUUID()).removeLast();
        }
    }

    /** Performs an Undo operation for the player. */
    public static void undo(Player player) {
        process(player, UNDO_STACKS, REDO_STACKS, true);
    }

    /** Performs a Redo operation for the player. */
    public static void redo(Player player) {
        process(player, REDO_STACKS, UNDO_STACKS, false);
    }

    private static void process(Player player, Map<UUID, Deque<List<StateChange>>> sourceMap, Map<UUID, Deque<List<StateChange>>> targetMap, boolean isUndo) {
        Level level = player.level();
        if (level.isClientSide) return;

        Deque<List<StateChange>> stack = sourceMap.get(player.getUUID());
        if (stack == null || stack.isEmpty()) return;

        isProcessingHistory = true;
        List<StateChange> action = stack.pop();
        GhostJobManager jobManager = GhostJobManager.get(level);

        try {
            for (StateChange change : action) {
                BlockState targetState = isUndo ? change.oldState : change.newState;
                BlockPos pos = change.pos;

                jobManager.removeJob(pos);

                if (targetState.isAir()) {
                    if (!level.getBlockState(pos).isAir()) {
                        jobManager.registerDirectDeconstruct(pos, Blocks.AIR.defaultBlockState(), level);
                    }
                } else {
                    level.setBlock(pos, ModBlocks.GHOST_BLOCK.get().defaultBlockState(), 3);
                    if (level.getBlockEntity(pos) instanceof GhostBlockEntity gbe) {
                        gbe.setTargetState(targetState);
                        gbe.setState(GhostBlockEntity.GhostState.UNASSIGNED);
                    }
                }
            }
            jobManager.syncToClients(level);
            targetMap.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>()).push(action);
        } finally {
            isProcessingHistory = false;
        }
    }
}
