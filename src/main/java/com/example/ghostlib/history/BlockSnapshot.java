package com.example.ghostlib.history;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

/**
 * A high-fidelity snapshot of a block's physical state.
 */
public record BlockSnapshot(BlockState state, CompoundTag nbt) {
    public static final BlockSnapshot AIR = new BlockSnapshot(Blocks.AIR.defaultBlockState(), null);

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.put("State", NbtUtils.writeBlockState(state));
        if (nbt != null && !nbt.isEmpty()) {
            tag.put("NBT", nbt);
        }
        return tag;
    }

    public static BlockSnapshot load(CompoundTag tag, HolderLookup.Provider registries) {
        BlockState state = NbtUtils.readBlockState(registries.lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK), tag.getCompound("State"));
        CompoundTag nbt = tag.contains("NBT") ? tag.getCompound("NBT") : null;
        return new BlockSnapshot(state, nbt);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BlockSnapshot that = (BlockSnapshot) o;
        return Objects.equals(state, that.state) && Objects.equals(nbt, that.nbt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(state, nbt);
    }
}
