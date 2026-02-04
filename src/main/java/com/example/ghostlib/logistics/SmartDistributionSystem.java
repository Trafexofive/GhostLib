package com.example.ghostlib.logistics;

import com.example.ghostlib.block.entity.LogisticalChestBlockEntity;
import com.example.ghostlib.block.LogisticalChestBlock;
import com.example.ghostlib.util.LogisticsNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.*;

/**
 * Implements Factorio-grade smart item distribution system
 * Handles intelligent routing, prioritization, and network-wide inventory management
 */
public class SmartDistributionSystem {
    
    /**
     * Distribute items across the network based on chest types and priorities
     */
    public static void distributeItems(Level level, int networkId, ItemStack item, int count) {
        LogisticsNetworkManager manager = LogisticsNetworkManager.get(level);
        if (manager == null) return;
        
        Set<BlockPos> networkMembers = manager.getNetworkMembers(networkId);
        if (networkMembers.isEmpty()) return;
        
        // Sort members by chest type priority
        List<BlockPos> sortedMembers = new ArrayList<>(networkMembers);
        sortedMembers.sort(Comparator.comparingInt(pos -> getChestPriority(level, pos)));
        
        ItemStack remaining = item.copyWithCount(count);
        
        // First pass: Try to insert into storage chests
        for (BlockPos pos : sortedMembers) {
            if (remaining.isEmpty()) break;
            
            if (level.getBlockEntity(pos) instanceof LogisticalChestBlockEntity chest) {
                LogisticalChestBlock.ChestType type = chest.getChestType();
                if (type == LogisticalChestBlock.ChestType.STORAGE ||
                    type == LogisticalChestBlock.ChestType.BUFFER) {
                    // Use the proper insertItem method signature
                    remaining = insertItemToChest(chest.getInventory(), remaining, false);
                }
            }
        }
        
        // Second pass: Try to insert into passive providers
        for (BlockPos pos : sortedMembers) {
            if (remaining.isEmpty()) break;
            
            if (level.getBlockEntity(pos) instanceof LogisticalChestBlockEntity chest) {
                LogisticalChestBlock.ChestType type = chest.getChestType();
                if (type == LogisticalChestBlock.ChestType.PASSIVE_PROVIDER) {
                    remaining = insertItemToChest(chest.getInventory(), remaining, false);
                }
            }
        }
        
        // Third pass: Try to insert into active providers
        for (BlockPos pos : sortedMembers) {
            if (remaining.isEmpty()) break;
            
            if (level.getBlockEntity(pos) instanceof LogisticalChestBlockEntity chest) {
                LogisticalChestBlock.ChestType type = chest.getChestType();
                if (type == LogisticalChestBlock.ChestType.ACTIVE_PROVIDER) {
                    remaining = insertItemToChest(chest.getInventory(), remaining, false);
                }
            }
        }
        
        // If items remain, they couldn't be distributed - consider dropping or requesting new storage
        if (!remaining.isEmpty()) {
            com.example.ghostlib.util.GhostLogger.logistics("Network " + networkId + " could not distribute " + remaining.getCount() + "x " + remaining.getHoverName().getString());
        }
    }
    
    /**
     * Request items from the network based on requester chest configurations
     */
    public static ItemStack requestItem(Level level, int networkId, ItemStack template, int count) {
        LogisticsNetworkManager manager = LogisticsNetworkManager.get(level);
        if (manager == null) return ItemStack.EMPTY;
        
        Set<BlockPos> networkMembers = manager.getNetworkMembers(networkId);
        if (networkMembers.isEmpty()) return ItemStack.EMPTY;
        
        ItemStack requested = template.copyWithCount(count);
        ItemStack result = ItemStack.EMPTY;
        
        // First, check active providers (they push items out)
        for (BlockPos pos : networkMembers) {
            if (level.getBlockEntity(pos) instanceof LogisticalChestBlockEntity chest) {
                LogisticalChestBlock.ChestType type = chest.getChestType();
                if (type == LogisticalChestBlock.ChestType.ACTIVE_PROVIDER) {
                    result = extractMatchingItem(chest.getInventory(), requested);
                    if (!result.isEmpty()) {
                        return result;
                    }
                }
            }
        }
        
        // Second, check passive providers (they provide when requested)
        for (BlockPos pos : networkMembers) {
            if (level.getBlockEntity(pos) instanceof LogisticalChestBlockEntity chest) {
                LogisticalChestBlock.ChestType type = chest.getChestType();
                if (type == LogisticalChestBlock.ChestType.PASSIVE_PROVIDER) {
                    result = extractMatchingItem(chest.getInventory(), requested);
                    if (!result.isEmpty()) {
                        return result;
                    }
                }
            }
        }
        
        // Third, check storage chests
        for (BlockPos pos : networkMembers) {
            if (level.getBlockEntity(pos) instanceof LogisticalChestBlockEntity chest) {
                LogisticalChestBlock.ChestType type = chest.getChestType();
                if (type == LogisticalChestBlock.ChestType.STORAGE || 
                    type == LogisticalChestBlock.ChestType.BUFFER) {
                    result = extractMatchingItem(chest.getInventory(), requested);
                    if (!result.isEmpty()) {
                        return result;
                    }
                }
            }
        }
        
        return ItemStack.EMPTY;
    }

