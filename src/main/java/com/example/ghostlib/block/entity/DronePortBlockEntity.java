package com.example.ghostlib.block.entity;

import com.example.ghostlib.GhostLib;
import com.example.ghostlib.api.IDronePort;
import com.example.ghostlib.config.GhostLibConfig;
import com.example.ghostlib.entity.DroneEntity;
import com.example.ghostlib.registry.ModBlockEntities;
import com.example.ghostlib.registry.ModEntities;
import com.example.ghostlib.registry.ModItems;
import com.example.ghostlib.util.GhostJobManager;
import com.example.ghostlib.util.GhostGUI;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ProgressBar;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Drone Port block entity. Manages drone spawning, energy, and item storage.
 *
 * <h2>Spawn flow</h2>
 * <ol>
 *   <li>Every 20 t, if energy ≥ {@code ENERGY_PER_SPAWN} and active drones ≤ {@code MAX_DRONES},
 *       the port requests a job from {@link GhostJobManager} using its own deterministic UUID.</li>
 *   <li>If a job is found and items exist (locally or in the logistics network),
 *       a drone egg is consumed, the job is re-assigned from {@code portId → drone.UUID},
 *       and the entity is spawned.</li>
 *   <li>If items are unavailable, the job is released and the ghost is marked MISSING_ITEMS.</li>
 * </ol>
 *
 * <h2>reassignJob invariant</h2>
 * The port claims the job with {@code portId = UUID.nameUUIDFromBytes(pos.toString())} in
 * {@code requestJob}. This same {@code portId} is always passed as {@code oldId} in
 * {@code reassignJob} so the ConcurrentHashMap replace is guaranteed to succeed.
 */
