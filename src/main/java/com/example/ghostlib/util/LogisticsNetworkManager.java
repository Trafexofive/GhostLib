package com.example.ghostlib.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Manages virtual "Logistics Networks" that link multiple ports and chests together.
 */
public class LogisticsNetworkManager extends SavedData {
    private static final String DATA_NAME = "ghostlib_logistics_networks";

    private final Map<Integer, Set<BlockPos>> networkMembers = new HashMap<>();
    private final Map<BlockPos, Integer> posToNetworkId = new HashMap<>();
    private int nextId = 1;
    public LogisticsNetworkManager() {}


    public static LogisticsNetworkManager get(Level level) {
        if (level instanceof ServerLevel sl) {
            return sl.getDataStorage().computeIfAbsent(new Factory<>(
                LogisticsNetworkManager::new,
                LogisticsNetworkManager::load,
                null
            ), DATA_NAME);
        }
        return null;
    }

    public static LogisticsNetworkManager load(CompoundTag tag, HolderLookup.Provider registries) {
        LogisticsNetworkManager manager = new LogisticsNetworkManager();
        manager.nextId = tag.getInt("NextId");
        ListTag nets = tag.getList("Networks", Tag.TAG_COMPOUND);
        for (int i = 0; i < nets.size(); i++) {
            CompoundTag netTag = nets.getCompound(i);
            int id = netTag.getInt("Id");
            Set<BlockPos> members = new HashSet<>();
            ListTag memberList = netTag.getList("Members", Tag.TAG_COMPOUND);
            for (int j = 0; j < memberList.size(); j++) {
                NbtUtils.readBlockPos(memberList.getCompound(j), "P").ifPresent(p -> {
                    members.add(p);
                    manager.posToNetworkId.put(p, id);
                });
            }
            manager.networkMembers.put(id, members);
        }
        return manager;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("NextId", nextId);
        ListTag nets = new ListTag();
        for (var entry : networkMembers.entrySet()) {
            CompoundTag netTag = new CompoundTag();
            netTag.putInt("Id", entry.getKey());
            ListTag memberList = new ListTag();
            for (BlockPos p : entry.getValue()) {
                CompoundTag pTag = new CompoundTag();
                pTag.put("P", NbtUtils.writeBlockPos(p));
                memberList.add(pTag);
            }
            netTag.put("Members", memberList);
            nets.add(netTag);
        }
        tag.put("Networks", nets);
        return tag;
    }

    /**
     * Join a specific network by ID
     */
    public void joinNetwork(BlockPos pos, int id) {
        leaveNetwork(pos);
        networkMembers.computeIfAbsent(id, k -> new HashSet<>()).add(pos);
        posToNetworkId.put(pos, id);
        setDirty();
    }

    /**
     * Automatically assign to an existing network if adjacent, or create a new one
     */
    public int joinOrCreateNetwork(BlockPos pos, Level level) {
        leaveNetwork(pos);

        // Look for adjacent network members to join their network
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;

                    BlockPos neighborPos = pos.offset(dx, dy, dz);
                    Integer neighborNetworkId = posToNetworkId.get(neighborPos);
                    if (neighborNetworkId != null) {
                        // Join the neighbor's network
                        networkMembers.computeIfAbsent(neighborNetworkId, k -> new HashSet<>()).add(pos);
                        posToNetworkId.put(pos, neighborNetworkId);
                        setDirty();
                        return neighborNetworkId;
                    }
                }
            }
        }

        // No adjacent networks found, create a new one
        int newId = nextId++;
        networkMembers.computeIfAbsent(newId, k -> new HashSet<>()).add(pos);
        posToNetworkId.put(pos, newId);
        setDirty();
        return newId;
    }

    /**
     * Merge two networks together
     */
    public void mergeNetworks(int sourceId, int targetId) {
        if (sourceId == targetId) return;

        Set<BlockPos> sourceMembers = networkMembers.remove(sourceId);
        if (sourceMembers != null) {
            Set<BlockPos> targetMembers = networkMembers.computeIfAbsent(targetId, k -> new HashSet<>());
            for (BlockPos pos : sourceMembers) {
                targetMembers.add(pos);
                posToNetworkId.put(pos, targetId);
            }
        }
        setDirty();
    }

    public void leaveNetwork(BlockPos pos) {
        Integer id = posToNetworkId.remove(pos);
        if (id != null) {
            Set<BlockPos> members = networkMembers.get(id);
            if (members != null) {
                members.remove(pos);
                if (members.isEmpty()) networkMembers.remove(id);
            }
            setDirty();
        }
    }

    public Integer getNetworkId(BlockPos pos) {
        return posToNetworkId.get(pos);
    }

    public Set<BlockPos> getNetworkMembers(int id) {
        return networkMembers.getOrDefault(id, new HashSet<>());
    }
}
