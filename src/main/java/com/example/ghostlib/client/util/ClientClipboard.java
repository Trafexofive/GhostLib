package com.example.ghostlib.client.util;

import net.minecraft.nbt.CompoundTag;

public class ClientClipboard {
    private static CompoundTag clipboard = null;

    public static void setClipboard(CompoundTag tag) {
        clipboard = tag;
    }

    public static CompoundTag getClipboard() {
        return clipboard;
    }

    public static boolean hasClipboard() {
        return clipboard != null && !clipboard.isEmpty();
    }
}
