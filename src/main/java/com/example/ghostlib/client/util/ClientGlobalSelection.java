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

    // Lock Mode: Freezes the cursor position for Paste/Details
    public static boolean isLocked = false;
    public static BlockPos lockedPos = null;

    // Offset Mode: Shifts the pattern relative to anchor/lockedPos
    public static BlockPos patternOffset = BlockPos.ZERO;

    // Tiling Spacing: Gap/Overlap between tiles
    public static int tilingSpacingX = 0;
    public static int tilingSpacingZ = 0;

    public static boolean forceModeToggle = false;

    // Tracks if the user is currently holding the mouse button for a selection/drag
    public static boolean isSelecting = false;
    public static boolean startedSelectionThisClick = false;

    private static long lastInteractionTime = 0;
    private static final long TIMEOUT_MS = 60000; // Increased to 60s for better UX

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

        // Reset lock on mode change
        isLocked = false;
        lockedPos = null;
        patternOffset = BlockPos.ZERO;
        tilingSpacingX = 0;
        tilingSpacingZ = 0;

        updateInteractionTime();
    }

    public static void updateInteractionTime() {
        lastInteractionTime = System.currentTimeMillis();
    }

    public static void checkExpiry() {
        if (currentMode != SelectionMode.NONE && !isSelecting && !isLocked) {
            // Only timeout if not selecting and NOT locked.
            if (System.currentTimeMillis() - lastInteractionTime > TIMEOUT_MS) {
                setMode(SelectionMode.NONE);
                Minecraft.getInstance().player.displayClientMessage(Component.literal("Selection Mode Timed Out"),
                        true);
            }
        } else if (isSelecting || isLocked) {
            updateInteractionTime(); // Keep alive
        }
    }
}
