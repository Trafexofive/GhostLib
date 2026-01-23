package com.example.ghostlib.client;

import com.example.ghostlib.GhostLib;
import com.example.ghostlib.client.util.ClientClipboard;
import com.example.ghostlib.client.util.ClientGlobalSelection;
import com.example.ghostlib.network.payload.ServerboundRedoPacket;
import com.example.ghostlib.network.payload.ServerboundUndoPacket;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

public class Keybinds {
    public static final KeyMapping UNDO_KEY = new KeyMapping("key.ghostlib.undo", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, "key.categories.ghostlib");
    public static final KeyMapping REDO_KEY = new KeyMapping("key.ghostlib.redo", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Y, "key.categories.ghostlib");
    public static final KeyMapping CUT_KEY = new KeyMapping("key.ghostlib.cut", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_X, "key.categories.ghostlib");
    public static final KeyMapping COPY_KEY = new KeyMapping("key.ghostlib.copy", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, "key.categories.ghostlib");
    public static final KeyMapping PASTE_KEY = new KeyMapping("key.ghostlib.paste", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, "key.categories.ghostlib");
    public static final KeyMapping DELETE_KEY = new KeyMapping("key.ghostlib.delete", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_D, "key.categories.ghostlib"); // Ctrl+D for Deconstruct

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(UNDO_KEY);
        event.register(REDO_KEY);
        event.register(CUT_KEY);
        event.register(COPY_KEY);
        event.register(PASTE_KEY);
        event.register(DELETE_KEY);
    }

    @EventBusSubscriber(modid = GhostLib.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
    public static class KeyInputHandler {
        @SubscribeEvent
        public static void onKeyInput(InputEvent.Key event) {
            if (Minecraft.getInstance().player == null || Minecraft.getInstance().screen != null) return;
            if (event.getAction() != GLFW.GLFW_PRESS) return;

            long windowHandle = Minecraft.getInstance().getWindow().getWindow();
            boolean isCtrlDown = InputConstants.isKeyDown(windowHandle, GLFW.GLFW_KEY_LEFT_CONTROL) || InputConstants.isKeyDown(windowHandle, GLFW.GLFW_KEY_RIGHT_CONTROL);

            if (event.getKey() == GLFW.GLFW_KEY_ESCAPE) {
                if (ClientGlobalSelection.currentMode != ClientGlobalSelection.SelectionMode.NONE) {
                    ClientGlobalSelection.setMode(ClientGlobalSelection.SelectionMode.NONE);
                    Minecraft.getInstance().player.displayClientMessage(Component.literal("Selection Cancelled"), true);
                    return; // Don't propagate ESC if we handled it
                }
            }

            if (isCtrlDown) {
                if (event.getKey() == GLFW.GLFW_KEY_Z) {
                    Minecraft.getInstance().getConnection().send(new ServerboundUndoPacket());
                } else if (event.getKey() == GLFW.GLFW_KEY_Y) {
                    Minecraft.getInstance().getConnection().send(new ServerboundRedoPacket());
                } else if (event.getKey() == GLFW.GLFW_KEY_X) {
                    if (ClientGlobalSelection.currentMode == ClientGlobalSelection.SelectionMode.CUT) {
                        ClientGlobalSelection.setMode(ClientGlobalSelection.SelectionMode.NONE);
                        Minecraft.getInstance().player.displayClientMessage(Component.literal("Mode: None"), true);
                    } else {
                        ClientGlobalSelection.setMode(ClientGlobalSelection.SelectionMode.CUT);
                        Minecraft.getInstance().player.displayClientMessage(Component.literal("Mode: Cut (Select Area)"), true);
                    }
                } else if (event.getKey() == GLFW.GLFW_KEY_C) {
                    if (ClientGlobalSelection.currentMode == ClientGlobalSelection.SelectionMode.COPY) {
                        ClientGlobalSelection.setMode(ClientGlobalSelection.SelectionMode.NONE);
                        Minecraft.getInstance().player.displayClientMessage(Component.literal("Mode: None"), true);
                    } else {
                        ClientGlobalSelection.setMode(ClientGlobalSelection.SelectionMode.COPY);
                        Minecraft.getInstance().player.displayClientMessage(Component.literal("Mode: Copy (Select Area)"), true);
                    }
                } else if (event.getKey() == GLFW.GLFW_KEY_V) {
                    if (ClientGlobalSelection.currentMode == ClientGlobalSelection.SelectionMode.PASTE) {
                        ClientGlobalSelection.setMode(ClientGlobalSelection.SelectionMode.NONE);
                        Minecraft.getInstance().player.displayClientMessage(Component.literal("Mode: None"), true);
                    } else if (ClientClipboard.hasClipboard()) {
                        ClientGlobalSelection.setMode(ClientGlobalSelection.SelectionMode.PASTE);
                        Minecraft.getInstance().player.displayClientMessage(Component.literal("Mode: Paste"), true);
                    } else {
                        Minecraft.getInstance().player.displayClientMessage(Component.literal("Clipboard Empty"), true);
                    }
                } else if (event.getKey() == GLFW.GLFW_KEY_D) {
                    if (ClientGlobalSelection.currentMode == ClientGlobalSelection.SelectionMode.DECONSTRUCT) {
                        ClientGlobalSelection.setMode(ClientGlobalSelection.SelectionMode.NONE);
                        Minecraft.getInstance().player.displayClientMessage(Component.literal("Mode: None"), true);
                    } else {
                        ClientGlobalSelection.setMode(ClientGlobalSelection.SelectionMode.DECONSTRUCT);
                        Minecraft.getInstance().player.displayClientMessage(Component.literal("Mode: Deconstruct (Select Area)"), true);
                    }
                }
            }
        }
    }
}
