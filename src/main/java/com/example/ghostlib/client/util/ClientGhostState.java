package com.example.ghostlib.client.util;

import net.minecraft.core.BlockPos;

public class ClientGhostState {
    public static boolean isDragging = false;
    public static BlockPos anchorPos = null;
    
    public static void startDrag(BlockPos pos) {
        isDragging = true;
        anchorPos = pos;
    }

    public static void stopDrag() {
        isDragging = false;
        anchorPos = null;
    }
}