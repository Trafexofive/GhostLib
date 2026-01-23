package com.example.ghostlib.client.util;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.client.Minecraft;

public class ClientGlobalSelection {
    public enum SelectionMode {
        NONE, COPY, CUT, PASTE, DECONSTRUCT
    }

    public static SelectionMode currentMode = SelectionMode.NONE;
    public static BlockPos anchorPos = null;
    public static BlockPos currentEndPos = null;
    
    // Tracks if the user is currently holding the mouse button for a selection/drag
    public static boolean isSelecting = false;
    public static boolean startedSelectionThisClick = false;
    
    private static long lastInteractionTime = 0;
    private static final long TIMEOUT_MS = 5000; // 5 seconds

    public static void setMode(SelectionMode mode) {
        currentMode = mode;
        resetSelection();
        updateInteractionTime();
    }

    public static void startSelection(BlockPos pos) {
        anchorPos = pos;
        currentEndPos = pos;
        isSelecting = true;
        updateInteractionTime();
    }

    public static void updateSelection(BlockPos pos) {
        if (pos != null && !pos.equals(currentEndPos)) {
            currentEndPos = pos;
            updateInteractionTime();
        } else if (currentEndPos == null && pos != null) {
            currentEndPos = pos;
            updateInteractionTime();
        }
    }

    public static void resetSelection() {
        anchorPos = null;
        currentEndPos = null;
        isSelecting = false;
        updateInteractionTime();
    }
    
    public static void updateInteractionTime() {
        lastInteractionTime = System.currentTimeMillis();
    }

    public static void checkExpiry() {
        if (currentMode != SelectionMode.NONE && !isSelecting) {
            if (System.currentTimeMillis() - lastInteractionTime > TIMEOUT_MS) {
                setMode(SelectionMode.NONE);
                Minecraft.getInstance().player.displayClientMessage(Component.literal("Selection Mode Timed Out"), true);
            }
        } else if (isSelecting) {
            updateInteractionTime(); // Keep alive while dragging
        }
    }
}
