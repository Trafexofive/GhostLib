package com.example.ghostlib.block.entity;

import com.example.ghostlib.api.IDronePort;
import com.example.ghostlib.entity.DroneEntity;
import com.example.ghostlib.registry.ModBlockEntities;
import com.example.ghostlib.registry.ModEntities;
import com.example.ghostlib.util.GhostJobManager;
import com.example.ghostlib.util.GhostGUI;
import com.example.ghostlib.util.LayoutUtils;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ProgressBar;
import com.lowdragmc.lowdraglib2.gui.ui.elements.inventory.InventorySlots;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.items.ItemStackHandler;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

public class DronePortBlockEntity extends BlockEntity implements IDronePort, MenuProvider, com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType.BlockUI, com.lowdragmc.lowdraglib2.gui.factory.IContainerUIHolder {
    private final ItemStackHandler inventory = new ItemStackHandler(9) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };
    
    private final EnergyStorage energyStorage = new EnergyStorage(1000000, 10000, 10000);

    public DronePortBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DRONE_PORT.get(), pos, state);
    }

    @Override
    public com.lowdragmc.lowdraglib2.gui.ui.ModularUI createUI(Player player) {
        UI ui = UI.empty();
        ui.getRootElement().addChild(new Label().setValue(getDisplayName()).layout(l -> LayoutUtils.margin(l, 5f, 0f, 5f, 0f)));
        
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                int index = i * 3 + j;
                float x = 62f + j * 18f;
                float y = 17f + i * 18f;
                ui.getRootElement().addChild(new ItemSlot().bind(inventory, index).layout(l -> { l.left(x); l.top(y); }));
            }
        }
        
        ui.getRootElement().addChild(new ProgressBar()
                .bindDataSource(GhostGUI.supplier(() -> (float) energyStorage.getEnergyStored() / energyStorage.getMaxEnergyStored()))
                .layout(l -> LayoutUtils.apply(l, 10f, 17f, 10f, 54f)));
        
        ui.getRootElement().addChild(new InventorySlots().layout(l -> { l.bottom(5f); l.left(8f); }));
        return ModularUI.of(ui, player);
    }

    @Override
    public com.lowdragmc.lowdraglib2.gui.ui.ModularUI createUI(com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType.BlockUIHolder holder) {
        return createUI(holder.player);
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

    public EnergyStorage getEnergyStorage() {
        return energyStorage;
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
        return new com.example.ghostlib.menu.DronePortMenu((MenuType<com.lowdragmc.lowdraglib2.gui.holder.ModularUIContainerMenu>)com.example.ghostlib.registry.ModMenus.DRONE_PORT_MENU.get(), windowId, playerInventory, this);
    }

    private void trySpawnDrone() {
        if (level == null) return;
        GhostJobManager manager = GhostJobManager.get(level);
        
        UUID portId = UUID.nameUUIDFromBytes(worldPosition.toString().getBytes());
        GhostJobManager.Job job = manager.requestJob(worldPosition, portId, true);
        if (job == null) return;

        GhostBlockEntity gbe = null;
        if (level.getBlockEntity(job.pos()) instanceof GhostBlockEntity foundGbe) {
            gbe = foundGbe;
            if (gbe.getCurrentState() == GhostBlockEntity.GhostState.MISSING_ITEMS) {
                manager.releaseJob(job.pos(), portId);
                return;
            }
        }

        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (stack.getItem() == com.example.ghostlib.registry.ModItems.DRONE_SPAWN_EGG.get()) {
                DroneEntity drone = new DroneEntity(ModEntities.DRONE.get(), level);
                drone.setPos(worldPosition.getX() + 0.5, worldPosition.getY() + 1.0, worldPosition.getZ() + 0.5);
                drone.setPort(worldPosition);
                
                boolean canFulfill = false;
                if (job.type() == GhostJobManager.JobType.CONSTRUCTION) {
                    ItemStack req = new ItemStack(job.targetAfter().getBlock().asItem());
                    ItemStack taken = extractItem(req, 1, true);
                    if (!taken.isEmpty()) {
                        canFulfill = true;
                    }
                } else {
                    canFulfill = true;
                }

                if (canFulfill) {
                    stack.shrink(1);
                    UUID assignedId = gbe != null ? gbe.getAssignedTo() : portId;
                    manager.reassignJob(job.pos(), assignedId, drone.getUUID());
                    drone.setInitialJob(job);
                    level.addFreshEntity(drone);
                } else {
                    UUID assignedId = gbe != null ? gbe.getAssignedTo() : portId;
                    manager.releaseJob(job.pos(), assignedId);
                    if (gbe != null) {
                        gbe.setState(GhostBlockEntity.GhostState.MISSING_ITEMS);
                    }
                }
                return;
            }
        }
        manager.releaseJob(job.pos(), portId); 
    }

    @Override
    public int chargeDrone(int amount, boolean simulate) {
        return energyStorage.extractEnergy(amount, simulate);
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
