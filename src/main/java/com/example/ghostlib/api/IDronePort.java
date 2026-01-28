package com.example.ghostlib.api;

import net.minecraft.world.item.ItemStack;

public interface IDronePort {
    /**
     * Attempt to insert an item into the port's storage.
     * @return The remainder that could not be inserted.
     */
    ItemStack insertItem(ItemStack stack, boolean simulate);

    /**
     * Attempt to extract an item from the port's storage.
     * @return The extracted item.
     */
    ItemStack extractItem(ItemStack stack, int amount, boolean simulate);

    /**
     * Charge the drone's energy.
     * @param maxReceive Maximum energy to receive.
     * @param simulate Whether to simulate.
     * @return Energy received.
     */
    int chargeDrone(int maxReceive, boolean simulate);
    
    /**
     * Check if this port is valid/active.
     */
    boolean isValid();
}
