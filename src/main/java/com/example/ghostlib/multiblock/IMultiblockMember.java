package com.example.ghostlib.multiblock;

import net.minecraft.core.BlockPos;
import java.util.Optional;

/**
 * Interface for blocks that belong to a multiblock structure.
 */
public interface IMultiblockMember {
    void setControllerPos(BlockPos pos);
    Optional<BlockPos> getControllerPos();
    default boolean isPartofMultiblock() {
        return getControllerPos().isPresent();
    }
}