public class DronePortBlockEntity extends BlockEntity
        implements IDronePort,
                   net.minecraft.world.MenuProvider,
                   com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType.BlockUI,
                   com.lowdragmc.lowdraglib2.gui.factory.IContainerUIHolder {

    // ── Constants ─────────────────────────────────────────────────────────────

    /** FE cost deducted from the port per drone spawn. Prevents free infinite spawning. */
    private static final int ENERGY_PER_SPAWN   = 5_000;
    /** Maximum drones this port will keep alive simultaneously. */
    private static final int MAX_ACTIVE_DRONES  = 8;
    /** Tick interval for drone spawn attempts. */
    private static final int SPAWN_INTERVAL     = 20;

    // ── Storage ───────────────────────────────────────────────────────────────

    private final ItemStackHandler inventory = new ItemStackHandler(9) {
        @Override protected void onContentsChanged(int slot) { setChanged(); }
    };

    private final EnergyStorage energyStorage = new EnergyStorage(
            GhostLibConfig.PORT_ENERGY_CAPACITY,
            GhostLibConfig.PORT_ENERGY_TRANSFER,
            GhostLibConfig.PORT_ENERGY_TRANSFER);

    /** Active drone count tracked by spawn/recall events. Not persisted — resets on restart. */
    private int activeDroneCount = 0;

    // ── Identity ──────────────────────────────────────────────────────────────

    /**
     * Deterministic UUID for this port, used as placeholder claimant in
     * {@link GhostJobManager#requestJob}. Derived from world position — stable
     * across restarts, unique per position per JVM (not cross-JVM safe, but fine
     * for single-server use).
     */
    private UUID portId = null;

    // ── Constructor ───────────────────────────────────────────────────────────

    public DronePortBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DRONE_PORT.get(), pos, state);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            portId = UUID.nameUUIDFromBytes(worldPosition.toString().getBytes());
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
    }

    // ── Server tick ───────────────────────────────────────────────────────────

    public static void tick(Level level, BlockPos pos, BlockState state, DronePortBlockEntity be) {
        if (level.isClientSide) return;

        // Pull energy from floor (up to 4 blocks below)
        if (level.getGameTime() % 2 == 0) {
            for (int i = 1; i <= 4; i++) {
                IEnergyStorage floor = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos.below(i), Direction.UP);
                if (floor != null && floor.canExtract()) {
                    int toPull = be.energyStorage.getMaxEnergyStored() - be.energyStorage.getEnergyStored();
                    if (toPull > 0) {
                        int extracted = floor.extractEnergy(Math.min(toPull, GhostLibConfig.PORT_ENERGY_TRANSFER), false);
                        if (extracted > 0) {
                            be.energyStorage.receiveEnergy(extracted, false);
                            be.setChanged();
                        }
                    }
                    break;
                }
            }
        }

        // Spawn tick
        if (level.getGameTime() % SPAWN_INTERVAL == 0) {
            be.trySpawnDrone();
        }
    }

    // ── Drone spawn ───────────────────────────────────────────────────────────

    private void trySpawnDrone() {
        if (level == null || portId == null) return;

        // ── Guards ────────────────────────────────────────────────────────────
        if (activeDroneCount >= MAX_ACTIVE_DRONES) return;
        if (energyStorage.getEnergyStored() < ENERGY_PER_SPAWN) {
            GhostLib.LOGGER.debug("[DronePort] {} not enough energy to spawn ({}/{})",
                    worldPosition, energyStorage.getEnergyStored(), ENERGY_PER_SPAWN);
            return;
        }

        // ── Claim a job ───────────────────────────────────────────────────────
        GhostJobManager manager = GhostJobManager.get(level);
        GhostJobManager.Job job  = manager.requestJob(worldPosition, portId, true);
        if (job == null) return;

        // ── Ghost state check ─────────────────────────────────────────────────
        GhostBlockEntity gbe = null;
        if (level.getBlockEntity(job.pos()) instanceof GhostBlockEntity found) {
            gbe = found;
            if (gbe.getCurrentState() == GhostBlockEntity.GhostState.MISSING_ITEMS) {
                manager.releaseJob(job.pos(), portId);
                return;
            }
        }

        // ── Find a drone egg ──────────────────────────────────────────────────
        int eggSlot = -1;
        for (int i = 0; i < inventory.getSlots(); i++) {
            if (inventory.getStackInSlot(i).is(ModItems.DRONE_SPAWN_EGG.get())) {
                eggSlot = i;
                break;
            }
        }
        if (eggSlot == -1) {
            manager.releaseJob(job.pos(), portId);
            return;
        }

        // ── Check item availability for construction jobs ──────────────────────
        // Note: we only CHECK availability here — we do NOT extract.
        // The drone itself fetches the item during TRAVELING_FETCH.
        // This prevents the port from holding items "in transit" and causing
        // deadlocks when multiple drones are spawned for overlapping blueprints.
        boolean canFulfill = true;
        if (job.type() == GhostJobManager.JobType.CONSTRUCTION) {
            ItemStack required = new ItemStack(job.targetAfter().getBlock().asItem());
            canFulfill = isItemAvailable(required);
        }

        if (!canFulfill) {
            manager.releaseJob(job.pos(), portId);
            if (gbe != null) gbe.setState(GhostBlockEntity.GhostState.MISSING_ITEMS);
            return;
        }

        // ── Spawn ─────────────────────────────────────────────────────────────
        ItemStack eggStack = inventory.getStackInSlot(eggSlot);
        DroneEntity drone  = new DroneEntity(ModEntities.DRONE.get(), level);

        CustomData customData = eggStack.get(DataComponents.ENTITY_DATA);
        if (customData != null) customData.loadInto(drone);

        drone.setPos(worldPosition.getX() + 0.5, worldPosition.getY() + 1.5, worldPosition.getZ() + 0.5);
        drone.setPort(worldPosition);

        // Deduct egg BEFORE spawning to avoid any race where the entity exists but no egg was used
        inventory.extractItem(eggSlot, 1, false);

        // Deduct energy
        energyStorage.extractEnergy(ENERGY_PER_SPAWN, false);
        setChanged();

        // Atomically transfer the job claim from portId → drone UUID
        // portId is ALWAYS the correct oldId because requestJob used it above.
        manager.reassignJob(job.pos(), portId, drone.getUUID());
        drone.setInitialJob(job);

        level.addFreshEntity(drone);
        activeDroneCount++;

        GhostLib.LOGGER.debug("[DronePort] Spawned drone {} for job at {}", drone.getUUID(), job.pos());
    }

    // ── Item availability check ───────────────────────────────────────────────

    private boolean isItemAvailable(ItemStack required) {
        // Local port inventory
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack s = inventory.getStackInSlot(i);
            if (!s.isEmpty() && s.is(required.getItem())) return true;
        }

        // Scan nearby inventories within 16 blocks
        int range = 16;
        for (BlockPos pos : BlockPos.betweenClosed(
                worldPosition.offset(-range, -4, -range),
                worldPosition.offset(range, 4, range))) {
            if (pos.equals(worldPosition)) continue;
            if (!level.isLoaded(pos)) continue;
            IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
            if (handler == null) continue;
            for (int j = 0; j < handler.getSlots(); j++) {
                if (handler.getStackInSlot(j).is(required.getItem())) return true;
            }
        }
        return false;
    }

    // ── IDronePort ────────────────────────────────────────────────────────────

    @Override
    public int chargeDrone(int amount, boolean simulate) {
        return energyStorage.extractEnergy(amount, simulate);
    }

    @Override
    public ItemStack insertItem(ItemStack stack, boolean simulate) {
        ItemStack remainder = stack;

        // 1. Try port's own inventory first
        for (int i = 0; i < inventory.getSlots() && !remainder.isEmpty(); i++) {
            remainder = inventory.insertItem(i, remainder, simulate);
        }

        // 2. Scan nearby inventories within 16 blocks (vanilla chests, barrels, etc.)
        if (!remainder.isEmpty() && level != null && !level.isClientSide && !simulate) {
            int range = 16;
            BlockPos center = worldPosition;

            // Sort positions by distance to prefer closer inventories
            List<BlockPos> nearbyPositions = new java.util.ArrayList<>();
            for (BlockPos pos : BlockPos.betweenClosed(
                    center.offset(-range, -4, -range),
                    center.offset(range, 4, range))) {
                if (pos.equals(worldPosition)) continue;
                if (!level.isLoaded(pos)) continue;
                nearbyPositions.add(pos);
            }
            nearbyPositions.sort(Comparator.comparingDouble(p -> p.distSqr(center)));

            for (BlockPos pos : nearbyPositions) {
                net.neoforged.neoforge.items.IItemHandler handler =
                        level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, pos, null);
                if (handler == null) continue;

                remainder = net.neoforged.neoforge.items.ItemHandlerHelper.insertItemStacked(handler, remainder, simulate);
                if (remainder.isEmpty()) break;
            }
        }

        return remainder;
    }

    @Override
    public ItemStack extractItem(ItemStack stack, int amount, boolean simulate) {
        // 1. Check port's own inventory first
        for (int i = 0; i < inventory.getSlots(); i++) {
            if (inventory.getStackInSlot(i).is(stack.getItem())) {
                return inventory.extractItem(i, amount, simulate);
            }
        }

        // 2. Scan nearby inventories within 16 blocks (vanilla chests, barrels, etc.)
        if (level != null && !level.isClientSide && !simulate) {
            int range = 16;
            BlockPos center = worldPosition;

            // Sort positions by distance to prefer closer inventories
            List<BlockPos> nearbyPositions = new java.util.ArrayList<>();
            for (BlockPos pos : BlockPos.betweenClosed(
                    center.offset(-range, -4, -range),
                    center.offset(range, 4, range))) {
                if (pos.equals(worldPosition)) continue;
                if (!level.isLoaded(pos)) continue;
                nearbyPositions.add(pos);
            }
            nearbyPositions.sort(Comparator.comparingDouble(p -> p.distSqr(center)));

            for (BlockPos pos : nearbyPositions) {
                net.neoforged.neoforge.items.IItemHandler handler =
                        level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, pos, null);
                if (handler == null) continue;

                for (int i = 0; i < handler.getSlots(); i++) {
                    if (handler.getStackInSlot(i).is(stack.getItem())) {
                        ItemStack extracted = handler.extractItem(i, amount, simulate);
                        if (!extracted.isEmpty()) {
                            return extracted;
                        }
                    }
                }
            }
        }

        return ItemStack.EMPTY;
    }

    @Override
    public boolean isValid() { return !isRemoved(); }

    /** Called by drones when they recall or self-store, so the port can spawn replacements. */
    public void onDroneReturned() {
        activeDroneCount = Math.max(0, activeDroneCount - 1);
    }

    // ── GUI ───────────────────────────────────────────────────────────────────

    @Override
    public ModularUI createUI(Player player) {
        try {
            com.lowdragmc.lowdraglib2.gui.ui.UITemplate template =
                    com.lowdragmc.lowdraglib2.editor.resource.UIResource.INSTANCE.getResourceInstance()
                            .getResource(new com.lowdragmc.lowdraglib2.editor.resource.FilePath(
                                    ResourceLocation.fromNamespaceAndPath("ldlib2", "resources/global/port.ui.nbt")));
            UI ui = template != null ? template.createUI() : UI.empty();
            ui.select("energy_bar", ProgressBar.class).forEach(bar ->
                    bar.bindDataSource(GhostGUI.supplier(
                            () -> (float) energyStorage.getEnergyStored() / energyStorage.getMaxEnergyStored())));
            return ModularUI.of(ui, player);
        } catch (Exception e) {
            GhostLib.LOGGER.error("[DronePort] GUI creation failed", e);
            return ModularUI.of(UI.empty(), player);
        }
    }

    @Override
    public ModularUI createUI(com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType.BlockUIHolder holder) {
        return createUI(holder.player);
    }

    @Override
    public boolean isStillValid(Player player) { return !isRemoved(); }

    @Override
    public Component getDisplayName() { return Component.literal("Drone Port"); }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int windowId, Inventory playerInventory, Player player) {
        return new com.example.ghostlib.menu.DronePortMenu(
                (MenuType) com.example.ghostlib.registry.ModMenus.DRONE_PORT_MENU.get(),
                windowId, playerInventory, this);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public ItemStackHandler getInventory()       { return inventory; }
    public EnergyStorage    getEnergyStorage()   { return energyStorage; }
    public int              getActiveDroneCount(){ return activeDroneCount; }

    // ── NBT ───────────────────────────────────────────────────────────────────

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Inventory", inventory.serializeNBT(registries));
        tag.putInt("Energy", energyStorage.getEnergyStored());
        // activeDroneCount intentionally not persisted — recalculated naturally after restart
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Inventory")) inventory.deserializeNBT(registries, tag.getCompound("Inventory"));
        if (tag.contains("Energy")) {
            // receiveEnergy on a fresh EnergyStorage (0 stored) to set the value
            energyStorage.receiveEnergy(tag.getInt("Energy"), false);
        }
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
    public void onDataPacket(net.minecraft.network.Connection net,
                             net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket pkt,
                             HolderLookup.Provider lookupProvider) {
        if (pkt.getTag() != null) loadAdditional(pkt.getTag(), lookupProvider);
    }
}
