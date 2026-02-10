package com.example.ghostlib.history;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * THE COMMAND LEDGER (Source of Truth)
 * 
 * This class implements a deterministic coordinate-state stack for every block 
 * coordinate touched during a session. It adheres to the Factorio Standard:
 * 
 * 1. ROOT PERSISTENCE: The first time a coordinate is touched, its "natural" state
 *    (spawn state) is captured as Version 0. This ensures "Undo All" returns the 
 *    world to its exact starting state.
 * 
 * 2. TRANSACTIONAL INTEGRITY: Actions (Blueprints or Manual) are pushed as batches.
 *    Undoing an action rolls back the stack for all affected coordinates.
 * 
 * 3. DIRTY TRACKING: Reconciliation is optimized via a Dirty Set, ensuring that 
 *    the system only processes coordinates with active Intent/Reality mismatches.
 */
public class WorldHistoryManager extends SavedData {
    private static final String DATA_NAME = "ghostlib_world_history";
    private static final int MAX_HISTORY_SIZE = 1000;

    /**
     * Lineage per coordinate.
     * Index 0: Natural Spawn State.
     * Index N: Current Intended State.
     */
    private final Map<BlockPos, List<BlockSnapshot>> coordinateStacks = new ConcurrentHashMap<>();
    
    /**
     * Set of positions where intended state has changed and requires reconciliation.
     */
    private final Set<BlockPos> dirtyPositions = Collections.synchronizedSet(new HashSet<>());

    // Global Command Timeline
    private final Deque<HistoryAction> undoStack = new ArrayDeque<>();
    private final Deque<HistoryAction> redoStack = new ArrayDeque<>();

    /**
     * Represents a single atomic operation in the world timeline.
     */
    public record HistoryAction(String name, Map<BlockPos, BlockSnapshot> changes) {
        public CompoundTag save(HolderLookup.Provider registries) {
            CompoundTag tag = new CompoundTag();
            tag.putString("Name", name);
            ListTag list = new ListTag();
            for (var entry : changes.entrySet()) {
                CompoundTag changeTag = new CompoundTag();
                changeTag.put("Pos", NbtUtils.writeBlockPos(entry.getKey()));
                changeTag.put("Snapshot", entry.getValue().save(registries));
                list.add(changeTag);
            }
            tag.put("Changes", list);
            return tag;
        }

        public static HistoryAction load(CompoundTag tag, HolderLookup.Provider registries) {
            String name = tag.getString("Name");
            Map<BlockPos, BlockSnapshot> changes = new HashMap<>();
            ListTag list = tag.getList("Changes", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag changeTag = list.getCompound(i);
                BlockPos pos = NbtUtils.readBlockPos(changeTag, "Pos").orElse(BlockPos.ZERO);
                changes.put(pos, BlockSnapshot.load(changeTag.getCompound("Snapshot"), registries));
            }
            return new HistoryAction(name, changes);
        }
    }

    public static WorldHistoryManager get(Level level) {
        if (level instanceof ServerLevel sl) {
            return sl.getDataStorage().computeIfAbsent(new Factory<>(
                    WorldHistoryManager::new,
                    WorldHistoryManager::load,
                    null
            ), DATA_NAME);
        }
        throw new IllegalStateException("Cannot access WorldHistoryManager on client side");
    }

    /**
     * Records a new action into the global timeline.
     * @param action The set of new intended states.
     * @param level The level context.
     * @param baseStates Optional map of states that existed BEFORE the action (critical for manual actions).
     */
    public void pushAction(HistoryAction action, Level level, Map<BlockPos, BlockSnapshot> baseStates) {
        for (Map.Entry<BlockPos, BlockSnapshot> entry : action.changes().entrySet()) {
            BlockPos pos = entry.getKey().immutable();
            BlockSnapshot newState = entry.getValue();
            
            List<BlockSnapshot> stack = coordinateStacks.computeIfAbsent(pos, p -> {
                List<BlockSnapshot> s = new ArrayList<>();
                // Use provided base state (manual) or capture current (blueprint)
                s.add(baseStates.getOrDefault(p, captureCurrentState(level, p)));
                return s;
            });

            if (!stack.get(stack.size() - 1).equals(newState)) {
                stack.add(newState);
                dirtyPositions.add(pos);
            }
        }
        undoStack.push(action);
        if (undoStack.size() > MAX_HISTORY_SIZE) undoStack.removeLast();
        redoStack.clear();
        setDirty();
    }

    public void pushAction(HistoryAction action, Level level) {
        pushAction(action, level, Collections.emptyMap());
    }

