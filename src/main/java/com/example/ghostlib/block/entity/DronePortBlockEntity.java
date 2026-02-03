package com.example.ghostlib.block.entity;

import com.example.ghostlib.registry.ModBlockEntities;
import com.example.ghostlib.api.IDronePort;
import com.example.ghostlib.util.LogisticsNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.ItemStackHandler;

import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;

public class DronePortBlockEntity extends BlockEntity implements IDronePort, MenuProvider {
    private final EnergyStorage energy = new EnergyStorage(1000000, 10000, 10000);
    private boolean isFormed = false;
    private int checkTimer = 0;

    protected final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case 0 -> energy.getEnergyStored();
                case 1 -> isFormed ? 1 : 0;
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            if (index == 1) isFormed = value != 0;
        }

        @Override
        public int getCount() {
            return 2;
        }
    };

    private final ItemStackHandler inventory = new ItemStackHandler(27) {
        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return stack.is(com.example.ghostlib.registry.ModItems.DRONE_SPAWN_EGG.get());
        }

        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };

    public DronePortBlockEntity(BlockPos pos, BlockState state) {
        super(com.example.ghostlib.registry.ModBlockEntities.DRONE_PORT.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            // Default to network 1 for now
            LogisticsNetworkManager.get(level).joinNetwork(worldPosition, 1);
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide) {
            LogisticsNetworkManager.get(level).leaveNetwork(worldPosition);
        }
        super.setRemoved();
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Drone Port");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        // We will create the menu class next
        return new com.example.ghostlib.menu.DronePortMenu(containerId, inventory, this, data);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, DronePortBlockEntity be) {
        if (level.isClientSide) return;

        if (be.checkTimer++ >= 40) {
            be.checkTimer = 0;
            be.validateStructure();
        }

        if (be.isFormed) {
            // 1. Charging logic: Pull from floor below the multiblock
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    BlockPos floorPos = pos.offset(x, -3, z);
                    net.neoforged.neoforge.capabilities.BlockCapabilityCache<net.neoforged.neoforge.energy.IEnergyStorage, net.minecraft.core.Direction> cache = 
                        net.neoforged.neoforge.capabilities.BlockCapabilityCache.create(
                            net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,
                            (net.minecraft.server.level.ServerLevel)level, floorPos, net.minecraft.core.Direction.UP);
                    
                    net.neoforged.neoforge.energy.IEnergyStorage floorEnergy = cache.getCapability();
                    if (floorEnergy != null && floorEnergy.canExtract()) {
                        int space = be.energy.getMaxEnergyStored() - be.energy.getEnergyStored();
                        if (space > 0) {
                            int pulled = floorEnergy.extractEnergy(Math.min(2000, space), false);
                            be.energy.receiveEnergy(pulled, false);
                            if (pulled > 0) be.setChanged();
                        }
                    }
                }
            }

            // 2. Auto-deployment logic
            if (level.getGameTime() % 5 == 0 && be.energy.getEnergyStored() > 5000) {
                be.tryDeployDrone();
            }
        }
    }
    
    // We'll hook into tryDeployDrone
    private void tryDeployDrone() {
        if (level == null || level.isClientSide) return;

        // Check if there are jobs in range (64 blocks)
        if (!com.example.ghostlib.util.GhostJobManager.get(level).hasAvailableJob(worldPosition, 64)) return;

        int deployedThisTick = 0;
        while (deployedThisTick < 3) {
            // Check current drone count assigned to this port
            long count = java.util.stream.StreamSupport.stream(((net.minecraft.server.level.ServerLevel)level).getEntities().getAll().spliterator(), false)
                .filter(e -> e instanceof com.example.ghostlib.entity.DroneEntity d && 
                             d.getMode() == com.example.ghostlib.entity.DroneEntity.DroneMode.PORT && 
                             d.getPortPos().isPresent() && d.getPortPos().get().equals(worldPosition))
                .count();

            if (count < 10) { // Max 10 drones per port
                boolean found = false;
                for (int i = 0; i < inventory.getSlots(); i++) {
                    ItemStack stack = inventory.getStackInSlot(i);
                    if (!stack.isEmpty() && stack.is(com.example.ghostlib.registry.ModItems.DRONE_SPAWN_EGG.get())) {
                        // Deploy!
                        com.example.ghostlib.entity.DroneEntity drone = new com.example.ghostlib.entity.DroneEntity(com.example.ghostlib.registry.ModEntities.DRONE.get(), level);
                        drone.setPos(worldPosition.getX() + 0.5, worldPosition.getY() + 1.2, worldPosition.getZ() + 0.5);
                        drone.setPort(worldPosition);
                        level.addFreshEntity(drone);
                        
                        inventory.extractItem(i, 1, false);
                        energy.extractEnergy(1000, false);
                        level.playSound(null, worldPosition, net.minecraft.sounds.SoundEvents.BEACON_POWER_SELECT, net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, 1.5f);
                        deployedThisTick++;
                        found = true;
                        com.example.ghostlib.util.GhostLogger.multiblock("Drone Port at " + worldPosition + " deployed drone. Total active: " + (count + 1));
                        break;
                    }
                }
                if (!found) break;
            } else {
                break;
            }
        }
    }

    @Override
    public int chargeDrone(int amount, boolean simulate) {
        return energy.extractEnergy(amount, simulate);
    }

    @Override
    public ItemStack extractItem(ItemStack prototype, int amount, boolean simulate) {
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (!stack.isEmpty() && stack.is(prototype.getItem())) {
                return inventory.extractItem(i, amount, simulate);
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack insertItem(ItemStack stack, boolean simulate) {
        for (int i = 0; i < inventory.getSlots(); i++) {
            stack = inventory.insertItem(i, stack, simulate);
            if (stack.isEmpty()) return ItemStack.EMPTY;
        }
        return stack;
    }

    private void validateStructure() {
        if (level == null || level.isClientSide) return;
        boolean wasFormed = isFormed;
        isFormed = true;

        // Standard 3x3x3 Multiblock Logic
        // This controller is the TOP-CENTER block.
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.block.Block> casingKey = 
            net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BLOCK, 
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("factorycore", "machine_casing"));

        for (int x = -1; x <= 1; x++) {
            for (int y = -2; y <= 0; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && y == 0 && z == 0) continue; // Skip controller itself

                    BlockPos p = worldPosition.offset(x, y, z);
                    BlockState s = level.getBlockState(p);
                    
                    if (!s.is(casingKey)) {
                        // Accept solid blocks as fallback if casing not found, but AIR is a hard fail
                        if (s.isAir()) {
                            isFormed = false;
                            break;
                        }
                    }
                }
                if (!isFormed) break;
            }
            if (!isFormed) break;
        }

        if (isFormed != wasFormed) {
            setChanged();
            if (isFormed) {
                level.playSound(null, worldPosition, net.minecraft.sounds.SoundEvents.BEACON_ACTIVATE, net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, 1.0f);
            }
        }
    }

    @Override
    public boolean isValid() {
        return !isRemoved() && isFormed;
    }
    
    public IEnergyStorage getEnergy() { return energy; }
    public ItemStackHandler getInventory() { return inventory; }
}
