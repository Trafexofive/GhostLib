package com.example.ghostlib.multiblock;

import net.minecraft.core.BlockPos;

/**
 * Interface for the master block of a multiblock.
 */
public interface IMultiblockController {
    boolean validateStructure();
    void assemble();
    void disassemble();
    boolean isAssembled();
}