    /**
     * Helper method to insert item into chest inventory
     */
    private static ItemStack insertItemToChest(net.neoforged.neoforge.items.IItemHandler inventory, ItemStack stack, boolean simulate) {
        ItemStack remaining = stack.copy();

        // First pass: try to fill existing stacks
        for (int i = 0; i < inventory.getSlots(); i++) {
            if (inventory.getStackInSlot(i).isEmpty()) continue;

            remaining = inventory.insertItem(i, remaining, simulate);
            if (remaining.isEmpty()) {
                return ItemStack.EMPTY;
            }
        }

        // Second pass: try empty slots
        for (int i = 0; i < inventory.getSlots(); i++) {
            if (!inventory.getStackInSlot(i).isEmpty()) continue;

            remaining = inventory.insertItem(i, remaining, simulate);
            if (remaining.isEmpty()) {
                return ItemStack.EMPTY;
            }
        }

        return remaining;
    }

    /**
     * Get priority value for a chest type (higher = higher priority)
     */
    private static int getChestPriority(Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof LogisticalChestBlockEntity chest)) {
            return 0; // Unknown block
        }

        return switch (chest.getChestType()) {
            case STORAGE -> 3;      // Highest priority for general storage
            case BUFFER -> 2;       // Medium priority for buffering
            case PASSIVE_PROVIDER -> 1; // Lower priority for providers
            case ACTIVE_PROVIDER -> 1;  // Lower priority for providers
            case REQUESTER -> -1;       // Negative priority (requesters don't accept items)
            default -> 0;
        };
    }
    
    /**
     * Extract a matching item from an inventory
     */
    private static ItemStack extractMatchingItem(net.neoforged.neoforge.items.IItemHandler inventory, ItemStack template) {
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, template)) {
                int count = Math.min(template.getCount(), stack.getCount());
                return inventory.extractItem(i, count, false);
            }
        }
        return ItemStack.EMPTY;
    }
    
    /**
     * Balance inventory across network members of the same type
     */
    public static void balanceNetworkInventory(Level level, int networkId) {
        LogisticsNetworkManager manager = LogisticsNetworkManager.get(level);
        if (manager == null) return;
        
        Set<BlockPos> networkMembers = manager.getNetworkMembers(networkId);
        if (networkMembers.size() <= 1) return;
        
        // Group members by chest type
        Map<LogisticalChestBlock.ChestType, List<BlockPos>> groupedMembers = new HashMap<>();
        
        for (BlockPos pos : networkMembers) {
            if (level.getBlockEntity(pos) instanceof LogisticalChestBlockEntity chest) {
                LogisticalChestBlock.ChestType type = chest.getChestType();
                groupedMembers.computeIfAbsent(type, k -> new ArrayList<>()).add(pos);
            }
        }
        
        // Balance each chest type group
        for (var entry : groupedMembers.entrySet()) {
            if (entry.getValue().size() > 1) {
                balanceChestGroup(level, entry.getValue());
            }
        }
    }
    
    /**
     * Balance inventory across a group of chests of the same type
     */
    private static void balanceChestGroup(Level level, List<BlockPos> chestPositions) {
        if (chestPositions.size() <= 1) return;
        
        // Collect all items from the group
        Map<String, Integer> totalItems = new HashMap<>();
        List<net.neoforged.neoforge.items.IItemHandler> inventories = new ArrayList<>();
        
        for (BlockPos pos : chestPositions) {
            if (level.getBlockEntity(pos) instanceof LogisticalChestBlockEntity chest) {
                net.neoforged.neoforge.items.IItemHandler inv = chest.getInventory();
                inventories.add(inv);
                
                // Count items in this inventory
                for (int i = 0; i < inv.getSlots(); i++) {
                    ItemStack stack = inv.getStackInSlot(i);
                    if (!stack.isEmpty()) {
                        String key = getItemKey(stack);
                        totalItems.merge(key, stack.getCount(), Integer::sum);
                    }
                }
            }
        }
        
        // Calculate target distribution
        Map<String, Integer> targetPerChest = new HashMap<>();
        for (var entry : totalItems.entrySet()) {
            int total = entry.getValue();
            int perChest = total / chestPositions.size();
            int remainder = total % chestPositions.size();
            targetPerChest.put(entry.getKey(), perChest + (remainder > 0 ? 1 : 0)); // Distribute remainder
        }
        
        // Redistribute items
        for (int i = 0; i < inventories.size(); i++) {
            net.neoforged.neoforge.items.IItemHandler inv = inventories.get(i);
            redistributeInventory(inv, targetPerChest);
        }
    }
    
    /**
     * Get a unique key for an item (item ID + NBT)
     */
    private static String getItemKey(ItemStack stack) {
        if (stack.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)) {
            net.minecraft.core.component.DataComponentMap components = stack.getComponents();
            StringBuilder keyBuilder = new StringBuilder();
            keyBuilder.append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());

            if (components.has(net.minecraft.core.component.DataComponents.DAMAGE)) {
                keyBuilder.append(":damage=").append(components.get(net.minecraft.core.component.DataComponents.DAMAGE));
            }
            if (components.has(net.minecraft.core.component.DataComponents.ENCHANTMENTS)) {
                keyBuilder.append(":enchanted");
            }
            if (components.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)) {
                keyBuilder.append(":named");
            }

            return keyBuilder.toString();
        }
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
    
    /**
     * Redistribute items in an inventory based on targets
     */
    private static void redistributeInventory(net.neoforged.neoforge.items.IItemHandler inventory, Map<String, Integer> targetCounts) {
        // This is a simplified redistribution - in practice, this would require
        // more complex logic to move items between inventories in the network
        // For now, we'll just log the intended redistribution
        com.example.ghostlib.util.GhostLogger.logistics("Redistribution logic would balance inventory to targets: " + targetCounts);
    }
}