package com.example.ghostlib.client.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.Registries;
import java.io.File;
import java.io.IOException;
import java.util.LinkedList;

public class ClientClipboard {
    private static final int MAX_HISTORY = 10;
    private static final LinkedList<CompoundTag> history = new LinkedList<>();
    private static int currentIndex = 0;
    private static final File CLIPBOARD_FILE = new File(Minecraft.getInstance().gameDirectory, "ghostlib_clipboard.nbt");
    private static boolean loaded = false;

    private static void ensureLoaded() {
        if (!loaded) {
            load();
            loaded = true;
        }
    }

    public static void setClipboard(CompoundTag tag) {
        ensureLoaded();
        if (tag == null || tag.isEmpty())
            return;
        if (!history.isEmpty() && history.getFirst().equals(tag)) {
            currentIndex = 0;
            return;
        }

        history.addFirst(tag.copy());
        if (history.size() > MAX_HISTORY) {
            history.removeLast();
        }
        currentIndex = 0;
        save();
    }

    public static CompoundTag getClipboard() {
        ensureLoaded();
        if (history.isEmpty())
            return null;
        if (currentIndex >= history.size())
            currentIndex = 0;
        return history.get(currentIndex);
    }

    public static boolean hasClipboard() {
        ensureLoaded();
        return !history.isEmpty();
    }

    public static void cycle() {
        ensureLoaded();
        if (history.isEmpty())
            return;
        currentIndex = (currentIndex + 1) % history.size();
    }

    public static int getHistorySize() {
        ensureLoaded();
        return history.size();
    }

    public static int getCurrentIndex() {
        return currentIndex;
    }

    private static void save() {
        try {
            CompoundTag root = new CompoundTag();
            ListTag list = new ListTag();
            for (CompoundTag tag : history) {
                list.add(tag);
            }
            root.put("History", list);
            NbtIo.writeCompressed(root, CLIPBOARD_FILE.toPath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void load() {
        if (!CLIPBOARD_FILE.exists()) return;
        try {
            CompoundTag root = NbtIo.readCompressed(CLIPBOARD_FILE.toPath(), NbtAccounter.unlimitedHeap());
            if (root.contains("History", Tag.TAG_LIST)) {
                ListTag list = root.getList("History", Tag.TAG_COMPOUND);
                history.clear();
                for (int i = 0; i < list.size(); i++) {
                    history.add(list.getCompound(i));
                }
                // Enforce limit just in case
                while (history.size() > MAX_HISTORY) {
                    history.removeLast();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void rotate() {
        ensureLoaded();
        CompoundTag tag = getClipboard();
        if (tag == null)
            return;

        CompoundTag newTag = tag.copy();
        ListTag pattern = newTag.getList("Pattern", 10);
        ListTag newPattern = new ListTag();

        int sizeX = newTag.getInt("SizeX");
        int sizeZ = newTag.getInt("SizeZ");

        // Rotate 90 CW: (x, z) -> (-z, x)
        // Realign: (SizeZ - 1 - z, x)

        var registries = Minecraft.getInstance().level.holderLookup(Registries.BLOCK);

        for (int i = 0; i < pattern.size(); i++) {
            CompoundTag block = pattern.getCompound(i);
            BlockPos rel = NbtUtils.readBlockPos(block, "Rel").orElse(BlockPos.ZERO);

            int newX = sizeZ - 1 - rel.getZ();
            int newZ = rel.getX();
            int newY = rel.getY();

            BlockState state = NbtUtils.readBlockState(registries, block.getCompound("State"));
            state = state.rotate(Rotation.CLOCKWISE_90);

            CompoundTag newBlock = new CompoundTag();
            newBlock.put("Rel", NbtUtils.writeBlockPos(new BlockPos(newX, newY, newZ)));
            newBlock.put("State", NbtUtils.writeBlockState(state));
            newPattern.add(newBlock);
        }

        newTag.put("Pattern", newPattern);
        newTag.putInt("SizeX", sizeZ);
        newTag.putInt("SizeZ", sizeX);

        history.set(currentIndex, newTag);
        save();

        // SYNC: Tell server about the rotation
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().getConnection().send(new com.example.ghostlib.network.payload.ServerboundUpdateBlueprintPacket(newTag));
        }
    }

    public static void flip() {
        ensureLoaded();
        CompoundTag tag = getClipboard();
        if (tag == null)
            return;

        CompoundTag newTag = tag.copy();
        ListTag pattern = newTag.getList("Pattern", 10);
        ListTag newPattern = new ListTag();

        int sizeX = newTag.getInt("SizeX");

        // Flip X axis (Mirror Left/Right)
        // (x, z) -> (SizeX - 1 - x, z)

        var registries = Minecraft.getInstance().level.holderLookup(Registries.BLOCK);

        for (int i = 0; i < pattern.size(); i++) {
            CompoundTag block = pattern.getCompound(i);
            BlockPos rel = NbtUtils.readBlockPos(block, "Rel").orElse(BlockPos.ZERO);

            int newX = sizeX - 1 - rel.getX();
            int newZ = rel.getZ();
            int newY = rel.getY();

            BlockState state = NbtUtils.readBlockState(registries, block.getCompound("State"));
            state = state.mirror(Mirror.LEFT_RIGHT);

            CompoundTag newBlock = new CompoundTag();
            newBlock.put("Rel", NbtUtils.writeBlockPos(new BlockPos(newX, newY, newZ)));
            newBlock.put("State", NbtUtils.writeBlockState(state));
            newPattern.add(newBlock);
        }

        newTag.put("Pattern", newPattern);
        // Size doesn't swap on flip

        history.set(currentIndex, newTag);
        save();

        // SYNC: Tell server about the flip
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().getConnection().send(new com.example.ghostlib.network.payload.ServerboundUpdateBlueprintPacket(newTag));
        }
    }
}