    public void undo(Level level) {
        if (undoStack.isEmpty()) return;
        HistoryAction action = undoStack.pop();
        
        for (BlockPos pos : action.changes().keySet()) {
            List<BlockSnapshot> stack = coordinateStacks.get(pos);
            if (stack != null && stack.size() > 1) {
                stack.remove(stack.size() - 1);
                dirtyPositions.add(pos);
            }
        }
        
        redoStack.push(action);
        setDirty();
    }

    public void redo(Level level) {
        if (redoStack.isEmpty()) return;
        HistoryAction action = redoStack.pop();
        
        for (Map.Entry<BlockPos, BlockSnapshot> entry : action.changes().entrySet()) {
            List<BlockSnapshot> stack = coordinateStacks.get(entry.getKey());
            if (stack != null) {
                stack.add(entry.getValue());
                dirtyPositions.add(entry.getKey());
            }
        }
        
        undoStack.push(action);
        setDirty();
    }

    public Set<BlockPos> getDirtyPositions() {
        return dirtyPositions;
    }

    public void markClean(BlockPos pos) {
        dirtyPositions.remove(pos);
    }

    public BlockSnapshot getIntendedState(BlockPos pos) {
        List<BlockSnapshot> stack = coordinateStacks.get(pos);
        if (stack == null || stack.isEmpty()) return null;
        return stack.get(stack.size() - 1);
    }

    private BlockSnapshot captureCurrentState(Level level, BlockPos pos) {
        var state = level.getBlockState(pos);
        var be = level.getBlockEntity(pos);
        CompoundTag nbt = null;
        if (be != null) {
            nbt = be.saveWithFullMetadata(level.registryAccess());
        }
        return new BlockSnapshot(state, nbt);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag stacksTag = new ListTag();
        for (var entry : coordinateStacks.entrySet()) {
            CompoundTag coordTag = new CompoundTag();
            coordTag.put("Pos", NbtUtils.writeBlockPos(entry.getKey()));
            ListTag stateList = new ListTag();
            for (BlockSnapshot snapshot : entry.getValue()) {
                stateList.add(snapshot.save(registries));
            }
            coordTag.put("Stack", stateList);
            stacksTag.add(coordTag);
        }
        tag.put("CoordinateStacks", stacksTag);

        ListTag undoList = new ListTag();
        for (HistoryAction action : undoStack) undoList.add(action.save(registries));
        tag.put("UndoStack", undoList);

        ListTag redoList = new ListTag();
        for (HistoryAction action : redoStack) redoList.add(action.save(registries));
        tag.put("RedoStack", redoList);

        ListTag dirtyList = new ListTag();
        for (BlockPos p : dirtyPositions) {
            CompoundTag pTag = new CompoundTag();
            pTag.put("P", NbtUtils.writeBlockPos(p));
            dirtyList.add(pTag);
        }
        tag.put("DirtyPositions", dirtyList);

        return tag;
    }

    public static WorldHistoryManager load(CompoundTag tag, HolderLookup.Provider registries) {
        WorldHistoryManager manager = new WorldHistoryManager();
        
        ListTag stacksTag = tag.getList("CoordinateStacks", Tag.TAG_COMPOUND);
        for (int i = 0; i < stacksTag.size(); i++) {
            CompoundTag coordTag = stacksTag.getCompound(i);
            BlockPos pos = NbtUtils.readBlockPos(coordTag, "Pos").orElse(BlockPos.ZERO);
            ListTag stateList = coordTag.getList("Stack", Tag.TAG_COMPOUND);
            List<BlockSnapshot> stack = new ArrayList<>();
            for (int j = 0; j < stateList.size(); j++) {
                stack.add(BlockSnapshot.load(stateList.getCompound(j), registries));
            }
            manager.coordinateStacks.put(pos, stack);
        }

        ListTag undoList = tag.getList("UndoStack", Tag.TAG_COMPOUND);
        for (int i = undoList.size() - 1; i >= 0; i--) {
            manager.undoStack.push(HistoryAction.load(undoList.getCompound(i), registries));
        }

        ListTag redoList = tag.getList("RedoStack", Tag.TAG_COMPOUND);
        for (int i = redoList.size() - 1; i >= 0; i--) {
            manager.redoStack.push(HistoryAction.load(redoList.getCompound(i), registries));
        }

        ListTag dirtyList = tag.getList("DirtyPositions", Tag.TAG_COMPOUND);
        for (int i = 0; i < dirtyList.size(); i++) {
            NbtUtils.readBlockPos(dirtyList.getCompound(i), "P").ifPresent(manager.dirtyPositions::add);
        }

        return manager;
    }
}