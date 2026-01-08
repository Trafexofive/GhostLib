package com.example.ghostlib.block.entity;

import com.example.ghostlib.multiblock.IMultiblockMember;
import com.example.ghostlib.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

public class DronePortMemberBlockEntity extends BlockEntity implements IMultiblockMember {
    
    private BlockPos controllerPos;

    public DronePortMemberBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DRONE_PORT_MEMBER.get(), pos, state);
    }

    @Override
    public void setControllerPos(BlockPos pos) {
        this.controllerPos = pos;
        setChanged();
    }

    @Override
    public Optional<BlockPos> getControllerPos() {
        return Optional.ofNullable(controllerPos);
    }

    public InteractionResult delegateInteraction(Player player, InteractionHand hand) {
        if (controllerPos != null && level.getBlockEntity(controllerPos) instanceof DronePortControllerBlockEntity controller) {
            return controller.handlePlayerInteraction(player, hand);
        }
        return InteractionResult.PASS;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (controllerPos != null) {
            tag.putLong("controllerPos", controllerPos.asLong());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("controllerPos")) {
            controllerPos = BlockPos.of(tag.getLong("controllerPos"));
        }
    }
}
