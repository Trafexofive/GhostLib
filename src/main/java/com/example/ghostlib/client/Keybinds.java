package com.example.ghostlib.client;

import com.example.ghostlib.GhostLib;
import com.example.ghostlib.network.payload.ServerboundRedoPacket;
import com.example.ghostlib.network.payload.ServerboundUndoPacket;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

public class Keybinds {
    public static final KeyMapping UNDO_KEY = new KeyMapping("key.ghostlib.undo", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, "key.categories.ghostlib");
    public static final KeyMapping REDO_KEY = new KeyMapping("key.ghostlib.redo", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Y, "key.categories.ghostlib");

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(UNDO_KEY);
        event.register(REDO_KEY);
    }

    @EventBusSubscriber(modid = GhostLib.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
    public static class KeyInputHandler {
        @SubscribeEvent
        public static void onKeyInput(InputEvent.Key event) {
            if (Minecraft.getInstance().player == null || Minecraft.getInstance().screen != null) return;
            if (event.getAction() != GLFW.GLFW_PRESS) return;

            long windowHandle = Minecraft.getInstance().getWindow().getWindow();
            boolean isCtrlDown = InputConstants.isKeyDown(windowHandle, GLFW.GLFW_KEY_LEFT_CONTROL) || InputConstants.isKeyDown(windowHandle, GLFW.GLFW_KEY_RIGHT_CONTROL);

            if (isCtrlDown) {
                if (UNDO_KEY.isActiveAndMatches(InputConstants.getKey(event.getKey(), event.getScanCode()))) {
                    Minecraft.getInstance().getConnection().send(new ServerboundUndoPacket());
                } else if (REDO_KEY.isActiveAndMatches(InputConstants.getKey(event.getKey(), event.getScanCode()))) {
                    Minecraft.getInstance().getConnection().send(new ServerboundRedoPacket());
                }
            }
        }
    }
}