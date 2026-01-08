package com.example.ghostlib.block.entity;

import com.example.ghostlib.multiblock.IMultiblockController;
import com.example.ghostlib.registry.ModBlockEntities;
import com.example.ghostlib.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class DronePortControllerBlockEntity extends BlockEntity implements com.example.ghostlib.multiblock.IMultiblockController, com.example.voltlink.api.IVoltReceiver {
    
    private final ItemStackHandler droneStorage = new ItemStackHandler(64) {
        @Override
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            return stack.is(ModItems.DRONE_SPAWN_EGG.get());
        }
    };

    private final EnergyStorage energyStorage = new EnergyStorage(com.example.ghostlib.config.GhostLibConfig.PORT_ENERGY_CAPACITY, 10000, 10000);

    private int activationRange = com.example.ghostlib.config.GhostLibConfig.PORT_ACTIVATION_RANGE;
    private int maxActiveDrones = com.example.ghostlib.config.GhostLibConfig.PORT_MAX_ACTIVE_DRONES;
    private final List<UUID> activeDrones = new ArrayList<>();
    private boolean isAssembled = false;

    public DronePortControllerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DRONE_PORT_CONTROLLER.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            com.example.voltlink.network.GridManager.get(level).subscribe(this, level);
            // ADD NODE TO GRID FOR VISIBLE WIRE
            com.example.voltlink.network.GridManager.get(level).addNode(getBlockPos(), level);
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide) {
            com.example.voltlink.network.GridManager.get(level).unsubscribe(this, level);
            com.example.voltlink.network.GridManager.get(level).removeNode(getBlockPos(), level);
        }
        super.setRemoved();
    }

    @Override
    public boolean validateStructure() {
        // Validate 3x3x3 cube of 'drone_port_member' with this controller in center-bottom
        BlockPos base = getBlockPos().offset(-1, 0, -1);
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 3; x++) {
                for (int z = 0; z < 3; z++) {
                    BlockPos p = base.offset(x, y, z);
                    if (p.equals(getBlockPos())) continue;
                    if (!(level.getBlockState(p).getBlock() instanceof com.example.ghostlib.block.DronePortMemberBlock)) return false;
                }
            }
        }
        return true; 
    }

    @Override
    public void assemble() {
        this.isAssembled = true;
        
        // Explicitly register with Grid upon assembly to ensure wire appears
        if (level != null && !level.isClientSide) {
            com.example.voltlink.network.GridManager.get(level).addNode(getBlockPos(), level);
        }

        BlockPos base = getBlockPos().offset(-1, 0, -1);
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 3; x++) {
                for (int z = 0; z < 3; z++) {
                    BlockPos p = base.offset(x, y, z);
                    if (level.getBlockEntity(p) instanceof DronePortMemberBlockEntity member) {
                        member.setControllerPos(getBlockPos());
                    }
                }
            }
        }
        setChanged();
    }

    @Override
    public long getVoltDemand() {
        return energyStorage.getEnergyStored() < energyStorage.getMaxEnergyStored() ? 1000 : 0;
    }

    @Override
    public void onVoltReceive(long amount) {
        this.energyStorage.receiveEnergy((int) amount, false);
    }

    @Override
    public BlockPos getVoltPos() {
        return getBlockPos();
    }

    public static void tick(Level level, BlockPos pos, BlockState state, DronePortControllerBlockEntity be) {
        if (level.isClientSide) return;
        be.manageSwarm();
    }

    private void manageSwarm() {
        if (!isAssembled || energyStorage.getEnergyStored() < com.example.ghostlib.config.GhostLibConfig.PORT_ENERGY_PER_SPAWN) return;
        
        if (level.getGameTime() % 100 == 0) {
            activeDrones.removeIf(uuid -> {
                if (level instanceof net.minecraft.server.level.ServerLevel sl) {
                    return sl.getEntity(uuid) == null;
                }
                return true;
            });
        }

        if (activeDrones.size() < maxActiveDrones) {
            if (hasJobInHive()) {
                ItemStack droneItem = ItemStack.EMPTY;
                for (int i = 0; i < droneStorage.getSlots(); i++) {
                    if (!droneStorage.getStackInSlot(i).isEmpty()) {
                        droneItem = droneStorage.extractItem(i, 1, false);
                        break;
                    }
                }

                if (!droneItem.isEmpty()) {
                    spawnDrone();
                    energyStorage.extractEnergy(com.example.ghostlib.config.GhostLibConfig.PORT_ENERGY_PER_SPAWN, false);
                }
            }
        }
    }

    private boolean hasJobInHive() {
        // Local Check First
        if (com.example.ghostlib.util.GhostJobManager.get(level).hasAvailableJob(getBlockPos(), activationRange)) return true;

        // Grid Check
        com.example.voltlink.network.GridManager.PowerIsland island = com.example.voltlink.network.GridManager.get(level).getIsland(getBlockPos());
        if (island != null) {
            synchronized (island.receivers) {
                for (com.example.voltlink.api.IVoltReceiver r : island.receivers) {
                    if (r instanceof DronePortControllerBlockEntity otherPort && !otherPort.getBlockPos().equals(getBlockPos())) {
                        if (com.example.ghostlib.util.GhostJobManager.get(level).hasAvailableJob(otherPort.getBlockPos(), otherPort.activationRange)) return true;
                    }
                }
            }
        }
        return false;
    }

    private void spawnDrone() {
        if (level instanceof net.minecraft.server.level.ServerLevel sl) {
            com.example.ghostlib.entity.PortDroneEntity drone = new com.example.ghostlib.entity.PortDroneEntity(com.example.ghostlib.registry.ModEntities.PORT_DRONE.get(), level);
            drone.setPos(getBlockPos().getX() + 0.5, getBlockPos().getY() + 3.5, getBlockPos().getZ() + 0.5);
            drone.setHomePos(getBlockPos());
            sl.addFreshEntity(drone);
            activeDrones.add(drone.getUUID());
        }
    }

    public InteractionResult handlePlayerInteraction(Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (held.isEmpty()) {
            if (!player.level().isClientSide) {
                player.displayClientMessage(net.minecraft.network.chat.Component.literal("--- Drone Port Status ---").withStyle(net.minecraft.ChatFormatting.BLUE), false);
                player.displayClientMessage(net.minecraft.network.chat.Component.literal("Energy: ").withStyle(net.minecraft.ChatFormatting.GRAY)
                        .append(net.minecraft.network.chat.Component.literal(String.format("%,d", energyStorage.getEnergyStored()) + " / " + energyStorage.getMaxEnergyStored() + " FE").withStyle(net.minecraft.ChatFormatting.GOLD)), false);
                int dronesCount = 0;
                for (int i = 0; i < droneStorage.getSlots(); i++) dronesCount += droneStorage.getStackInSlot(i).getCount();
                player.displayClientMessage(net.minecraft.network.chat.Component.literal("Hangar: ").withStyle(net.minecraft.ChatFormatting.GRAY)
                        .append(net.minecraft.network.chat.Component.literal(dronesCount + " / 64 Drones").withStyle(net.minecraft.ChatFormatting.AQUA)), false);
                player.displayClientMessage(net.minecraft.network.chat.Component.literal("Active: ").withStyle(net.minecraft.ChatFormatting.GRAY)
                        .append(net.minecraft.network.chat.Component.literal(activeDrones.size() + " / " + maxActiveDrones).withStyle(net.minecraft.ChatFormatting.WHITE)), false);
            }
            return InteractionResult.SUCCESS;
        }
        if (held.is(ModItems.DRONE_SPAWN_EGG.get())) {
            ItemStack toInsert = held.copyWithCount(player.isCrouching() ? held.getCount() : 1);
            ItemStack remaining = droneStorage.insertItem(0, toInsert, false);
            held.setCount(remaining.getCount() + (player.isCrouching() ? 0 : (held.getCount() - 1)));
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @Override
    public void disassemble() {
        this.isAssembled = false;
        setChanged();
    }

    @Override
    public boolean isAssembled() {
        return isAssembled;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("droneStorage", droneStorage.serializeNBT(registries));
        tag.putInt("energy", energyStorage.getEnergyStored());
        tag.putBoolean("assembled", isAssembled);
        tag.putInt("range", activationRange);
        tag.putInt("maxActive", maxActiveDrones);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        droneStorage.deserializeNBT(registries, tag.getCompound("droneStorage"));
        energyStorage.receiveEnergy(tag.getInt("energy"), false);
        isAssembled = tag.getBoolean("assembled");
        activationRange = tag.getInt("range");
        maxActiveDrones = tag.getInt("maxActive");
    }

    public ItemStackHandler getDroneStorage() { return droneStorage; }
    public EnergyStorage getEnergyStorage() { return energyStorage; }
}
