package com.example.ghostlib.util;

import com.example.ghostlib.GhostLib;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.neoforged.fml.loading.FMLPaths;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Handles the persistence of blueprints to the local file system.
 * Blueprints are stored as compressed NBT files in the config/ghostlib/blueprints directory.
 */
public class BlueprintManager {
    
    private static final Path BLUEPRINT_DIR = FMLPaths.CONFIGDIR.get().resolve("ghostlib/blueprints");

    static {
        File dir = BLUEPRINT_DIR.toFile();
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    /**
     * Saves a blueprint (CompoundTag) to disk with the given name.
     * @param name The file name (without extension).
     * @param data The NBT data representing the blueprint pattern.
     */
    public static void saveBlueprint(String name, CompoundTag data) {
        try {
            File file = BLUEPRINT_DIR.resolve(name + ".nbt").toFile();
            NbtIo.writeCompressed(data, file.toPath());
            GhostLib.LOGGER.info("Saved blueprint: " + name);
        } catch (IOException e) {
            GhostLib.LOGGER.error("Failed to save blueprint: " + name, e);
        }
    }

    /**
     * Loads a blueprint from disk.
     * @param name The name of the blueprint to load.
     * @return The CompoundTag containing the pattern, or null if not found/error.
     */
    public static CompoundTag loadBlueprint(String name) {
        try {
            File file = BLUEPRINT_DIR.resolve(name + ".nbt").toFile();
            if (file.exists()) {
                return NbtIo.readCompressed(file.toPath(), net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            }
        } catch (IOException e) {
            GhostLib.LOGGER.error("Failed to load blueprint: " + name, e);
        }
        return null;
    }
}
