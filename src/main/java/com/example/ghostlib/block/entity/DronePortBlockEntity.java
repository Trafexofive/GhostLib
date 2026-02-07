package com.example.ghostlib.block.entity;

import com.example.ghostlib.api.IDronePort;
import com.example.ghostlib.entity.DroneEntity;
import com.example.ghostlib.registry.ModBlockEntities;
import com.example.ghostlib.registry.ModEntities;
import com.example.ghostlib.util.GhostJobManager;
import com.example.ghostlib.util.GhostGUI;
import com.example.ghostlib.util.LayoutUtils;
import com.example.ghostlib.util.LogisticsNetworkManager;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ProgressBar;
import com.lowdragmc.lowdraglib2.gui.ui.elements.inventory.InventorySlots;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.MCSprites;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
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
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

public class DronePortBlockEntity extends BlockEntity implements IDronePort, net.minecraft.world.MenuProvider, com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType.BlockUI, com.lowdragmc.lowdraglib2.gui.factory.IContainerUIHolder {
    private final ItemStackHandler inventory = new ItemStackHandler(9) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };
    
    private final EnergyStorage energyStorage = new EnergyStorage(1000000, 50000, 50000); // 50k FE/t

    public DronePortBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DRONE_PORT.get(), pos, state);
    }

    @Override
    public ModularUI createUI(Player player) {
        System.out.println("DronePortBlockEntity: Creating UI for " + player.getName().getString());
        UI ui = UI.empty();
        ui.getRootElement().layout(l -> LayoutUtils.apply(l, 0f, 0f, 176f, 166f));
        ui.getRootElement().style(s -> s.background(MCSprites.RECT));
        
        ui.getRootElement().addChild(new Label().setValue(getDisplayName()).layout(l -> LayoutUtils.margin(l, 5f, 0f, 5f, 0f)));
        
        for (int i = 0; i < 3; i++) {
            final int row = i;
            for (int j = 0; j < 3; j++) {
                final int col = j;
                int index = i * 3 + j;
                ui.getRootElement().addChild(new ItemSlot().bind(inventory, index).layout(l -> LayoutUtils.apply(l, 62f + col * 18f, 17f + row * 18f, 18f, 18f)));
            }
        }
        
        ui.getRootElement().addChild(new ProgressBar()
                .bindDataSource(GhostGUI.supplier(() -> (float) energyStorage.getEnergyStored() / energyStorage.getMaxEnergyStored()))
                .layout(l -> LayoutUtils.apply(l, 10f, 17f, 10f, 54f)));
        
        ui.getRootElement().addChild(new InventorySlots().layout(l -> LayoutUtils.apply(l, 8f, 84f, 160f, 76f)));
        return ModularUI.of(ui, player);
    }

    @Override
    public ModularUI createUI(com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType.BlockUIHolder holder) {
        return createUI(holder.player);
    }

    @Override
    public boolean isStillValid(Player player) {
        return !isRemoved();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            LogisticsNetworkManager.get(level).joinOrCreateNetwork(worldPosition, level);
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide) {
            LogisticsNetworkManager.get(level).leaveNetwork(worldPosition);
        }
        super.setRemoved();
    }

    public static void tick(Level level, BlockPos pos, BlockState state, DronePortBlockEntity be) {
        if (level.isClientSide) return;

        // RAPID CHARGING: Check every 2 ticks, pull up to 50,000 FE
        // Footprint scan: Check 4 blocks down
        if (level.getGameTime() % 2 == 0) {
            IEnergyStorage floor = null;
            for (int i = 1; i <= 4; i++) {
                BlockPos p = pos.below(i);
                floor = level.getCapability(Capabilities.EnergyStorage.BLOCK, p, Direction.UP);
                if (floor != null) break;
            }

            if (floor != null && floor.canExtract()) {
                int toPull = Math.min(be.energyStorage.getMaxEnergyStored() - be.energyStorage.getEnergyStored(), 50000);
                int extracted = floor.extractEnergy(toPull, false);
                be.energyStorage.receiveEnergy(extracted, false);
                if (extracted > 0) {
                    be.setChanged();
                }
            }
        }

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
        return new com.example.ghostlib.menu.DronePortMenu((MenuType)com.example.ghostlib.registry.ModMenus.DRONE_PORT_MENU.get(), windowId, playerInventory, this);
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
                    // 1. Check local inventory
                    ItemStack taken = extractItem(req, 1, true);
                    if (!taken.isEmpty()) {
                        canFulfill = true;
                    } else {
                        // 2. Check Logistics Network
                        LogisticsNetworkManager networkManager = LogisticsNetworkManager.get(level);
                        if (networkManager != null) {
                            Integer networkId = networkManager.getNetworkId(worldPosition);
                            if (networkId != null) {
                                for (BlockPos memberPos : networkManager.getNetworkMembers(networkId)) {
                                    if (level.isLoaded(memberPos)) {
                                        net.neoforged.neoforge.items.IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, memberPos, null);
                                        if (handler != null) {
                                            for (int j = 0; j < handler.getSlots(); j++) {
                                                if (handler.getStackInSlot(j).is(req.getItem())) {
                                                    canFulfill = true;
                                                    break;
                                                }
                                            }
                                        }
                                    }
                                    if (canFulfill) break;
                                }
                            }
                        }
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

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Inventory", inventory.serializeNBT(registries));
        tag.putInt("Energy", energyStorage.getEnergyStored());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Inventory")) inventory.deserializeNBT(registries, tag.getCompound("Inventory"));
        if (tag.contains("Energy")) energyStorage.receiveEnergy(tag.getInt("Energy"), false);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(net.minecraft.network.Connection net, net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket pkt, HolderLookup.Provider lookupProvider) {
        if (pkt.getTag() != null) loadAdditional(pkt.getTag(), lookupProvider);
    }
}