package com.example.ghostlib.block.entity;

import com.example.ghostlib.api.IDronePort;
import com.example.ghostlib.entity.DroneEntity;
import com.example.ghostlib.registry.ModBlockEntities;
import com.example.ghostlib.registry.ModEntities;
import com.example.ghostlib.util.GhostJobManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

public class DronePortBlockEntity extends BlockEntity implements IDronePort, MenuProvider, com.lowdragmc.lowdraglib2.gui.factory.IContainerUIHolder {
    private final ItemStackHandler inventory = new ItemStackHandler(9) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };

    public DronePortBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DRONE_PORT.get(), pos, state);
    }

    @Override
    public com.lowdragmc.lowdraglib2.gui.ui.ModularUI createUI(Player player) {
        return com.lowdragmc.lowdraglib2.gui.ui.ModularUI.of(com.lowdragmc.lowdraglib2.gui.ui.UI.empty(), player);
    }

    @Override
    public boolean isStillValid(Player player) {
        return !isRemoved();
    }

    public static void tick(Level level, BlockPos pos, BlockState state, DronePortBlockEntity be) {
        if (level.isClientSide) return;

        if (level.getGameTime() % 20 == 0) {
            be.trySpawnDrone();
        }
    }

    public ItemStackHandler getInventory() {
        return inventory;
    }

    public int getEnergy() {
        return 1000000; // Mock energy
    }

    @Override
    public boolean isValid() {
        return !isRemoved();
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Drone Port");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int windowId, Inventory playerInventory, Player player) {
        return new com.example.ghostlib.menu.DronePortMenu(com.example.ghostlib.registry.ModMenus.DRONE_PORT_MENU.get(), windowId, playerInventory, this);
    }

    private void trySpawnDrone() {
        if (level == null) return;
        GhostJobManager manager = GhostJobManager.get(level);
        GhostJobManager.Job job = manager.requestJob(worldPosition, UUID.randomUUID(), true);
        if (job == null) return;

        // Skip Halted ghosts
        GhostBlockEntity gbe = null;
        if (level.getBlockEntity(job.pos()) instanceof GhostBlockEntity foundGbe) {
            gbe = foundGbe;
            if (gbe.getCurrentState() == GhostBlockEntity.GhostState.MISSING_ITEMS) {
                manager.releaseJob(job.pos(), gbe.getAssignedTo());
                return;
            }
        }

        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (stack.getItem() == com.example.ghostlib.registry.ModItems.DRONE_SPAWN_EGG.get()) {
                // Deployment Check: Can fulfill?
                DroneEntity drone = new DroneEntity(ModEntities.DRONE.get(), level);
                drone.setPos(worldPosition.getX() + 0.5, worldPosition.getY() + 1.0, worldPosition.getZ() + 0.5);
                drone.setPort(worldPosition);
                
                boolean canFulfill = false;
                if (job.type() == GhostJobManager.JobType.CONSTRUCTION) {
                    ItemStack req = new ItemStack(job.targetAfter().getBlock().asItem());
                    // 1. Check port inventory first
                    ItemStack taken = extractItem(req, 1, true);
                    if (!taken.isEmpty()) {
                        canFulfill = true;
                    } else {
                        // 2. Scan logistics network (future feature, for now check nearby chests)
                        // This logic should eventually use a global network lookup.
                    }
                } else {
                    canFulfill = true;
                }

                if (canFulfill) {
                    stack.shrink(1);
                    UUID assignedId = gbe != null ? gbe.getAssignedTo() : UUID.randomUUID();
                    manager.reassignJob(job.pos(), assignedId, drone.getUUID());
                    drone.setInitialJob(job);
                    level.addFreshEntity(drone);
                } else {
                    UUID assignedId = gbe != null ? gbe.getAssignedTo() : UUID.randomUUID();
                    manager.releaseJob(job.pos(), assignedId);
                    
                    // Mark as halted if port can't fulfill
                    if (gbe != null) {
                        gbe.setState(GhostBlockEntity.GhostState.MISSING_ITEMS);
                    }
                }
                return;
            }
        }
        
        // No drone or can't fulfill
        UUID assignedId = gbe != null ? gbe.getAssignedTo() : UUID.randomUUID();
        manager.releaseJob(job.pos(), assignedId); 
    }

    @Override
    public int chargeDrone(int amount, boolean simulate) {
        return amount; // Port has infinite energy for now
    }

    @Override
    public ItemStack insertItem(ItemStack stack, boolean simulate) {
        for (int i = 0; i < inventory.getSlots(); i++) {
            stack = inventory.insertItem(i, stack, simulate);
            if (stack.isEmpty()) break;
        }
        return stack;
    }

    @Override
    public ItemStack extractItem(ItemStack stack, int amount, boolean simulate) {
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack inSlot = inventory.getStackInSlot(i);
            if (inSlot.is(stack.getItem())) {
                return inventory.extractItem(i, amount, simulate);
            }
        }
        return ItemStack.EMPTY;
    }
}