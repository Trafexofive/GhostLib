package com.example.ghostlib.client;

import com.example.ghostlib.GhostLib;
import com.example.ghostlib.client.util.ClientClipboard;
import com.example.ghostlib.client.util.ClientGlobalSelection;
import com.example.ghostlib.network.payload.ServerboundRedoPacket;
import com.example.ghostlib.network.payload.ServerboundUndoPacket;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;

public class Keybinds {
    public static final String CATEGORY = "key.categories.ghostlib";

    public static final KeyMapping UNDO_KEY = new KeyMapping("key.ghostlib.undo", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, CATEGORY);
    public static final KeyMapping REDO_KEY = new KeyMapping("key.ghostlib.redo", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Y, CATEGORY);
    public static final KeyMapping CUT_KEY = new KeyMapping("key.ghostlib.cut", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_X, CATEGORY);
    public static final KeyMapping COPY_KEY = new KeyMapping("key.ghostlib.copy", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, CATEGORY);
    public static final KeyMapping PASTE_KEY = new KeyMapping("key.ghostlib.paste", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, CATEGORY);
    public static final KeyMapping DELETE_KEY = new KeyMapping("key.ghostlib.delete", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_D, CATEGORY);
    public static final KeyMapping LOCK_KEY = new KeyMapping("key.ghostlib.lock", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, CATEGORY);
    public static final KeyMapping ROTATE_KEY = new KeyMapping("key.ghostlib.rotate", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, CATEGORY);
    public static final KeyMapping FLIP_KEY = new KeyMapping("key.ghostlib.flip", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F, CATEGORY);

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(UNDO_KEY);
        event.register(REDO_KEY);
        event.register(CUT_KEY);
        event.register(COPY_KEY);
        event.register(PASTE_KEY);
        event.register(DELETE_KEY);
        event.register(LOCK_KEY);
        event.register(ROTATE_KEY);
        event.register(FLIP_KEY);
    }

    @EventBusSubscriber(modid = GhostLib.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
    public static class KeyInputHandler {
        
        private static final Map<Integer, Integer> heldKeys = new HashMap<>();
        private static final int INITIAL_DELAY = 10;
        private static final int REPEAT_DELAY = 2;

        @SubscribeEvent
        public static void onKeyInput(InputEvent.Key event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.screen != null) return;

            int key = event.getKey();
            int action = event.getAction();

            if (action == GLFW.GLFW_PRESS) {
                if (handleAction(key, mc)) {
                    if (key != GLFW.GLFW_KEY_Z && key != GLFW.GLFW_KEY_Y) {
                        heldKeys.put(key, INITIAL_DELAY);
                    }
                }
            } else if (action == GLFW.GLFW_RELEASE) {
                heldKeys.remove(key);
            }
        }

        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            if (heldKeys.isEmpty()) return;
            
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.screen != null) {
                heldKeys.clear();
                return;
            }

            for (var entry : new HashMap<>(heldKeys).entrySet()) {
                int key = entry.getKey();
                int cooldown = entry.getValue();

                if (!InputConstants.isKeyDown(mc.getWindow().getWindow(), key)) {
                    heldKeys.remove(key);
                    continue;
                }

                if (cooldown > 0) {
                    heldKeys.put(key, cooldown - 1);
                } else {
                    if (handleAction(key, mc)) {
                        heldKeys.put(key, REPEAT_DELAY);
                    } else {
                        heldKeys.remove(key);
                    }
                }
            }
        }

        private static boolean handleAction(int key, Minecraft mc) {
            long windowHandle = mc.getWindow().getWindow();
            boolean isCtrlDown = InputConstants.isKeyDown(windowHandle, GLFW.GLFW_KEY_LEFT_CONTROL) || 
                                 InputConstants.isKeyDown(windowHandle, GLFW.GLFW_KEY_RIGHT_CONTROL);
            boolean isAltDown = InputConstants.isKeyDown(windowHandle, GLFW.GLFW_KEY_LEFT_ALT) ||
                                InputConstants.isKeyDown(windowHandle, GLFW.GLFW_KEY_RIGHT_ALT);

            if (ClientGlobalSelection.currentMode == ClientGlobalSelection.SelectionMode.PASTE) {
                Direction facing = mc.player.getDirection();
                boolean handled = false;
                
                if (isAltDown) {
                    if (key == GLFW.GLFW_KEY_UP) { ClientGlobalSelection.tilingSpacingZ--; handled = true; }
                    else if (key == GLFW.GLFW_KEY_DOWN) { ClientGlobalSelection.tilingSpacingZ++; handled = true; }
                    else if (key == GLFW.GLFW_KEY_LEFT) { ClientGlobalSelection.tilingSpacingX--; handled = true; }
                    else if (key == GLFW.GLFW_KEY_RIGHT) { ClientGlobalSelection.tilingSpacingX++; handled = true; }
                    
                    if (handled) {
                        mc.player.displayClientMessage(Component.literal("Tiling Spacing: X=" + ClientGlobalSelection.tilingSpacingX + ", Z=" + ClientGlobalSelection.tilingSpacingZ).withStyle(ChatFormatting.GOLD), true);
                        return true;
                    }
                } else {
                    if (key == GLFW.GLFW_KEY_UP) { ClientGlobalSelection.patternOffset = ClientGlobalSelection.patternOffset.relative(facing); handled = true; }
                    else if (key == GLFW.GLFW_KEY_DOWN) { ClientGlobalSelection.patternOffset = ClientGlobalSelection.patternOffset.relative(facing.getOpposite()); handled = true; }
                    else if (key == GLFW.GLFW_KEY_LEFT) { ClientGlobalSelection.patternOffset = ClientGlobalSelection.patternOffset.relative(facing.getCounterClockWise()); handled = true; }
                    else if (key == GLFW.GLFW_KEY_RIGHT) { ClientGlobalSelection.patternOffset = ClientGlobalSelection.patternOffset.relative(facing.getClockWise()); handled = true; }
                    else if (key == GLFW.GLFW_KEY_PAGE_UP) { ClientGlobalSelection.patternOffset = ClientGlobalSelection.patternOffset.above(); handled = true; }
                    else if (key == GLFW.GLFW_KEY_PAGE_DOWN) { ClientGlobalSelection.patternOffset = ClientGlobalSelection.patternOffset.below(); handled = true; }

                    if (handled) {
                        BlockPos p = ClientGlobalSelection.patternOffset;
                        mc.player.displayClientMessage(Component.literal("Pattern Offset: " + p.getX() + ", " + p.getY() + ", " + p.getZ()).withStyle(ChatFormatting.AQUA), true);
                        return true;
                    }
                }
            }

            if (isCtrlDown) {
                if (key == GLFW.GLFW_KEY_Z) {
                    mc.getConnection().send(new ServerboundUndoPacket());
                    return true;
                } else if (key == GLFW.GLFW_KEY_Y) {
                    mc.getConnection().send(new ServerboundRedoPacket());
                    return true;
                } else if (key == GLFW.GLFW_KEY_X) {
                    toggleMode(ClientGlobalSelection.SelectionMode.CUT, "Cut (Select Area)");
                    return true;
                } else if (key == GLFW.GLFW_KEY_C) {
                    toggleMode(ClientGlobalSelection.SelectionMode.COPY, "Copy (Select Area)");
                    return true;
                } else if (key == GLFW.GLFW_KEY_V) {
                    if (ClientGlobalSelection.currentMode == ClientGlobalSelection.SelectionMode.PASTE) {
                        if (ClientClipboard.hasClipboard()) {
                            ClientClipboard.cycle();
                            int idx = ClientClipboard.getCurrentIndex() + 1;
                            int total = ClientClipboard.getHistorySize();
                            mc.player.displayClientMessage(Component.literal("Clipboard: " + idx + "/" + total).withStyle(ChatFormatting.YELLOW), true);
                        }
                    } else if (ClientClipboard.hasClipboard()) {
                        ClientGlobalSelection.setMode(ClientGlobalSelection.SelectionMode.PASTE);
                        mc.player.displayClientMessage(Component.literal("Mode: Paste (Ctrl+V to cycle)").withStyle(ChatFormatting.GREEN), true);
                    } else {
                        mc.player.displayClientMessage(Component.literal("Clipboard Empty").withStyle(ChatFormatting.RED), true);
                    }
                    return true;
                } else if (key == GLFW.GLFW_KEY_D) {
                    toggleMode(ClientGlobalSelection.SelectionMode.DECONSTRUCT, "Deconstruct (Select Area)");
                    return true;
                }
            }

            if (ClientGlobalSelection.isLocked && ClientGlobalSelection.lockedPos != null) {
                Direction facing = mc.player.getDirection();
                boolean handled = false;
                if (key == GLFW.GLFW_KEY_UP) { ClientGlobalSelection.lockedPos = ClientGlobalSelection.lockedPos.relative(facing); handled = true; }
                else if (key == GLFW.GLFW_KEY_DOWN) { ClientGlobalSelection.lockedPos = ClientGlobalSelection.lockedPos.relative(facing.getOpposite()); handled = true; }
                else if (key == GLFW.GLFW_KEY_LEFT) { ClientGlobalSelection.lockedPos = ClientGlobalSelection.lockedPos.relative(facing.getCounterClockWise()); handled = true; }
                else if (key == GLFW.GLFW_KEY_RIGHT) { ClientGlobalSelection.lockedPos = ClientGlobalSelection.lockedPos.relative(facing.getClockWise()); handled = true; }
                else if (key == GLFW.GLFW_KEY_PAGE_UP) { ClientGlobalSelection.lockedPos = ClientGlobalSelection.lockedPos.above(); handled = true; }
                else if (key == GLFW.GLFW_KEY_PAGE_DOWN) { ClientGlobalSelection.lockedPos = ClientGlobalSelection.lockedPos.below(); handled = true; }

                if (handled) {
                    BlockPos p = ClientGlobalSelection.lockedPos;
                    mc.player.displayClientMessage(Component.literal("Nudged to: " + p.getX() + ", " + p.getY() + ", " + p.getZ()).withStyle(ChatFormatting.YELLOW), true);
                    return true;
                }
            }

            if (key == GLFW.GLFW_KEY_H && ClientGlobalSelection.currentMode != ClientGlobalSelection.SelectionMode.NONE) {
                ClientGlobalSelection.isLocked = !ClientGlobalSelection.isLocked;
                if (ClientGlobalSelection.isLocked) {
                    mc.player.displayClientMessage(Component.literal("Selection Locked").withStyle(ChatFormatting.GOLD), true);
                } else {
                    ClientGlobalSelection.lockedPos = null;
                    mc.player.displayClientMessage(Component.literal("Selection Unlocked").withStyle(ChatFormatting.GREEN), true);
                }
                return true;
            }

            if (ClientGlobalSelection.currentMode == ClientGlobalSelection.SelectionMode.PASTE) {
                if (key == GLFW.GLFW_KEY_R) {
                    ClientClipboard.rotate();
                    mc.player.displayClientMessage(Component.literal("Rotated Clipboard").withStyle(ChatFormatting.AQUA), true);
                    return true;
                }
                if (key == GLFW.GLFW_KEY_F) {
                    if (isCtrlDown) {
                        ClientGlobalSelection.forceModeToggle = !ClientGlobalSelection.forceModeToggle;
                        mc.player.displayClientMessage(Component.literal("Force Mode: " + (ClientGlobalSelection.forceModeToggle ? "ON" : "OFF")).withStyle(ChatFormatting.GOLD), true);
                    } else {
                        ClientClipboard.flip();
                        mc.player.displayClientMessage(Component.literal("Flipped Clipboard").withStyle(ChatFormatting.AQUA), true);
                    }
                    return true;
                }
            }
            
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                if (ClientGlobalSelection.currentMode != ClientGlobalSelection.SelectionMode.NONE) {
                    ClientGlobalSelection.setMode(ClientGlobalSelection.SelectionMode.NONE);
                    mc.player.displayClientMessage(Component.literal("Selection Cancelled").withStyle(ChatFormatting.RED), true);
                    return true;
                }
            }

            return false;
        }

        private static void toggleMode(ClientGlobalSelection.SelectionMode mode, String name) {
            if (ClientGlobalSelection.currentMode == mode) {
                ClientGlobalSelection.setMode(ClientGlobalSelection.SelectionMode.NONE);
                Minecraft.getInstance().player.displayClientMessage(Component.literal("Mode: None").withStyle(ChatFormatting.GRAY), true);
            } else {
                ClientGlobalSelection.setMode(mode);
                Minecraft.getInstance().player.displayClientMessage(Component.literal("Mode: " + name).withStyle(ChatFormatting.GREEN), true);
            }
        }
    }
}