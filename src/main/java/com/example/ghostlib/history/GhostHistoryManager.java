package com.example.ghostlib.history;

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
 * Manages the global Undo/Redo history for all players.
 * 
 * <p>Key Responsibilities:</p>
 * <ul>
 *   <li><b>State Tracking:</b> Records blocks before and after changes.</li>
 *   <li><b>Persistence:</b> Saves history to disk.</li>
 *   <li><b>Undo/Redo:</b> Reverts changes by generating Drone Jobs (Construction/Deconstruction).</li>
 * </ul>
 */
public class GhostHistoryManager {
    
    public record StateChange(BlockPos pos, BlockState oldState, BlockState newState) {}

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
            e.printStackTrace();
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
            e.printStackTrace();
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
                        NbtUtils.readBlockState(level.holderLookup(net.minecraft.core.registries.Registries.BLOCK), cTag.getCompound("Old")),
                        NbtUtils.readBlockState(level.holderLookup(net.minecraft.core.registries.Registries.BLOCK), cTag.getCompound("New"))
                    ));
                }
                history.addLast(new HistoryEntry(changes));
            }
            map.put(uuid, history);
        }
    }

    public static void recordAction(Player player, List<StateChange> changes) {
        if (isProcessingHistory || changes == null || changes.isEmpty()) return;

        UNDO_STACKS.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>())
            .push(new HistoryEntry(new ArrayList<>(changes)));
        REDO_STACKS.remove(player.getUUID()); 
        
        if (UNDO_STACKS.get(player.getUUID()).size() > MAX_HISTORY) {
            UNDO_STACKS.get(player.getUUID()).removeLast();
        }
    }

    public static void undo(Player player) {
        process(player, UNDO_STACKS, REDO_STACKS, true);
    }

    public static void redo(Player player) {
        process(player, REDO_STACKS, UNDO_STACKS, false);
    }

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
                
                // PERFORMANCE & UX OPTIMIZATION: "Debris Exclusion"
                // When placing a structure, drones often clear away grass, snow, or flowers.
                // Reverting the placement should logically result in a clean area, not 
                // a construction queue to "re-plant" every individual piece of grass.
                // We treat all 'replaceable' blocks as Air during Undo.
                if (isUndo && targetState.canBeReplaced(new net.minecraft.world.item.context.BlockPlaceContext(player, net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY, new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.ZERO, net.minecraft.core.Direction.UP, change.pos, false)))) {
                    targetState = Blocks.AIR.defaultBlockState();
                }

                BlockPos pos = change.pos;
                BlockState currentWorldState = level.getBlockState(pos);

                // ZERO FORCE: Order a deconstruction job for anything in the way.
                // ZERO FORCE: Order a deconstruction job for anything in the way.
                if (targetState.isAir()) {
                    // We want to revert to AIR (Removal).
                    if (!currentWorldState.isAir() && !(currentWorldState.getBlock() instanceof com.example.ghostlib.block.GhostBlock)) {
                        // Real block in way: Mark for deconstruction
                        jobManager.registerDirectDeconstruct(pos, Blocks.AIR.defaultBlockState(), level);
                    } else if (currentWorldState.getBlock() instanceof com.example.ghostlib.block.GhostBlock) {
                        // INSTANT UNDO: Ghost markers are meta-data. Reverting them should be instant.
                        // We do NOT want to dispatch drones to break "nothing".
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                    }
                } else {
                    // We want to revert to a BLOCK (Placement).
                    if (!currentWorldState.equals(targetState)) {
                        if (!currentWorldState.isAir() && !(currentWorldState.getBlock() instanceof com.example.ghostlib.block.GhostBlock)) {
                            // Block in the way: Deconstruct it, then the drone will place the Ghost marker.
                            jobManager.registerDirectDeconstruct(pos, ModBlocks.GHOST_BLOCK.get().defaultBlockState(), targetState, level);
                        } else {
                            // Area is Air or already a ghost marker. Place/Update the marker.
                            level.setBlock(pos, ModBlocks.GHOST_BLOCK.get().defaultBlockState(), 3);
                            if (level.getBlockEntity(pos) instanceof GhostBlockEntity gbe) {
                                gbe.setTargetState(targetState);
                                gbe.setState(GhostBlockEntity.GhostState.UNASSIGNED);
                            }
                        }
                    }
                }
            }
            jobManager.syncToClients(level);
            targetMap.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>()).push(entry);
        } finally {
            isProcessingHistory = false;
        }
    }
}