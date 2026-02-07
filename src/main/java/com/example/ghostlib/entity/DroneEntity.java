package com.example.ghostlib.entity;

import com.example.ghostlib.registry.ModAttributes;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;

import com.example.ghostlib.GhostLib;
import com.example.ghostlib.api.IDronePort;
import com.example.ghostlib.block.GhostBlock;
import com.example.ghostlib.block.entity.GhostBlockEntity;
import com.example.ghostlib.registry.ModBlocks;
import com.example.ghostlib.registry.ModItems;
import com.example.ghostlib.util.GhostJobManager;
import com.example.ghostlib.util.LogisticsNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class DroneEntity extends PathfinderMob {

    private static final EntityDataAccessor<Byte> DATA_MODE = SynchedEntityData.defineId(DroneEntity.class,
            EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Optional<BlockPos>> DATA_PORT_POS = SynchedEntityData
            .defineId(DroneEntity.class, EntityDataSerializers.OPTIONAL_BLOCK_POS);
    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER_UUID = SynchedEntityData
            .defineId(DroneEntity.class, EntityDataSerializers.OPTIONAL_UUID);

    public enum DroneMode {
        PLAYER((byte) 0), PORT((byte) 1);

        final byte id;

        DroneMode(byte id) {
            this.id = id;
        }

        static DroneMode byId(byte id) {
            return id == 1 ? PORT : PLAYER;
        }
    }

    public UUID getOwnerUUID() {
        return this.entityData.get(DATA_OWNER_UUID).orElse(null);
    }

    public void setOwnerUUID(UUID uuid) {
        this.entityData.set(DATA_OWNER_UUID, Optional.ofNullable(uuid));
    }

    public enum DroneState {
        IDLE,
        FINDING_JOB,
        TRAVELING_CLEAR,
        TRAVELING_FETCH,
        TRAVELING_BUILD,
        DUMPING_ITEMS,
        CHARGING,
        RETURNING_TO_OWNER
    }

    // Logic controls
    private int idleChecks = 0;
    private int noJobBackoff = 0;
    private static final int MAX_BACKOFF = 10; // Max 120 tick interval between job checks

    private DroneState droneState = DroneState.IDLE;
    private GhostJobManager.Job currentJob = null;
    private Integer networkId = null;
    private final SimpleContainer inventory = new SimpleContainer(9);

    private int energy = 10000;
    private static final int FLY_COST = 1;
    private static final int WORK_COST = 50;
    private boolean lowPowerMode = false;

    private int waitTicks = 0;
    private int idleTicks = 0;
    private int lingerTicks = 0;
    private static final int MAX_IDLE_TICKS = 100;

    public DroneEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
        this.noPhysics = true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean isInvulnerableTo(net.minecraft.world.damagesource.DamageSource source) {
        if (source.is(net.minecraft.world.damagesource.DamageTypes.IN_WALL)
                || source.is(net.minecraft.world.damagesource.DamageTypes.CRAMMING))
            return true;
        return super.isInvulnerableTo(source);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_MODE, (byte) 0);
        builder.define(DATA_PORT_POS, Optional.empty());
        builder.define(DATA_OWNER_UUID, Optional.empty());
    }

    public void setOwner(Player player) {
        this.entityData.set(DATA_MODE, DroneMode.PLAYER.id);
        this.entityData.set(DATA_OWNER_UUID, Optional.of(player.getUUID()));
        this.entityData.set(DATA_PORT_POS, Optional.empty());
    }

    public void setPort(BlockPos pos) {
        this.entityData.set(DATA_MODE, DroneMode.PORT.id);
        this.entityData.set(DATA_PORT_POS, Optional.of(pos));
        this.entityData.set(DATA_OWNER_UUID, Optional.empty());
    }

    public DroneMode getMode() {
        return DroneMode.byId(this.entityData.get(DATA_MODE));
    }

    public Optional<BlockPos> getPortPos() {
        return this.entityData.get(DATA_PORT_POS);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, com.example.ghostlib.config.GhostLibConfig.DRONE_MAX_HEALTH)
                .add(Attributes.MOVEMENT_SPEED, 3.0D) // Optimized speed
                .add(ModAttributes.INTERACTION_RANGE)
                .add(ModAttributes.SEARCH_RANGE)
                .add(ModAttributes.WORK_SPEED)
                .add(ModAttributes.MAX_ENERGY)
                .add(ModAttributes.ENERGY_EFFICIENCY)
                .add(ModAttributes.SILK_TOUCH);
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, net.minecraft.world.damagesource.DamageSource source,
            boolean recentlyHit) {
        super.dropCustomDeathLoot(level, source, recentlyHit);
        // Drop Inventory
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                Block.popResource(level, blockPosition(), stack);
            }
        }
        // Drop Self
        Block.popResource(level, blockPosition(), new ItemStack(ModItems.DRONE_SPAWN_EGG.get()));
    }

    @Override
    public void travel(Vec3 travelVector) {
        if (this.isEffectiveAi() || this.isControlledByLocalInstance()) {
            if (this.noPhysics) {
                // Use delta movement set by our smooth movement logic
                Vec3 delta = this.getDeltaMovement();
                this.move(net.minecraft.world.entity.MoverType.SELF, delta);
                // Standard friction/drag
                this.setDeltaMovement(delta.scale(0.91)); 
            } else {
                super.travel(travelVector);
            }
        }
    }

    public void setInitialJob(GhostJobManager.Job job) {
        this.currentJob = job;
        if (job.type() == GhostJobManager.JobType.CONSTRUCTION) {
            ItemStack required = new ItemStack(job.targetAfter().getBlock().asItem());
            if (hasItemInInventory(required)) {
                this.droneState = DroneState.TRAVELING_BUILD;
            } else {
                this.droneState = DroneState.TRAVELING_FETCH;
            }
        } else {
            this.droneState = DroneState.TRAVELING_CLEAR;
        }
    }

    private int jobWatchdog = 0;
    private static final int WATCHDOG_LIMIT = 600; // 30 seconds

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide)
            return;

        consumeEnergy();

        if (waitTicks > 0) {
            waitTicks--;
            return;
        }

        // Job Watchdog
        if (currentJob != null) {
            jobWatchdog++;
            if (jobWatchdog > WATCHDOG_LIMIT) {
                com.example.ghostlib.util.GhostLogger
                        .drone("Drone " + this.getId() + " job timed out at " + currentJob.pos() + ". Releasing.");
                resetToIdle();
                return;
            }
        } else {
            jobWatchdog = 0;
        }
        if (getMode() == DroneMode.PORT) {
            Optional<BlockPos> p = getPortPos();
            boolean valid = false;
            if (p.isPresent()) {
                BlockPos portPos = p.get();
                if (level().hasChunkAt(portPos)) {
                    if (level().getBlockEntity(portPos) instanceof IDronePort) {
                        valid = true;
                        // Update Network ID occasionally
                        if (this.tickCount % 100 == 0) {
                            this.networkId = LogisticsNetworkManager.get(level()).getNetworkId(portPos);
                        }
                    }
                }
            }

            if (!valid) {
                // ORPHAN LOGIC: Find new port in same network
                boolean rehomed = false;
                if (networkId != null) {
                    Set<BlockPos> members = LogisticsNetworkManager.get(level()).getNetworkMembers(networkId);
                    for (BlockPos candidate : members) {
                        if (level().isLoaded(candidate) && level().getBlockEntity(candidate) instanceof IDronePort) {
                            this.setPort(candidate);
                            GhostLib.LOGGER.info("Orphaned drone rehomed to {}", candidate);
                            rehomed = true;
                            break;
                        }
                    }
                }

                if (!rehomed) {
                    GhostLib.LOGGER.warn("Drone orphaned and homeless. Deactivating.");
                    // Emergency Landing: Turn into item
                    Block.popResource(level(), blockPosition(), new ItemStack(ModItems.DRONE_SPAWN_EGG.get()));
                    // Drop inventory
                    for (int i = 0; i < inventory.getContainerSize(); i++) {
                        ItemStack stack = inventory.getItem(i);
                        if (!stack.isEmpty())
                            Block.popResource(level(), blockPosition(), stack);
                    }
                    this.discard();
                    return;
                }
            }
        }

        if (currentJob != null && droneState != DroneState.IDLE && droneState != DroneState.DUMPING_ITEMS
                && droneState != DroneState.CHARGING) {
            if (!GhostJobManager.get(level()).isAssignedTo(currentJob.pos(), this.getUUID())) {
                this.currentJob = null;
                this.droneState = isInventoryEmpty() ? DroneState.IDLE : DroneState.DUMPING_ITEMS;
            }
        }

        switch (droneState) {
            case IDLE -> handleIdle();
            case FINDING_JOB -> handleFindingJob();
            case TRAVELING_CLEAR -> handleTravelingClear();
            case TRAVELING_FETCH -> handleTravelingFetch();
            case TRAVELING_BUILD -> handleTravelingBuild();
            case DUMPING_ITEMS -> handleDumpingItems();
            case CHARGING -> handleCharging();
            case RETURNING_TO_OWNER -> handleReturningToOwner();
        }
    }

    private void consumeEnergy() {
        if (energy > 0) {
            if (this.getDeltaMovement().lengthSqr() > 0.001) {
                double efficiency = this.getAttributeValue(ModAttributes.ENERGY_EFFICIENCY);
                energy -= Math.max(1, (int) (FLY_COST / efficiency));
            }
            lowPowerMode = false;
        } else {
            lowPowerMode = true;
            if (getMode() == DroneMode.PORT)
                this.droneState = DroneState.CHARGING;
        }
    }

    private void handleCharging() {
        if (getMode() == DroneMode.PORT) {
            BlockPos targetPort = findNearestNetworkPort();
            if (targetPort == null) {
                targetPort = getPortPos().orElse(null);
            }

            if (targetPort != null) {
                // Update home port if we found a better one in network
                if (!targetPort.equals(getPortPos().orElse(null))) {
                    this.setPort(targetPort);
                }

                // Dock at the TOP of the controller
                Vec3 dockPos = Vec3.atCenterOf(targetPort).add(0, 0.5, 0);
                // Move faster when charging to return to port quickly
                moveSmoothlyTo(dockPos, 1.0); // Increased speed from 0.8 to 1.0

                if (this.position().distanceTo(dockPos) < 1.0) {
                    if (level().getBlockEntity(targetPort) instanceof IDronePort dp) {
                        // Charging
                        int charged = dp.chargeDrone(2000, false);
                        double maxEnergy = this.getAttributeValue(ModAttributes.MAX_ENERGY);
                        this.energy = Math.min(this.energy + charged, (int) maxEnergy);

                        // Item Swap while docked
                        if (!isInventoryEmpty()) {
                            tryDumpAtPort(targetPort);
                        }

                        if (this.energy >= (int) maxEnergy * 0.9) {
                            // If fully charged AND idle, try to store self
                            if (idleTicks > 100 && isInventoryEmpty()) {
                                ItemStack self = new ItemStack(
                                        com.example.ghostlib.registry.ModItems.DRONE_SPAWN_EGG.get());
                                if (dp.insertItem(self, true).isEmpty()) {
                                    dp.insertItem(self, false);
                                    this.discard();
                                    return;
                                }
                            }
                            this.droneState = DroneState.IDLE;
                            this.setDeltaMovement(0, 0.5, 0);
                        }
                    }
                }
            } else {
                this.droneState = DroneState.IDLE;
            }
        } else {
            this.droneState = DroneState.IDLE;
        }
    }

    private BlockPos findNearestNetworkPort() {
        if (networkId == null) return null;
        Set<BlockPos> members = LogisticsNetworkManager.get(level()).getNetworkMembers(networkId);
        BlockPos nearest = null;
        double minDist = Double.MAX_VALUE;
        for (BlockPos p : members) {
            if (level().getBlockEntity(p) instanceof IDronePort) {
                double d = p.distSqr(this.blockPosition());
                if (d < minDist) {
                    minDist = d;
                    nearest = p;
                }
            }
        }
        return nearest;
    }

    private void handleIdle() {
        if (energy < getAttributeValue(ModAttributes.MAX_ENERGY) * 0.2 && getMode() == DroneMode.PORT) {
            this.droneState = DroneState.CHARGING;
            return;
        }

        // Logic for drones with items
        if (!isInventoryEmpty()) {
            if (getMode() == DroneMode.PORT) {
                // Port drones ALWAYS go home to dump items if they have any and no urgent job
                this.droneState = DroneState.DUMPING_ITEMS;
                return;
            } else {
                // Player drones check if they can fulfill a local job first
                GhostJobManager.Job job = GhostJobManager.get(level()).requestJob(this.blockPosition(), this.getUUID(),
                        false);
                if (job != null && job.type() == GhostJobManager.JobType.CONSTRUCTION) {
                    ItemStack required = new ItemStack(job.targetAfter().getBlock().asItem());
                    if (hasItemInInventory(required)) {
                        this.droneState = DroneState.FINDING_JOB;
                        return;
                    }
                }
                this.droneState = DroneState.DUMPING_ITEMS;
                return;
            }
        }

        if (lingerTicks > 0) {
            lingerTicks--;
            this.setDeltaMovement(this.getDeltaMovement().scale(0.8));
        } else {
            if (getMode() == DroneMode.PLAYER) {
                UUID ownerId = getOwnerUUID();
                if (ownerId != null) {
                    Player player = level().getPlayerByUUID(ownerId);
                    if (player != null) {
                        Vec3 target = player.position().add(1.5, 2.0, 1.5);
                        moveSmoothlyTo(target, 0.2);
                        this.getLookControl().setLookAt(player);
                    }
                }
            } else if (getMode() == DroneMode.PORT && getPortPos().isPresent()) {
                // Hover at the TOP of the port multiblock
                Vec3 target = Vec3.atCenterOf(getPortPos().get()).add(0, 2.0, 0);
                moveSmoothlyTo(target, 0.2);
            }
        }

        // Auto-Recall and Backoff
        if (getMode() == DroneMode.PLAYER) {
            idleChecks++;
            // Recall if idle for ~15 seconds (300 ticks)
            if (idleChecks > 300) {
                UUID ownerId = getOwnerUUID();
                if (ownerId != null) {
                    this.droneState = DroneState.RETURNING_TO_OWNER;
                    return;
                }
            }
        } else if (getMode() == DroneMode.PORT) {
            idleChecks++;
            // Return to port storage if idle for ~5 seconds (faster storage)
            if (idleChecks > 100 && isInventoryEmpty() && energy > getAttributeValue(ModAttributes.MAX_ENERGY) * 0.5) {
                this.droneState = DroneState.CHARGING; // Go home
                return; // FIX: Return immediately to prevent state overwrite
            }
        }

        // Try to find job with backoff (Capped at 100 ticks = 5 seconds)
        int checkInterval = getMode() == DroneMode.PORT ? 10 : Math.min(100, 20 + noJobBackoff * 5);
        if (this.tickCount % checkInterval == 0 || (this.droneState == DroneState.IDLE && idleChecks == 0)) {
            // Priority Check: Switch to finding job immediately if we just became idle
            this.droneState = DroneState.FINDING_JOB;
        }
    }

    private void handleReturningToOwner() {
        UUID ownerId = getOwnerUUID();
        if (ownerId == null) {
            this.droneState = DroneState.IDLE;
            return;
        }

        Player owner = level().getPlayerByUUID(ownerId);
        if (owner == null) {
            this.droneState = DroneState.IDLE;
            return;
        }

        if (this.distanceToSqr(owner) < 9.0D) { // 3 blocks
            ItemStack droneItem = new ItemStack(ModItems.DRONE_SPAWN_EGG.get());
            // Save energy to item if desired? No, fresh start.

            if (owner.getInventory().add(droneItem)) {
                this.discard();
                level().playSound(null, this.blockPosition(), net.minecraft.sounds.SoundEvents.ITEM_PICKUP,
                        net.minecraft.sounds.SoundSource.NEUTRAL, 1.0f, 1.0f);
            } else {
                this.droneState = DroneState.IDLE;
                this.idleChecks = 0; // Reset
            }
        } else {
            // Use moveSmoothlyTo for flying, aiming slightly above player's head
            moveSmoothlyTo(owner.position().add(0, 2.0, 0), 0.7);
        }
    }

    private void handleFindingJob() {
        if (currentJob != null) return;

        // Drones can always build if they have energy. Space is only strictly required for deconstruction,
        // but we'll let the job manager filter based on the canBuild flag which should be true if we want to build.
        GhostJobManager.Job job = GhostJobManager.get(level()).requestJob(this.blockPosition(), this.getUUID(), true);
        
        if (job != null) {
            this.currentJob = job;
            idleTicks = 0;
            lingerTicks = 0;
            this.noJobBackoff = 0;

            if (job.type() == GhostJobManager.JobType.CONSTRUCTION) {
                ItemStack required = new ItemStack(job.targetAfter().getBlock().asItem());
                if (hasItemInInventory(required)) {
                    this.droneState = DroneState.TRAVELING_BUILD;
                    if (level().isLoaded(job.pos())) {
                        if (level().getBlockEntity(job.pos()) instanceof GhostBlockEntity gbe) {
                            gbe.setAssignedTo(this.getUUID());
                            gbe.setState(GhostBlockEntity.GhostState.INCOMING);
                        }
                    }
                    return;
                } else {
                    this.droneState = DroneState.TRAVELING_FETCH;
                    if (level().isLoaded(job.pos())) {
                        if (level().getBlockEntity(job.pos()) instanceof GhostBlockEntity gbe) {
                            gbe.setAssignedTo(this.getUUID());
                            gbe.setState(GhostBlockEntity.GhostState.FETCHING);
                        }
                    }
                    return;
                }
            } else if (job.type() == GhostJobManager.JobType.DIRECT_DECONSTRUCT) {
                this.droneState = DroneState.TRAVELING_CLEAR;
                return;
            } else if (job.type() == GhostJobManager.JobType.GHOST_REMOVAL) {
                this.droneState = DroneState.TRAVELING_CLEAR;
                if (level().isLoaded(job.pos())) {
                    if (level().getBlockEntity(job.pos()) instanceof GhostBlockEntity gbe) {
                        gbe.setAssignedTo(this.getUUID());
                        gbe.setState(GhostBlockEntity.GhostState.REMOVING);
                    }
                }
                return;
            }
        } else {
            this.noJobBackoff = Math.min(this.noJobBackoff + 1, MAX_BACKOFF);
            this.droneState = DroneState.IDLE;
        }
    }

    private void handleTravelingFetch() {
        if (currentJob == null) {
            resetToIdle();
            return;
        }

        // Verify the job still exists before proceeding
        if (!GhostJobManager.get(level()).jobExistsAt(currentJob.pos())) {
            com.example.ghostlib.util.GhostLogger.drone("Drone " + this.getId() + " fetch job no longer exists at "
                    + currentJob.pos() + ", releasing and finding new job");
            this.currentJob = null;
            this.droneState = DroneState.FINDING_JOB;
            return;
        }

        ItemStack required = new ItemStack(currentJob.targetAfter().getBlock().asItem());
        if (hasItemInInventory(required)) {
            this.droneState = DroneState.TRAVELING_BUILD;
            return;
        }

        if (getMode() == DroneMode.PORT && getPortPos().isPresent()) {
            BlockPos p = getPortPos().get();
            moveSmoothlyTo(Vec3.atCenterOf(p).add(0, 1, 0), 0.7);
            if (this.position().distanceTo(Vec3.atCenterOf(p).add(0, 1, 0)) < 2.0) {
                if (level().getBlockEntity(p) instanceof IDronePort dp) {
                    ItemStack extracted = dp.extractItem(required, 1, false);
                    if (!extracted.isEmpty()) {
                        this.inventory.addItem(extracted);
                        this.droneState = DroneState.TRAVELING_BUILD;
                        return;
                    }
                }
            }
        }

        BlockPos containerPos = findNearbyContainerWithItem(required);
        if (containerPos != null) {
            moveSmoothlyTo(Vec3.atCenterOf(containerPos), 0.7);
            if (this.position().distanceTo(Vec3.atCenterOf(containerPos)) < 2.0) {
                // RE-VERIFY: Check if item is still there before taking
                if (extractFromContainer(containerPos, required)) {
                    this.droneState = DroneState.TRAVELING_BUILD;
                    if (level().getBlockEntity(currentJob.pos()) instanceof GhostBlockEntity gbe) {
                        gbe.setState(GhostBlockEntity.GhostState.INCOMING);
                    }
                } else {
                    // Item gone! Release and look for another source or job.
                    com.example.ghostlib.util.GhostLogger.drone("Drone " + this.getId() + " failed to fetch "
                            + required.getItem().getName(required).getString() + " at " + containerPos
                            + ". Item missing (Race).");
                    this.droneState = DroneState.FINDING_JOB;
                }
            }
            return;
        }
        Player player = level().getNearestPlayer(this, 64);
        if (player == null) {
            // No player available, try to find another option or go back to finding job
            com.example.ghostlib.util.GhostLogger
                    .drone("Drone " + this.getId() + " no player found for fetch, returning to job search");
            this.droneState = DroneState.FINDING_JOB;
            return;
        }
        Vec3 fetchPos = player.position().add(0, player.getEyeHeight(), 0);
        moveSmoothlyTo(fetchPos, 0.7);
        if (this.position().distanceTo(fetchPos) < 2.0) {
            // Loose matching: Find any slot with the correct Item, ignoring NBT/Components
            int slot = -1;
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack s = player.getInventory().getItem(i);
                if (!s.isEmpty() && s.is(required.getItem())) {
                    slot = i;
                    break;
                }
            }

            boolean acquired = false;
            if (slot != -1) {
                ItemStack stackInSlot = player.getInventory().getItem(slot);
                // Double check (redundant but safe)
                if (!stackInSlot.isEmpty() && stackInSlot.is(required.getItem())) {
                    // CRITICAL: Validate drone has space BEFORE taking from player
                    if (!hasSpace()) {
                        GhostLib.LOGGER.warn("Drone inventory full, cannot take item from player");
                        this.droneState = DroneState.DUMPING_ITEMS;
                        if (currentJob != null) {
                            GhostJobManager.get(level()).releaseJob(currentJob.pos(), this.getUUID());
                        }
                        this.currentJob = null;
                        return;
                    }

                    // Transaction: Take from player
                    ItemStack taken = stackInSlot.split(1);

                    // Transaction: Add to drone (with rollback on failure)
                    ItemStack remainder = this.inventory.addItem(taken);
                    if (!remainder.isEmpty()) {
                        // ROLLBACK: Failed to add to drone, return to player
                        GhostLib.LOGGER.error("Failed to add item to drone inventory, rolling back transaction");
                        stackInSlot.grow(1); // Return the item
                        acquired = false;
                    } else {
                        // SUCCESS: Transaction complete
                        acquired = true;
                    }
                }
            }
            if (acquired) {
                this.droneState = DroneState.TRAVELING_BUILD;
                if (currentJob != null && level().getBlockEntity(currentJob.pos()) instanceof GhostBlockEntity gbe) {
                    if (gbe.getCurrentState() != GhostBlockEntity.GhostState.INCOMING)
                        gbe.setState(GhostBlockEntity.GhostState.INCOMING);
                }
            } else {
                if (currentJob != null && level().getBlockEntity(currentJob.pos()) instanceof GhostBlockEntity gbe) {
                    gbe.setState(GhostBlockEntity.GhostState.MISSING_ITEMS);
                }
                if (currentJob != null) {
                    GhostJobManager.get(level()).releaseJob(currentJob.pos(), this.getUUID());
                }
                currentJob = null;
                waitTicks = 100;
                this.droneState = DroneState.IDLE;
            }
        }
    }

    private BlockPos findNearbyContainerWithItem(ItemStack stack) {
        BlockPos center = this.blockPosition();

        // 1. Network Search (Prioritized by Factorio rules)
        if (networkId != null) {
            Set<BlockPos> members = LogisticsNetworkManager.get(level()).getNetworkMembers(networkId);
            BlockPos bestProvider = null;
            BlockPos bestGeneric = null;

            for (BlockPos p : members) {
                net.neoforged.neoforge.items.IItemHandler handler = level()
                        .getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, p, null);
                if (handler != null) {
                    boolean isProvider = false;
                    if (level().getBlockEntity(
                            p) instanceof com.example.ghostlib.block.entity.LogisticalChestBlockEntity lc) {
                        var type = lc.getChestType();
                        if (type == com.example.ghostlib.block.LogisticalChestBlock.ChestType.PASSIVE_PROVIDER ||
                                type == com.example.ghostlib.block.LogisticalChestBlock.ChestType.ACTIVE_PROVIDER ||
                                type == com.example.ghostlib.block.LogisticalChestBlock.ChestType.BUFFER) {
                            isProvider = true;
                        }
                    }

                    for (int i = 0; i < handler.getSlots(); i++) {
                        if (handler.getStackInSlot(i).is(stack.getItem())) {
                            if (isProvider) {
                                if (bestProvider == null || p.distSqr(center) < bestProvider.distSqr(center))
                                    bestProvider = p.immutable();
                            } else {
                                if (bestGeneric == null || p.distSqr(center) < bestGeneric.distSqr(center))
                                    bestGeneric = p.immutable();
                            }
                            break;
                        }
                    }
                }
            }
            if (bestProvider != null)
                return bestProvider;
            if (bestGeneric != null)
                return bestGeneric;
        }

        // 2. Local Search (Fallback or Player Mode)
        int searchRange = (int) this.getAttributeValue(ModAttributes.SEARCH_RANGE);
        int rh = searchRange / 2;
        int rv = searchRange / 4;

        BlockPos bestProvider = null;
        BlockPos bestGeneric = null;

        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-rh, -rv, -rh), center.offset(rh, rv, rh))) {
            net.neoforged.neoforge.items.IItemHandler handler = level()
                    .getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, pos, null);

            if (handler != null) {
                // Priority Check: Is it a Logistical Provider?
                boolean isProvider = false;
                if (level().getBlockEntity(
                        pos) instanceof com.example.ghostlib.block.entity.LogisticalChestBlockEntity lc) {
                    var type = lc.getChestType();
                    if (type == com.example.ghostlib.block.LogisticalChestBlock.ChestType.PASSIVE_PROVIDER ||
                            type == com.example.ghostlib.block.LogisticalChestBlock.ChestType.BUFFER) {
                        isProvider = true;
                    }
                }

                for (int i = 0; i < handler.getSlots(); i++) {
                    if (handler.getStackInSlot(i).is(stack.getItem())) {
                        if (isProvider) {
                            if (bestProvider == null || pos.distSqr(center) < bestProvider.distSqr(center)) {
                                bestProvider = pos.immutable();
                            }
                        } else {
                            if (bestGeneric == null || pos.distSqr(center) < bestGeneric.distSqr(center)) {
                                bestGeneric = pos.immutable();
                            }
                        }
                        break;
                    }
                }
            }
        }
        return bestProvider != null ? bestProvider : bestGeneric;
    }

    private boolean extractFromContainer(BlockPos pos, ItemStack stack) {
        net.neoforged.neoforge.items.IItemHandler handler = level()
                .getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler != null) {
            for (int i = 0; i < handler.getSlots(); i++) {
                if (handler.getStackInSlot(i).is(stack.getItem())) {
                    ItemStack simulated = handler.extractItem(i, 1, true);
                    if (!simulated.isEmpty()) {
                        if (this.inventory.canAddItem(simulated)) {
                            ItemStack taken = handler.extractItem(i, 1, false);
                            this.inventory.addItem(taken);
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private void handleTravelingBuild() {
        if (currentJob == null) {
            resetToIdle();
            return;
        }

        // Verify the job still exists before proceeding
        if (!GhostJobManager.get(level()).jobExistsAt(currentJob.pos())) {
            com.example.ghostlib.util.GhostLogger.drone("Drone " + this.getId() + " job no longer exists at "
                    + currentJob.pos() + ", releasing and finding new job");
            this.currentJob = null;
            this.droneState = DroneState.FINDING_JOB;
            return;
        }

        ItemStack required = new ItemStack(currentJob.targetAfter().getBlock().asItem());
        if (!hasItemInInventory(required)) {
            com.example.ghostlib.util.GhostLogger
                    .drone("Drone " + this.getId() + " missing required item for build at " + currentJob.pos());
            this.droneState = DroneState.TRAVELING_FETCH;
            return;
        }

        moveSmoothlyTo(currentJob.pos().getCenter(), 0.7);
        double interactRange = this.getAttributeValue(ModAttributes.INTERACTION_RANGE);
        double dist = this.position().distanceTo(currentJob.pos().getCenter());

        if (dist < interactRange) {
            BlockPos pos = currentJob.pos();

            // VISUAL FEEDBACK: Cyan Laser for Build
            if (com.example.ghostlib.config.GhostLibConfig.RENDER_DRONE_BEAMS) {
                spawnBeam(this.position().add(0, 0.2, 0), Vec3.atCenterOf(pos), 0.2f, 0.8f, 1.0f);
            }

            CompoundTag nbtToApply = null;
            if (level().getBlockEntity(pos) instanceof GhostBlockEntity gbe) {
                nbtToApply = gbe.getCapturedNbt();
            }

            if (com.example.ghostlib.config.GhostLibConfig.RENDER_DRONE_BEAMS) {
                spawnBeam(this.position().add(0, 0.2, 0), Vec3.atCenterOf(pos), 0.2f, 0.2f, 1.0f);
            }

            BlockState worldState = level().getBlockState(pos);
            com.example.ghostlib.util.GhostLogger.drone("Drone " + this.getId() + " at build site " + pos
                    + ", current block: " + worldState.getBlock().getName().getString() + ", target: "
                    + currentJob.targetAfter().getBlock().getName().getString());

            // Verify the job still exists and is valid before proceeding
            if (!GhostJobManager.get(level()).jobExistsAt(pos)) {
                com.example.ghostlib.util.GhostLogger.drone(
                        "Drone " + this.getId() + " job was claimed by another drone at " + pos + ", aborting build");
                this.currentJob = null;
                this.droneState = DroneState.FINDING_JOB;
                return;
            }

            // If the block is already what we want, just finish
            if (worldState.equals(currentJob.targetAfter())) {
                com.example.ghostlib.util.GhostLogger
                        .drone("Drone " + this.getId() + " block already correct at " + pos + ", completing job");
                // Complete the job properly
                GhostJobManager.get(level()).completeJob(pos, level());
                this.currentJob = null;
                this.droneState = DroneState.IDLE;
                return;
            }

            if (!worldState.isAir() && !worldState.canBeReplaced() && !(worldState.getBlock() instanceof GhostBlock)) {
                com.example.ghostlib.util.GhostLogger
                        .drone("Drone " + this.getId() + " obstruction at " + pos + ", registering deconstruction");
                // Register deconstruction job for the obstructing block
                GhostJobManager.get(level()).registerDirectDeconstruct(pos, currentJob.targetAfter(), level());
                this.currentJob = null;
                this.droneState = DroneState.FINDING_JOB;
                return;
            }

            // Find item in inventory (preferring one with NBT data)
            int slot = findBestSlot(currentJob.targetAfter().getBlock().asItem());
            if (slot == -1) {
                com.example.ghostlib.util.GhostLogger
                        .drone("Drone " + this.getId() + " item disappeared from inventory at " + pos);
                // Unexpected: Inventory check passed earlier but item missing now?
                this.droneState = DroneState.TRAVELING_FETCH;
                return;
            }

            ItemStack usedStack = this.inventory.getItem(slot).split(1);
            if (this.inventory.getItem(slot).isEmpty()) {
                this.inventory.setItem(slot, ItemStack.EMPTY);
            }

            com.example.ghostlib.util.GhostLogger.drone("Drone " + this.getId() + " PLACING BLOCK at " + pos + ": "
                    + currentJob.targetAfter().getBlock().getName().getString());

            // Actually place the block
            boolean placed = this.level().setBlock(pos, currentJob.targetAfter(), 3);

            if (!placed) {
                com.example.ghostlib.util.GhostLogger
                        .drone("ERROR: Drone " + this.getId() + " setBlock FAILED at " + pos);
                // Return item to inventory
                this.inventory.addItem(usedStack);
                this.currentJob = null;
                this.droneState = DroneState.FINDING_JOB;
                return;
            }

            com.example.ghostlib.util.GhostLogger
                    .drone("Drone " + this.getId() + " setBlock SUCCESS at " + pos + ", verifying...");

            // Verify placement
            BlockState verifyState = level().getBlockState(pos);
            if (!verifyState.equals(currentJob.targetAfter())) {
                com.example.ghostlib.util.GhostLogger
                        .drone("ERROR: Drone " + this.getId() + " placement verification FAILED at " + pos + ", got "
                                + verifyState.getBlock().getName().getString());
            } else {
                com.example.ghostlib.util.GhostLogger.drone("Drone " + this.getId() + " placement VERIFIED at " + pos);
            }

            // Restore NBT Data (Only from the used item to prevent duplication)
            // We do NOT use blueprint NBT (nbtToApply) here because that would create items
            // from thin air.
            // Blueprint NBT is for reference or Creative Mode only.
            net.minecraft.world.level.block.entity.BlockEntity newBe = level().getBlockEntity(pos);
            if (newBe != null && usedStack.has(DataComponents.BLOCK_ENTITY_DATA)) {
                CustomData data = usedStack.get(DataComponents.BLOCK_ENTITY_DATA);
                if (data != null) {
                    CompoundTag tag = data.copyTag();
                    tag.putInt("x", pos.getX());
                    tag.putInt("y", pos.getY());
                    tag.putInt("z", pos.getZ());
                    newBe.loadWithComponents(tag, level().registryAccess());
                    newBe.setChanged();
                }
            }

            this.playSound(com.example.ghostlib.registry.ModSounds.DRONE_WORK.get(), 1.0f, 1.0f);

            // Complete the construction job properly
            GhostJobManager.get(level()).completeJob(pos, level());
            double efficiency = this.getAttributeValue(ModAttributes.ENERGY_EFFICIENCY);
            this.energy -= (int) (WORK_COST / efficiency);

            double workSpeed = this.getAttributeValue(ModAttributes.WORK_SPEED);
            this.lingerTicks = (int) (2 / workSpeed); // Faster linger
            this.currentJob = null;
            this.droneState = DroneState.FINDING_JOB; // Immediate re-check
        }
    }

    private void handleTravelingClear() {

        if (currentJob == null) {

            resetToIdle();

            return;

        }

        // Verify the job still exists before proceeding
        if (!GhostJobManager.get(level()).jobExistsAt(currentJob.pos())) {
            com.example.ghostlib.util.GhostLogger.drone("Drone " + this.getId()
                    + " deconstruct job no longer exists at " + currentJob.pos() + ", releasing and finding new job");
            this.currentJob = null;
            this.droneState = DroneState.FINDING_JOB;
            return;
        }

        moveSmoothlyTo(currentJob.pos().getCenter(), 0.6);
        double interactRange = this.getAttributeValue(ModAttributes.INTERACTION_RANGE);
        double dist = this.position().distanceTo(currentJob.pos().getCenter());

        if (dist < interactRange) {

            BlockPos pos = currentJob.pos();

            BlockState existing = level().getBlockState(pos);

            BlockState targetAfter = currentJob.targetAfter();

            BlockState finalIntended = currentJob.finalState();

            // VISUAL FEEDBACK: Construct/Deconstruct Lasers

            if (com.example.ghostlib.config.GhostLibConfig.RENDER_DRONE_BEAMS) {

                spawnBeam(this.position().add(0, 0.2, 0), Vec3.atCenterOf(pos), 1.0f, 0.2f, 0.2f); // Red for
                                                                                                   // Deconstruct

            }

            // Verify the job still exists and is valid before proceeding
            if (!GhostJobManager.get(level()).jobExistsAt(pos)) {
                com.example.ghostlib.util.GhostLogger.drone("Drone " + this.getId()
                        + " deconstruct job was claimed by another drone at " + pos + ", aborting deconstruction");
                this.currentJob = null;
                this.droneState = DroneState.FINDING_JOB;
                return;
            }

            com.example.ghostlib.util.GhostLogger.drone("Drone " + this.getId() + " physically breaking "
                    + existing.getBlock().getName().getString() + " at " + pos);

            // PHYSICAL WORK: Harvest and Break (Silent/Instant for performance)

            harvest(pos, existing);

            level().setBlock(pos, Blocks.AIR.defaultBlockState(), 3);

            // Complete the deconstruction job properly
            GhostJobManager.get(level()).completeJob(pos, this.level());

            if (targetAfter != null && !targetAfter.isAir()) {

                // Seed marker if necessary

                level().setBlock(pos, targetAfter, 3);

                if (targetAfter.getBlock() instanceof GhostBlock) {

                    if (level().getBlockEntity(pos) instanceof GhostBlockEntity gbe) {

                        if (finalIntended != null && !finalIntended.isAir()) {

                            gbe.setTargetState(finalIntended);

                            gbe.setState(GhostBlockEntity.GhostState.UNASSIGNED);

                            com.example.ghostlib.util.GhostLogger
                                    .drone("Drone " + this.getId() + " seeded Ghost marker for "
                                            + finalIntended.getBlock().getName().getString() + " at " + pos);

                        }

                    }

                }

            }

            double efficiency = this.getAttributeValue(ModAttributes.ENERGY_EFFICIENCY);

            this.energy -= (int) (WORK_COST / efficiency);

            this.lingerTicks = 10;

            this.currentJob = null;

            this.droneState = isInventoryEmpty() ? DroneState.IDLE : DroneState.DUMPING_ITEMS;

        }

    }

    private void spawnBeam(Vec3 start, Vec3 end, float r, float g, float b) {
        if (level() instanceof ServerLevel sl) {
            Vec3 dir = end.subtract(start);
            double dist = dir.length();
            dir = dir.normalize();
            for (double d = 0; d < dist; d += 0.2) {
                Vec3 p = start.add(dir.scale(d));
                sl.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD,
                        p.x, p.y, p.z, 1, 0, 0, 0, 0.01);
            }
        }
    }

    /**
     * Executes a physical block break with high-fidelity data preservation.
     * 
     * SMART HARVEST (CarryOn Logic):
     * If the block is a container, we capture its NBT and clear its internal
     * inventory BEFORE the block is broken. This prevents the items from
     * spilling into the world and ensures the drone picks up a single
     * 'saved' item that can be perfectly restored later.
     */
    private void harvest(BlockPos pos, BlockState state) {
        if (level() instanceof ServerLevel sl) {
            com.example.ghostlib.util.GhostLogger.drone(
                    "Drone " + this.getId() + " harvesting " + state.getBlock().getName().getString() + " at " + pos);
            net.minecraft.world.level.block.entity.BlockEntity be = level().getBlockEntity(pos);

            // Smart Harvest: Handle Containers (Chests, IItemHandlers, etc.)
            net.neoforged.neoforge.items.IItemHandler handler = sl
                    .getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, pos, null);

            if (handler != null) {
                CompoundTag data = be != null ? be.saveWithoutMetadata(sl.registryAccess()) : new CompoundTag();
                ItemStack savedItem = new ItemStack(state.getBlock());

                // Only attach data if it's a logistical chest or container that we want to
                // preserve
                boolean isLogistics = state.getBlock() instanceof com.example.ghostlib.block.LogisticalChestBlock;
                if (isLogistics) {
                    savedItem.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(data));

                    // CRITICAL: Empty the handler before the block is removed.
                    // DO NOT VOID: Add extracted items to drone or world.
                    for (int i = 0; i < handler.getSlots(); i++) {
                        ItemStack extracted = handler.extractItem(i, 64, false);
                        if (!extracted.isEmpty()) {
                            ItemStack remainder = this.inventory.addItem(extracted);
                            if (!remainder.isEmpty())
                                Block.popResource(level(), pos, remainder);
                        }
                    }
                }

                ItemStack remainder = this.inventory.addItem(savedItem);
                if (!remainder.isEmpty()) {
                    Block.popResource(level(), pos, remainder);
                }
                return;
            }

            // Standard Harvest (Poles, Cobble, etc.)
            ItemStack tool = new ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE);
            boolean silk = this.getAttributeValue(ModAttributes.SILK_TOUCH) >= 1.0;
            if (silk) {
                tool.enchant(sl.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                        .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.SILK_TOUCH), 1);
            }

            net.minecraft.world.level.storage.loot.LootParams.Builder builder = new net.minecraft.world.level.storage.loot.LootParams.Builder(
                    sl)
                    .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN,
                            Vec3.atCenterOf(pos))
                    .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.TOOL, tool)
                    .withOptionalParameter(
                            net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_ENTITY, be);

            List<ItemStack> drops = state.getDrops(builder);

            if (drops.isEmpty() && silk && !state.isAir()) {
                ItemStack fallback = new ItemStack(state.getBlock());
                if (!fallback.isEmpty())
                    drops.add(fallback);
            }

            for (ItemStack drop : drops) {
                ItemStack remainder = this.inventory.addItem(drop);
                if (!remainder.isEmpty())
                    Block.popResource(level(), pos, remainder);
            }
        }
    }

    private int findBestSlot(net.minecraft.world.item.Item item) {
        int bestSlot = -1;

        // Check Ghost NBT if available (Context: Current Job)
        CompoundTag requiredNbt = null;
        if (currentJob != null && level().getBlockEntity(currentJob.pos()) instanceof GhostBlockEntity gbe) {
            requiredNbt = gbe.getCapturedNbt();
        }

        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            ItemStack stack = this.inventory.getItem(i);
            if (stack.is(item)) {
                // Priority 1: Exact NBT Match (Restoring a specific chest)
                if (requiredNbt != null && stack.has(DataComponents.BLOCK_ENTITY_DATA)) {
                    // Simple check: Does it have data? (Deep comparison is expensive/hard)
                    // We assume if the drone has a "Data" stack, it's the right one for the job if
                    // we are in "Restoration" mode.
                    return i;
                }

                // Priority 2: Empty/Standard Stack (Fresh build)
                if (bestSlot == -1)
                    bestSlot = i;
            }
        }
        return bestSlot;
    }

    private void handleDumpingItems() {
        if (getMode() == DroneMode.PORT) {
            Optional<BlockPos> p = getPortPos();
            if (p.isPresent()) {
                BlockPos targetPort = p.get();

                // Find a place to dump items
                BlockPos dumpTarget = findDumpTarget();
                if (dumpTarget != null) {
                    moveSmoothlyTo(Vec3.atCenterOf(dumpTarget), 0.7);
                    if (this.position().distanceTo(Vec3.atCenterOf(dumpTarget)) < 2.0) {
                        insertInto(dumpTarget);
                        if (isInventoryEmptyOfNonEggs()) {
                            this.droneState = DroneState.IDLE;
                        }
                    }
                    return;
                }

                // If no other storage, try home port
                moveSmoothlyTo(Vec3.atCenterOf(targetPort).add(0, 1.0, 0), 0.7);
                if (this.position().distanceTo(Vec3.atCenterOf(targetPort).add(0, 1.0, 0)) < 1.0) {
                    if (tryDumpAtPort(targetPort)) {
                        this.droneState = DroneState.CHARGING;
                    }
                }
            }
            return;
        }

        Player player = level().getNearestPlayer(this, 32);
        if (player == null) {
            // No player available, but we still have items to dump
            // Wait a bit then try again, or go back to idle if we're stuck
            if (!isInventoryEmpty()) {
                // We still have items to dump, stay in dumping state
                // Add a counter to avoid infinite loops
                if (idleChecks++ > 200) { // Reset after 10 seconds of trying
                    idleChecks = 0;
                    // If still can't find player, just drop items
                    for (int i = 0; i < inventory.getContainerSize(); i++) {
                        ItemStack stack = inventory.getItem(i);
                        if (!stack.isEmpty() && !stack.is(ModItems.DRONE_SPAWN_EGG.get())) {
                            Block.popResource(level(), blockPosition(), stack.copy());
                            inventory.setItem(i, ItemStack.EMPTY);
                        }
                    }
                }
            } else {
                this.droneState = DroneState.IDLE;
            }
            return;
        }
        Vec3 dumpPos = player.position().add(0, player.getEyeHeight(), 0);
        moveSmoothlyTo(dumpPos, 0.6);
        if (this.position().distanceTo(dumpPos) < 2.5) {
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty()) {
                    if (player.getInventory().add(stack)) {
                        inventory.setItem(i, ItemStack.EMPTY);
                    } else {
                        Block.popResource(level(), player.blockPosition(), stack.copy());
                        inventory.setItem(i, ItemStack.EMPTY);
                    }
                }
            }
            if (isInventoryEmpty()) {
                this.lingerTicks = 10;
                this.droneState = DroneState.IDLE;
            }
        }
    }

    private BlockPos findDumpTarget() {
        // 1. Network Search
        if (networkId != null) {
            Set<BlockPos> members = LogisticsNetworkManager.get(level()).getNetworkMembers(networkId);
            BlockPos bestStorage = null;
            BlockPos bestOther = null;

            for (BlockPos p : members) {
                if (p.equals(getPortPos().orElse(null))) continue;
                
                net.neoforged.neoforge.items.IItemHandler handler = level().getCapability(
                    net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, p, null);
                
                if (handler != null) {
                    boolean isStorage = false;
                    if (level().getBlockEntity(p) instanceof com.example.ghostlib.block.entity.LogisticalChestBlockEntity lc) {
                        var type = lc.getChestType();
                        if (type == com.example.ghostlib.block.LogisticalChestBlock.ChestType.STORAGE ||
                            type == com.example.ghostlib.block.LogisticalChestBlock.ChestType.BUFFER) {
                            isStorage = true;
                        }
                    }

                    // Check if has space
                    boolean hasSpace = false;
                    for (int i = 0; i < inventory.getContainerSize(); i++) {
                        ItemStack s = inventory.getItem(i);
                        if (!s.isEmpty() && !s.is(ModItems.DRONE_SPAWN_EGG.get())) {
                            if (net.neoforged.neoforge.items.ItemHandlerHelper.insertItemStacked(handler, s, true).getCount() < s.getCount()) {
                                hasSpace = true;
                                break;
                            }
                        }
                    }

                    if (hasSpace) {
                        if (isStorage) {
                            if (bestStorage == null || p.distSqr(this.blockPosition()) < bestStorage.distSqr(this.blockPosition()))
                                bestStorage = p.immutable();
                        } else {
                            if (bestOther == null || p.distSqr(this.blockPosition()) < bestOther.distSqr(this.blockPosition()))
                                bestOther = p.immutable();
                        }
                    }
                }
            }
            if (bestStorage != null) return bestStorage;
            if (bestOther != null) return bestOther;
        }

        // 2. Local Search
        int radius = 16;
        BlockPos center = this.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -4, -radius), center.offset(radius, 4, radius))) {
            if (pos.equals(getPortPos().orElse(null))) continue;
            
            net.neoforged.neoforge.items.IItemHandler handler = level().getCapability(
                net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, pos, null);
            
            if (handler != null) {
                // Check if has space
                for (int i = 0; i < inventory.getContainerSize(); i++) {
                    ItemStack s = inventory.getItem(i);
                    if (!s.isEmpty() && !s.is(ModItems.DRONE_SPAWN_EGG.get())) {
                        if (net.neoforged.neoforge.items.ItemHandlerHelper.insertItemStacked(handler, s, true).getCount() < s.getCount()) {
                            return pos.immutable();
                        }
                    }
                }
            }
        }
        return null;
    }

    private void insertInto(BlockPos p) {
        net.neoforged.neoforge.items.IItemHandler handler = level()
                .getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, p, null);
        if (handler != null) {
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty() && !stack.is(ModItems.DRONE_SPAWN_EGG.get())) {
                    ItemStack remainder = net.neoforged.neoforge.items.ItemHandlerHelper.insertItemStacked(handler,
                            stack, false);
                    inventory.setItem(i, remainder);
                }
            }
        }
    }

    private boolean isInventoryEmptyOfNonEggs() {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (!s.isEmpty() && !s.is(ModItems.DRONE_SPAWN_EGG.get()))
                return false;
        }
        return true;
    }

    private boolean tryDumpAtPort(BlockPos pos) {
        if (level().getBlockEntity(pos) instanceof IDronePort dp) {
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty() && stack.is(ModItems.DRONE_SPAWN_EGG.get())) {
                    inventory.setItem(i, dp.insertItem(stack, false));
                }
            }
            // Return true if we are empty OR if we only have eggs that fit
            // But for "Dumping" state, we consider success if we are empty of NON-eggs
            // However, this method is used to see if we can "Clear" ourselves.
            // If we have non-eggs left, we failed.
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                if (!inventory.getItem(i).isEmpty())
                    return false;
            }
            return true;
        }
        return false;
    }

    public void setHomePos(BlockPos pos) {
        this.setPort(pos);
    }

    private void returnToHome() {
        if (getPortPos().isPresent()) {
            BlockPos p = getPortPos().get();
            if (level().getBlockEntity(p) instanceof IDronePort port) {
                ItemStack selfStack = new ItemStack(ModItems.DRONE_SPAWN_EGG.get());
                ItemStack remainder = port.insertItem(selfStack, true);
                if (remainder.isEmpty()) {
                    moveSmoothlyTo(Vec3.atCenterOf(p).add(0, 2, 0), 0.8);
                    if (this.position().distanceTo(Vec3.atCenterOf(p).add(0, 2, 0)) < 1.0) {
                        port.insertItem(selfStack, false);
                        for (int i = 0; i < inventory.getContainerSize(); i++) {
                            ItemStack s = inventory.getItem(i);
                            if (!s.isEmpty())
                                Block.popResource(level(), p, s);
                        }
                        this.discard();
                    }
                    return;
                }
            }
        }
        Player player = this.level().getNearestPlayer(this, 32);
        if (player != null)
            returnToPlayer(player);
    }

    private void returnToPlayer(Player player) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty())
                Block.popResource(level(), blockPosition(), stack);
        }
        ItemStack egg = new ItemStack(ModItems.DRONE_SPAWN_EGG.get());
        if (!player.getInventory().add(egg))
            Block.popResource(level(), blockPosition(), egg);
        this.discard();
    }

    private void moveSmoothlyTo(Vec3 target, double speed) {
        double actualSpeed = lowPowerMode ? speed * 0.2 : speed;
        Vec3 dir = target.subtract(this.position());
        double dist = dir.length();
        if (dist > 0.01) {
            double approachSpeed = actualSpeed;
            if (dist < 2.0)
                approachSpeed *= (dist / 2.0);
            this.setDeltaMovement(dir.scale(approachSpeed / dist));
            if (droneState != DroneState.IDLE)
                this.getLookControl().setLookAt(target.x, target.y, target.z);
        } else {
            this.setDeltaMovement(Vec3.ZERO);
        }
    }

    private void resetToIdle() {
        if (currentJob != null)
            GhostJobManager.get(level()).releaseJob(currentJob.pos(), this.getUUID());
        this.currentJob = null;
        this.droneState = DroneState.IDLE;
    }

    private boolean isInventoryEmpty() {
        for (int i = 0; i < inventory.getContainerSize(); i++)
            if (!inventory.getItem(i).isEmpty())
                return false;
        return true;
    }

    private boolean hasSpace() {
        for (int i = 0; i < inventory.getContainerSize(); i++)
            if (inventory.getItem(i).isEmpty())
                return true;
        return false;
    }

    public boolean hasItemInInventory(ItemStack stack) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (!s.isEmpty() && s.is(stack.getItem()))
                return true;
        }
        return false;
    }

    private void consumeFromInventory(ItemStack stack) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (!s.isEmpty() && s.is(stack.getItem())) {
                s.shrink(1);
                return;
            }
        }
    }
}