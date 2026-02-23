package com.example.ghostlib.entity;

import com.example.ghostlib.registry.ModAttributes;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.component.CustomData;

import com.example.ghostlib.GhostLib;
import com.example.ghostlib.api.IDronePort;
import com.example.ghostlib.block.GhostBlock;
import com.example.ghostlib.block.entity.GhostBlockEntity;
import com.example.ghostlib.registry.ModBlocks;
import com.example.ghostlib.registry.ModItems;
import com.example.ghostlib.util.GhostJobManager;
import com.example.ghostlib.util.GhostLogger;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Drone FSM — state transition summary:
 *
 * <pre>
 *  IDLE ──────────────────┬─► FINDING_JOB
 *   │ (recall / low-e)    │
 *   ▼                     │
 * RETURNING_TO_OWNER      │
 * CHARGING ◄──────────────┘
 *
 * FINDING_JOB ──► TRAVELING_FETCH ──► TRAVELING_BUILD ──► IDLE (lingerTicks)
 *             └─► TRAVELING_CLEAR ──────────────────────► IDLE / DUMPING_ITEMS
 *
 * Any state ──► DUMPING_ITEMS ──► IDLE / CHARGING
 * </pre>
 *
 * <h2>Key invariants</h2>
 * <ul>
 *   <li>{@code currentJob != null} iff state is TRAVELING_* or DUMPING (with active job).</li>
 *   <li>{@code lowPowerMode} blocks entry into FINDING_JOB. Finish current job first.</li>
 *   <li>Post-build always goes to IDLE (with {@code lingerTicks}), never FINDING_JOB directly.</li>
 *   <li>{@code recallTicks} tracks idle time for recall; {@code dumpRetryTicks} tracks
 *       give-up counter in DUMPING_ITEMS. They are independent fields.</li>
 * </ul>
 */
public class DroneEntity extends PathfinderMob {

    // -------------------------------------------------------------------------
    // Synced data
    // -------------------------------------------------------------------------

    private static final EntityDataAccessor<Byte>             DATA_MODE       = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Optional<BlockPos>> DATA_PORT_POS = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.OPTIONAL_BLOCK_POS);
    private static final EntityDataAccessor<Optional<UUID>>   DATA_OWNER_UUID = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.OPTIONAL_UUID);

    // -------------------------------------------------------------------------
    // Enums
    // -------------------------------------------------------------------------

    public enum DroneMode {
        PLAYER((byte) 0), PORT((byte) 1);
        final byte id;
        DroneMode(byte id) { this.id = id; }
        static DroneMode byId(byte id) { return id == 1 ? PORT : PLAYER; }
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

    // -------------------------------------------------------------------------
    // FSM state
    // -------------------------------------------------------------------------

    private DroneState droneState = DroneState.IDLE;
    private GhostJobManager.Job currentJob = null;

    // ── Timers (independent; see invariant notes above) ──────────────────────
    /** Ticks spent idle; used for recall / self-store triggering. */
    private int recallTicks = 0;
    /** Ticks spent in DUMPING_ITEMS with no reachable player/storage. */
    private int dumpRetryTicks = 0;
    /** Cooldown between player-inventory scans during fetch. One check per 10 t. */
    private int fetchPlayerCooldown = 0;
    /** Cooldown for self-store attempts to prevent spam when port is full. */
    private int selfStoreCooldown = 0;
    /** Remaining ticks of post-action hover before accepting new jobs. */
    private int lingerTicks = 0;
    /** Ticks spent waiting (sleep). FSM skips tick while > 0. */
    private int waitTicks = 0;

    /** Job-search backoff (ticks between IDLE→FINDING_JOB checks). */
    private int noJobBackoff = 0;
    private static final int MAX_BACKOFF = 10;

    /** Watchdog — how many ticks a job may be active before forced abort. */
    private int jobWatchdog = 0;
    private static final int WATCHDOG_LIMIT = 1200; // 60 s; covers 64-block travel + fetch + build

    // -------------------------------------------------------------------------
    // Resources
    // -------------------------------------------------------------------------

    private final SimpleContainer inventory = new SimpleContainer(9);
    private int energy = 10000;
    private static final int FLY_COST  = 1;
    private static final int WORK_COST = 50;
    private boolean lowPowerMode = false;

    /** Network ID cached from port; refreshed every 100 t. */
    private Integer networkId = null;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public DroneEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
        this.noPhysics = true;
    }

    // -------------------------------------------------------------------------
    // Capability / validity
    // -------------------------------------------------------------------------

    @Override public boolean isPushable()           { return false; }
    @Override public boolean canBeCollidedWith()    { return false; }

    @Override
    public boolean isInvulnerableTo(net.minecraft.world.damagesource.DamageSource source) {
        if (source.is(net.minecraft.world.damagesource.DamageTypes.IN_WALL)
                || source.is(net.minecraft.world.damagesource.DamageTypes.CRAMMING)) return true;
        return super.isInvulnerableTo(source);
    }

    // -------------------------------------------------------------------------
    // Synched data
    // -------------------------------------------------------------------------

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_MODE, (byte) 0);
        builder.define(DATA_PORT_POS, Optional.empty());
        builder.define(DATA_OWNER_UUID, Optional.empty());
    }

    public DroneMode  getMode()       { return DroneMode.byId(this.entityData.get(DATA_MODE)); }
    public Optional<BlockPos> getPortPos()  { return this.entityData.get(DATA_PORT_POS); }
    public UUID       getOwnerUUID()  { return this.entityData.get(DATA_OWNER_UUID).orElse(null); }

    public void setOwner(Player player) {
        this.entityData.set(DATA_MODE,       DroneMode.PLAYER.id);
        this.entityData.set(DATA_OWNER_UUID, Optional.of(player.getUUID()));
        this.entityData.set(DATA_PORT_POS,   Optional.empty());
    }

    public void setPort(BlockPos pos) {
        this.entityData.set(DATA_MODE,       DroneMode.PORT.id);
        this.entityData.set(DATA_PORT_POS,   Optional.of(pos));
        this.entityData.set(DATA_OWNER_UUID, Optional.empty());
    }

    public void setOwnerUUID(UUID uuid) {
        this.entityData.set(DATA_OWNER_UUID, Optional.ofNullable(uuid));
    }

    // -------------------------------------------------------------------------
    // Attribute definition
    // -------------------------------------------------------------------------

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, com.example.ghostlib.config.GhostLibConfig.DRONE_MAX_HEALTH)
                .add(Attributes.MOVEMENT_SPEED, 0.5D)
                .add(ModAttributes.INTERACTION_RANGE)
                .add(ModAttributes.SEARCH_RANGE)
                .add(ModAttributes.WORK_SPEED)
                .add(ModAttributes.MAX_ENERGY)
                .add(ModAttributes.ENERGY_EFFICIENCY)
                .add(ModAttributes.SILK_TOUCH);
    }

    // -------------------------------------------------------------------------
    // Movement override
    // -------------------------------------------------------------------------

    @Override
    public void travel(Vec3 travelVector) {
        if (this.isEffectiveAi() || this.isControlledByLocalInstance()) {
            if (this.noPhysics) {
                Vec3 delta = this.getDeltaMovement();
                this.move(net.minecraft.world.entity.MoverType.SELF, delta);
                this.setDeltaMovement(delta.scale(0.91));
            } else {
                super.travel(travelVector);
            }
        }
    }

    // -------------------------------------------------------------------------
    // NBT persistence
    // -------------------------------------------------------------------------

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putByte("DroneMode", this.entityData.get(DATA_MODE));
        if (getOwnerUUID() != null) tag.putUUID("Owner", getOwnerUUID());
        getPortPos().ifPresent(p -> tag.putLong("PortPos", p.asLong()));
        tag.putInt("Energy", this.energy);
        if (this.networkId != null) tag.putInt("NetworkId", this.networkId);

        ListTag inv = new ListTag();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (!s.isEmpty()) {
                CompoundTag it = new CompoundTag();
                it.putByte("Slot", (byte) i);
                it.put("Item", s.save(this.level().registryAccess()));
                inv.add(it);
            }
        }
        tag.put("Inventory", inv);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("DroneMode"))   this.entityData.set(DATA_MODE, tag.getByte("DroneMode"));
        if (tag.hasUUID("Owner"))        setOwnerUUID(tag.getUUID("Owner"));
        if (tag.contains("PortPos"))     this.entityData.set(DATA_PORT_POS, Optional.of(BlockPos.of(tag.getLong("PortPos"))));
        if (tag.contains("Energy"))      this.energy = tag.getInt("Energy");
        if (tag.contains("NetworkId"))   this.networkId = tag.getInt("NetworkId");

        if (tag.contains("Inventory")) {
            ListTag inv = tag.getList("Inventory", 10);
            for (int i = 0; i < inv.size(); i++) {
                CompoundTag it = inv.getCompound(i);
                int slot = it.getByte("Slot") & 255;
                if (slot < inventory.getContainerSize() && it.contains("Item")) {
                    ItemStack.parse(this.level().registryAccess(), it.getCompound("Item"))
                            .ifPresent(s -> inventory.setItem(slot, s));
                }
            }
        }
    }

    public void saveToItem(ItemStack stack) {
        CompoundTag tag = new CompoundTag();
        this.saveWithoutId(tag);
        tag.putString("id", net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getKey(this.getType()).toString());
        tag.remove("Pos");
        tag.remove("Motion");
        tag.remove("Rotation");
        tag.remove("UUID");
        stack.set(DataComponents.ENTITY_DATA, CustomData.of(tag));
    }

    // -------------------------------------------------------------------------
    // Death drops
    // -------------------------------------------------------------------------

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, net.minecraft.world.damagesource.DamageSource source, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, source, recentlyHit);
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (!s.isEmpty()) Block.popResource(level, blockPosition(), s);
        }
        Block.popResource(level, blockPosition(), new ItemStack(ModItems.DRONE_SPAWN_EGG.get()));
    }

    // -------------------------------------------------------------------------
    // Main tick
    // -------------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) return;

        // ── Sanity: job held in a non-traveling state ─────────────────────────
        if (currentJob != null && (droneState == DroneState.IDLE || droneState == DroneState.FINDING_JOB)) {
            GhostLogger.drone("Drone " + this.getId() + ": stale job in " + droneState + ", forcing release.");
            releaseCurrentJob();
            droneState = DroneState.IDLE;
        }

        consumeEnergy();

        if (waitTicks > 0) { waitTicks--; return; }
        if (fetchPlayerCooldown > 0) fetchPlayerCooldown--;
        if (selfStoreCooldown > 0)   selfStoreCooldown--;

        // ── Watchdog ──────────────────────────────────────────────────────────
        if (currentJob != null) {
            // Ledger validation every 10 t
            if (this.tickCount % 10 == 0) {
                com.example.ghostlib.history.BlockSnapshot intent =
                        com.example.ghostlib.history.WorldHistoryManager.get(level()).getIntendedState(currentJob.pos());
                if (intent != null) {
                    boolean valid = switch (currentJob.type()) {
                        case CONSTRUCTION       -> intent.state().equals(currentJob.targetAfter());
                        case DIRECT_DECONSTRUCT,
                             GHOST_REMOVAL      -> intent.state().isAir();
                    };
                    if (!valid) {
                        GhostLogger.drone("Drone " + this.getId() + ": ledger change invalidated job. Aborting.");
                        resetToIdle();
                        return;
                    }
                }
            }

            if (++jobWatchdog > WATCHDOG_LIMIT) {
                GhostLogger.drone("Drone " + this.getId() + ": watchdog expired at " + currentJob.pos() + ". Releasing.");
                resetToIdle();
                return;
            }
        } else {
            jobWatchdog = 0;
        }

        // ── Port orphan check ─────────────────────────────────────────────────
        if (getMode() == DroneMode.PORT && !validateOrRehomePort()) return;

        // ── External assignment revocation ────────────────────────────────────
        if (currentJob != null
                && droneState != DroneState.IDLE
                && droneState != DroneState.DUMPING_ITEMS
                && droneState != DroneState.CHARGING
                && !GhostJobManager.get(level()).isAssignedTo(currentJob.pos(), this.getUUID())) {
            releaseCurrentJob();
            droneState = isInventoryEmpty() ? DroneState.IDLE : DroneState.DUMPING_ITEMS;
        }

        // ── Dispatch ─────────────────────────────────────────────────────────
        switch (droneState) {
            case IDLE               -> handleIdle();
            case FINDING_JOB        -> handleFindingJob();
            case TRAVELING_CLEAR    -> handleTravelingClear();
            case TRAVELING_FETCH    -> handleTravelingFetch();
            case TRAVELING_BUILD    -> handleTravelingBuild();
            case DUMPING_ITEMS      -> handleDumpingItems();
            case CHARGING           -> handleCharging();
            case RETURNING_TO_OWNER -> handleReturningToOwner();
        }
    }

    // =========================================================================
    // FSM handlers
    // =========================================================================

    // ── IDLE ─────────────────────────────────────────────────────────────────

    private void handleIdle() {
        // Low energy → charge/recall first
        if (energy < getAttributeValue(ModAttributes.MAX_ENERGY) * 0.2) {
            if (getMode() == DroneMode.PORT)    { droneState = DroneState.CHARGING; return; }
            else                                { droneState = DroneState.RETURNING_TO_OWNER; return; }
        }

        // Non-empty inventory - check if we have construction items
        if (!isInventoryEmpty()) {
            // For PORT drones: check if items are for construction jobs
            if (getMode() == DroneMode.PORT) {
                // Check if we have items that match a construction job
                GhostJobManager.Job job = GhostJobManager.get(level()).requestJob(this.blockPosition(), this.getUUID(), false);
                if (job != null && job.type() == GhostJobManager.JobType.CONSTRUCTION
                        && hasItemInInventory(new ItemStack(job.targetAfter().getBlock().asItem()))) {
                    currentJob = job;
                    droneState = DroneState.TRAVELING_BUILD;
                    return;
                }
                if (job != null) GhostJobManager.get(level()).releaseJob(job.pos(), this.getUUID());
                // No matching job → dump items
                droneState = DroneState.DUMPING_ITEMS;
                return;
            } else {
                // PLAYER mode: same logic
                GhostJobManager.Job job = GhostJobManager.get(level()).requestJob(this.blockPosition(), this.getUUID(), false);
                if (job != null && job.type() == GhostJobManager.JobType.CONSTRUCTION
                        && hasItemInInventory(new ItemStack(job.targetAfter().getBlock().asItem()))) {
                    currentJob = job;
                    droneState = DroneState.TRAVELING_BUILD;
                } else {
                    if (job != null) GhostJobManager.get(level()).releaseJob(job.pos(), this.getUUID());
                    droneState = DroneState.DUMPING_ITEMS;
                }
                return;
            }
        }

        // Linger hover
        if (lingerTicks > 0) {
            lingerTicks--;
            this.setDeltaMovement(this.getDeltaMovement().scale(0.8));
            return;
        }

        // Hover near owner / port
        hoverPassively();

        recallTicks++;

        // PORT: self-store when idle long enough and not low-energy
        if (getMode() == DroneMode.PORT && recallTicks > 100
                && isInventoryEmpty() && energy > getAttributeValue(ModAttributes.MAX_ENERGY) * 0.5
                && selfStoreCooldown == 0) {
            droneState = DroneState.CHARGING; // go dock → self-store attempt in handleCharging
            return;
        }

        // PLAYER: recall after ~15 s
        if (getMode() == DroneMode.PLAYER && recallTicks > 300) {
            if (getOwnerUUID() != null) { droneState = DroneState.RETURNING_TO_OWNER; return; }
        }

        // Don't search for new jobs while low on power
        if (lowPowerMode) return;

        int checkInterval = getMode() == DroneMode.PORT ? 10 : Math.min(100, 20 + noJobBackoff * 5);
        if (this.tickCount % checkInterval == 0) {
            droneState = DroneState.FINDING_JOB;
        }
    }

    // ── FINDING_JOB ──────────────────────────────────────────────────────────

    private void handleFindingJob() {
        if (currentJob != null) return;

        // Low energy check before requesting (avoid claiming a job we can't finish)
        if (energy < getAttributeValue(ModAttributes.MAX_ENERGY) * 0.2) {
            droneState = getMode() == DroneMode.PORT ? DroneState.CHARGING : DroneState.RETURNING_TO_OWNER;
            return;
        }

        GhostJobManager.Job job = GhostJobManager.get(level()).requestJob(this.blockPosition(), this.getUUID(), true);

        if (job == null) {
            noJobBackoff = Math.min(noJobBackoff + 1, MAX_BACKOFF);
            droneState = DroneState.IDLE;
            return;
        }

        currentJob  = job;
        recallTicks = 0;
        lingerTicks = 0;
        noJobBackoff = 0;

        switch (job.type()) {
            case CONSTRUCTION -> {
                ItemStack required = new ItemStack(job.targetAfter().getBlock().asItem());
                boolean hasItem = hasItemInInventory(required);
                droneState = hasItem ? DroneState.TRAVELING_BUILD : DroneState.TRAVELING_FETCH;
                updateGhostState(job.pos(), hasItem ? GhostBlockEntity.GhostState.INCOMING : GhostBlockEntity.GhostState.FETCHING);
            }
            case DIRECT_DECONSTRUCT, GHOST_REMOVAL -> {
                droneState = DroneState.TRAVELING_CLEAR;
                if (job.type() == GhostJobManager.JobType.GHOST_REMOVAL) {
                    updateGhostState(job.pos(), GhostBlockEntity.GhostState.REMOVING);
                }
            }
        }
    }

    // ── TRAVELING_FETCH ───────────────────────────────────────────────────────

    private void handleTravelingFetch() {
        if (!validateActiveJob()) return;

        ItemStack required = new ItemStack(currentJob.targetAfter().getBlock().asItem());
        if (hasItemInInventory(required)) {
            droneState = DroneState.TRAVELING_BUILD;
            return;
        }

        // PORT: go home to extract
        if (getMode() == DroneMode.PORT && getPortPos().isPresent()) {
            BlockPos portPos = getPortPos().get();
            moveSmoothlyTo(Vec3.atCenterOf(portPos).add(0, 1, 0), 0.7);

            if (this.position().distanceTo(Vec3.atCenterOf(portPos).add(0, 1, 0)) < 2.0) {
                if (level().getBlockEntity(portPos) instanceof IDronePort dp) {
                    ItemStack extracted = dp.extractItem(required, 1, false);
                    if (!extracted.isEmpty()) {
                        // Race condition check - make sure we still have space
                        if (hasSpace()) {
                            inventory.addItem(extracted);
                            droneState = DroneState.TRAVELING_BUILD;
                            updateGhostState(currentJob.pos(), GhostBlockEntity.GhostState.INCOMING);
                        } else {
                            // No space - dump first then retry
                            droneState = DroneState.DUMPING_ITEMS;
                        }
                        return;
                    }
                }
            }
            return;
        }

        // Fallback: Player inventory (last resort)
        Player player = level().getNearestPlayer(this, 64);
        if (player != null) {
            Vec3 fetchPos = player.position().add(0, player.getEyeHeight(), 0);
            moveSmoothlyTo(fetchPos, 0.7);

            if (this.position().distanceTo(fetchPos) < 2.0) {
                if (fetchPlayerCooldown > 0) return;
                fetchPlayerCooldown = 10;

                int slot = findPlayerItemSlot(player, required);
                if (slot != -1) {
                    if (!hasSpace()) {
                        droneState = DroneState.DUMPING_ITEMS;
                        releaseCurrentJob();
                        return;
                    }

                    ItemStack extracted = player.getInventory().getItem(slot).copy();
                    extracted.setCount(1);
                    player.getInventory().removeItem(slot, 1);
                    inventory.addItem(extracted);
                    droneState = DroneState.TRAVELING_BUILD;
                    updateGhostState(currentJob.pos(), GhostBlockEntity.GhostState.INCOMING);
                    return;
                }
            }
            return;
        }

        // No items available - mark MISSING_ITEMS but allow retry after cooldown
        GhostLogger.drone("Drone " + this.getId() + ": no items for " + required + ". Marking MISSING_ITEMS with retry.");
        updateGhostState(currentJob.pos(), GhostBlockEntity.GhostState.MISSING_ITEMS);
        GhostJobManager.get(level()).releaseJob(currentJob.pos(), this.getUUID());
        currentJob = null;
        droneState = DroneState.IDLE;
        waitTicks = 200; // 10 second cooldown before retry
    }

    // ── TRAVELING_BUILD ───────────────────────────────────────────────────────

    private void handleTravelingBuild() {
        if (!validateActiveJob()) return;

        ItemStack required = new ItemStack(currentJob.targetAfter().getBlock().asItem());
        if (!hasItemInInventory(required)) { droneState = DroneState.TRAVELING_FETCH; return; }

        moveSmoothlyTo(currentJob.pos().getCenter(), 0.7);
        double dist = this.position().distanceTo(currentJob.pos().getCenter());
        if (dist >= this.getAttributeValue(ModAttributes.INTERACTION_RANGE)) return;

        BlockPos pos         = currentJob.pos();
        BlockState worldState = level().getBlockState(pos);

        // Beam
        if (com.example.ghostlib.config.GhostLibConfig.RENDER_DRONE_BEAMS) {
            spawnBeam(this.position().add(0, 0.2, 0), Vec3.atCenterOf(pos), 0.2f, 0.2f, 1.0f);
        }

        // Already correct
        if (worldState.equals(currentJob.targetAfter())) {
            GhostJobManager.get(level()).completeJob(pos, level());
            finishJob();
            return;
        }

        // Obstruction — real block in the way
        if (!worldState.isAir() && !worldState.canBeReplaced() && !(worldState.getBlock() instanceof GhostBlock)) {
            GhostLogger.drone("Drone " + this.getId() + ": obstruction at " + pos + ", registering deconstruct.");
            GhostJobManager.get(level()).registerDirectDeconstruct(pos, currentJob.targetAfter(), level());
            releaseCurrentJob();
            droneState = DroneState.FINDING_JOB;
            return;
        }

        // Double-check job still ours
        if (!GhostJobManager.get(level()).jobExistsAt(pos)) {
            releaseCurrentJob();
            droneState = DroneState.FINDING_JOB;
            return;
        }

        int slot = findBestSlot(currentJob.targetAfter().getBlock().asItem());
        if (slot == -1) { droneState = DroneState.TRAVELING_FETCH; return; }

        ItemStack usedStack = inventory.getItem(slot).split(1);
        if (inventory.getItem(slot).isEmpty()) inventory.setItem(slot, ItemStack.EMPTY);

        // Capture NBT before we replace the ghost
        CompoundTag nbtToApply = null;
        if (level().getBlockEntity(pos) instanceof GhostBlockEntity gbe) {
            nbtToApply = gbe.getCapturedNbt();
        }

        boolean placed = level().setBlock(pos, currentJob.targetAfter(), 3);
        if (!placed) {
            GhostLogger.drone("Drone " + this.getId() + ": setBlock FAILED at " + pos + ". Rollback.");
            inventory.addItem(usedStack);
            releaseCurrentJob();
            droneState = DroneState.FINDING_JOB;
            return;
        }

        currentJob.targetAfter().getBlock().setPlacedBy(level(), pos, currentJob.targetAfter(), this, usedStack);

        // Restore item NBT (never blueprint NBT — that would dupe)
        net.minecraft.world.level.block.entity.BlockEntity newBe = level().getBlockEntity(pos);
        if (newBe != null && usedStack.has(DataComponents.BLOCK_ENTITY_DATA)) {
            CustomData data = usedStack.get(DataComponents.BLOCK_ENTITY_DATA);
            if (data != null) {
                CompoundTag beTag = data.copyTag();
                beTag.putInt("x", pos.getX());
                beTag.putInt("y", pos.getY());
                beTag.putInt("z", pos.getZ());
                newBe.loadWithComponents(beTag, level().registryAccess());
                newBe.setChanged();
            }
        }

        this.playSound(com.example.ghostlib.registry.ModSounds.DRONE_WORK.get(), 1.0f, 1.0f);
        GhostJobManager.get(level()).completeJob(pos, level());

        double efficiency = getAttributeValue(ModAttributes.ENERGY_EFFICIENCY);
        energy -= (int) (WORK_COST / efficiency);

        finishJob(); // → IDLE with lingerTicks
    }

    // ── TRAVELING_CLEAR ───────────────────────────────────────────────────────

    private void handleTravelingClear() {
        if (!validateActiveJob()) return;

        moveSmoothlyTo(currentJob.pos().getCenter(), 0.6);
        if (this.position().distanceTo(currentJob.pos().getCenter())
                >= this.getAttributeValue(ModAttributes.INTERACTION_RANGE)) return;

        BlockPos  pos      = currentJob.pos();
        BlockState existing = level().getBlockState(pos);

        // Safety: never break a GhostBlock — the job is invalid
        if (existing.getBlock() instanceof GhostBlock) {
            GhostLogger.drone("Drone " + this.getId() + ": targeted GhostBlock for deconstruct. Aborting.");
            GhostJobManager.get(level()).completeJob(pos, level());
            currentJob = null;
            droneState = isInventoryEmpty() ? DroneState.IDLE : DroneState.DUMPING_ITEMS;
            return;
        }

        if (!GhostJobManager.get(level()).jobExistsAt(pos)) {
            releaseCurrentJob();
            droneState = DroneState.FINDING_JOB;
            return;
        }

        if (com.example.ghostlib.config.GhostLibConfig.RENDER_DRONE_BEAMS) {
            spawnBeam(this.position().add(0, 0.2, 0), Vec3.atCenterOf(pos), 1.0f, 0.2f, 0.2f);
        }

        harvest(pos, existing);
        level().setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        GhostJobManager.get(level()).completeJob(pos, level());

        // Place follow-up marker if the job specifies a next state
        BlockState targetAfter = currentJob.targetAfter();
        BlockState finalState  = currentJob.finalState();
        if (targetAfter != null && !targetAfter.isAir()) {
            level().setBlock(pos, targetAfter, 3);
            if (targetAfter.getBlock() instanceof GhostBlock
                    && level().getBlockEntity(pos) instanceof GhostBlockEntity gbe
                    && finalState != null && !finalState.isAir()) {
                gbe.setTargetState(finalState);
                gbe.setState(GhostBlockEntity.GhostState.UNASSIGNED);
            }
        }

        double efficiency = getAttributeValue(ModAttributes.ENERGY_EFFICIENCY);
        energy -= (int) (WORK_COST / efficiency);

        currentJob  = null;
        lingerTicks = 10;
        droneState  = isInventoryEmpty() ? DroneState.IDLE : DroneState.DUMPING_ITEMS;
    }

    // ── DUMPING_ITEMS ─────────────────────────────────────────────────────────

    private void handleDumpingItems() {
        if (getMode() == DroneMode.PORT) {
            BlockPos dumpTarget = findDumpTarget();
            if (dumpTarget != null) {
                moveSmoothlyTo(Vec3.atCenterOf(dumpTarget), 0.7);
                if (this.position().distanceTo(Vec3.atCenterOf(dumpTarget)) < 2.0) {
                    insertInto(dumpTarget);
                    if (isInventoryEmptyOfNonEggs()) droneState = DroneState.IDLE;
                }
                return;
            }

            // Fall back to home port
            getPortPos().ifPresent(portPos -> {
                moveSmoothlyTo(Vec3.atCenterOf(portPos).add(0, 1.0, 0), 0.7);
                if (this.position().distanceTo(Vec3.atCenterOf(portPos).add(0, 1.0, 0)) < 1.0) {
                    if (tryDumpAtPort(portPos)) droneState = DroneState.CHARGING;
                }
            });
            return;
        }

        // PLAYER mode
        Player player = level().getNearestPlayer(this, 32);
        if (player == null) {
            if (++dumpRetryTicks > 200) {
                dumpRetryTicks = 0;
                // No player reachable for 10 s — drop items and give up
                for (int i = 0; i < inventory.getContainerSize(); i++) {
                    ItemStack s = inventory.getItem(i);
                    if (!s.isEmpty() && !s.is(ModItems.DRONE_SPAWN_EGG.get())) {
                        Block.popResource(level(), blockPosition(), s.copy());
                        inventory.setItem(i, ItemStack.EMPTY);
                    }
                }
                droneState = DroneState.IDLE;
            }
            return;
        }

        dumpRetryTicks = 0;
        Vec3 dumpPos = player.position().add(0, player.getEyeHeight(), 0);
        moveSmoothlyTo(dumpPos, 0.6);

        if (this.position().distanceTo(dumpPos) < 2.5) {
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack s = inventory.getItem(i);
                if (!s.isEmpty()) {
                    if (player.getInventory().add(s)) {
                        inventory.setItem(i, ItemStack.EMPTY);
                    } else {
                        Block.popResource(level(), player.blockPosition(), s.copy());
                        inventory.setItem(i, ItemStack.EMPTY);
                    }
                }
            }
            if (isInventoryEmpty()) {
                lingerTicks = 10;
                droneState  = DroneState.IDLE;
                recallTicks = 0;
            }
        }
    }

    // ── CHARGING ─────────────────────────────────────────────────────────────

    private void handleCharging() {
        if (getMode() != DroneMode.PORT) { droneState = DroneState.IDLE; return; }

        BlockPos targetPort = findNearestNetworkPort();
        if (targetPort == null) targetPort = getPortPos().orElse(null);
        if (targetPort == null) { droneState = DroneState.IDLE; return; }

        // Rehome to nearest port in network
        if (!targetPort.equals(getPortPos().orElse(null))) setPort(targetPort);

        Vec3 dockPos = Vec3.atCenterOf(targetPort).add(0, 0.5, 0);
        moveSmoothlyTo(dockPos, 1.0);

        if (this.position().distanceTo(dockPos) < 1.0) {
            if (level().getBlockEntity(targetPort) instanceof IDronePort dp) {
                int maxE = (int) getAttributeValue(ModAttributes.MAX_ENERGY);
                energy = Math.min(energy + dp.chargeDrone(2000, false), maxE);

                // Dump while docked
                if (!isInventoryEmpty()) tryDumpAtPort(targetPort);

                // Self-store when fully charged and idle
                if (energy >= maxE * 0.9 && isInventoryEmpty() && selfStoreCooldown == 0) {
                    ItemStack self = new ItemStack(ModItems.DRONE_SPAWN_EGG.get());
                    this.saveToItem(self);
                    if (dp.insertItem(self, true).isEmpty()) {
                        dp.insertItem(self, false);
                        this.discard();
                        return;
                    }
                    // Port full — back off to avoid spam
                    selfStoreCooldown = 200;
                }

                if (energy >= maxE * 0.9) {
                    recallTicks = 0;
                    droneState  = DroneState.IDLE;
                    this.setDeltaMovement(0, 0.5, 0);
                }
            }
        }
    }

    // ── RETURNING_TO_OWNER ────────────────────────────────────────────────────

    private void handleReturningToOwner() {
        UUID ownerId = getOwnerUUID();
        if (ownerId == null) { droneState = DroneState.IDLE; return; }

        Player owner = level().getPlayerByUUID(ownerId);
        if (owner == null) { droneState = DroneState.IDLE; return; }

        if (this.distanceToSqr(owner) < 9.0D) {
            ItemStack droneItem = new ItemStack(ModItems.DRONE_SPAWN_EGG.get());
            this.saveToItem(droneItem);
            if (owner.getInventory().add(droneItem)) {
                this.discard();
                level().playSound(null, this.blockPosition(),
                        net.minecraft.sounds.SoundEvents.ITEM_PICKUP,
                        net.minecraft.sounds.SoundSource.NEUTRAL, 1.0f, 1.0f);
            } else {
                // Inventory full — go back to idling near owner
                recallTicks = 0;
                droneState  = DroneState.IDLE;
            }
        } else {
            moveSmoothlyTo(owner.position().add(0, 2.0, 0), 0.7);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    // ── Job validation ────────────────────────────────────────────────────────

    /**
     * Validate the active job is still present and the ghost marker still exists.
     * If invalid, transitions to an appropriate recovery state and returns {@code false}.
     */
    private boolean validateActiveJob() {
        if (currentJob == null) { resetToIdle(); return false; }

        if (!GhostJobManager.get(level()).jobExistsAt(currentJob.pos())) {
            GhostLogger.drone("Drone " + this.getId() + ": job vanished at " + currentJob.pos());
            releaseCurrentJob();
            droneState = isInventoryEmpty() ? DroneState.FINDING_JOB : DroneState.DUMPING_ITEMS;
            return false;
        }

        // For construction jobs, the ghost marker must still be there
        if (currentJob.type() == GhostJobManager.JobType.CONSTRUCTION
                && !(level().getBlockEntity(currentJob.pos()) instanceof GhostBlockEntity)) {
            GhostLogger.drone("Drone " + this.getId() + ": ghost missing at " + currentJob.pos() + " (undo?)");
            releaseCurrentJob();
            droneState = isInventoryEmpty() ? DroneState.IDLE : DroneState.DUMPING_ITEMS;
            return false;
        }

        return true;
    }

    /**
     * Called after a successful build or deconstruct.
     * Always transitions to IDLE with a short linger — never directly to FINDING_JOB.
     */
    private void finishJob() {
        currentJob  = null;
        lingerTicks = (int) (2 / getAttributeValue(ModAttributes.WORK_SPEED));
        droneState  = DroneState.IDLE;
        recallTicks = 0;
    }

    // ── Port validation ───────────────────────────────────────────────────────

    /**
     * Validate port-mode link. Attempts rehome within network if port is gone.
     * @return false if the drone discarded itself (caller must return immediately)
     */
    private boolean validateOrRehomePort() {
        Optional<BlockPos> p = getPortPos();
        if (p.isPresent() && level().hasChunkAt(p.get())
                && level().getBlockEntity(p.get()) instanceof IDronePort) {
            if (this.tickCount % 100 == 0)
                networkId = LogisticsNetworkManager.get(level()).getNetworkId(p.get());
            return true;
        }

        // Orphan — try to rehome
        if (networkId != null) {
            for (BlockPos candidate : LogisticsNetworkManager.get(level()).getNetworkMembers(networkId)) {
                if (level().isLoaded(candidate) && level().getBlockEntity(candidate) instanceof IDronePort) {
                    setPort(candidate);
                    GhostLib.LOGGER.info("Orphaned drone rehomed to {}", candidate);
                    return true;
                }
            }
        }

        // Nowhere to go
        GhostLib.LOGGER.warn("Drone {} orphaned with no port. Self-destructing.", this.getId());
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (!s.isEmpty()) Block.popResource(level(), blockPosition(), s);
        }
        Block.popResource(level(), blockPosition(), new ItemStack(ModItems.DRONE_SPAWN_EGG.get()));
        this.discard();
        return false;
    }

    // ── Energy ───────────────────────────────────────────────────────────────

    private void consumeEnergy() {
        if (energy > 0) {
            if (this.getDeltaMovement().lengthSqr() > 0.001) {
                double efficiency = getAttributeValue(ModAttributes.ENERGY_EFFICIENCY);
                energy -= Math.max(1, (int) (FLY_COST / efficiency));
            }
            lowPowerMode = false;
        } else {
            lowPowerMode = true;
            if (getMode() == DroneMode.PORT) droneState = DroneState.CHARGING;
        }
    }

    // ── Hover ────────────────────────────────────────────────────────────────

    private void hoverPassively() {
        if (getMode() == DroneMode.PLAYER) {
            Player owner = getOwnerUUID() != null ? level().getPlayerByUUID(getOwnerUUID()) : null;
            if (owner != null) {
                moveSmoothlyTo(owner.position().add(1.5, 2.0, 1.5), 0.2);
                getLookControl().setLookAt(owner);
            }
        } else if (getMode() == DroneMode.PORT && getPortPos().isPresent()) {
            moveSmoothlyTo(Vec3.atCenterOf(getPortPos().get()).add(0, 2.0, 0), 0.2);
        }
    }

    // ── Ghost state helper ────────────────────────────────────────────────────

    private void updateGhostState(BlockPos pos, GhostBlockEntity.GhostState state) {
        if (level().isLoaded(pos) && level().getBlockEntity(pos) instanceof GhostBlockEntity gbe) {
            // Guard: don't corrupt a MISSING_ITEMS ghost unless it's specifically being woken
            if (gbe.getCurrentState() == GhostBlockEntity.GhostState.MISSING_ITEMS
                    && state != GhostBlockEntity.GhostState.UNASSIGNED) return;
            gbe.setState(state);
        }
    }

    // ── Job release ──────────────────────────────────────────────────────────

    private void releaseCurrentJob() {
        if (currentJob == null) return;
        if (level().isLoaded(currentJob.pos())
                && level().getBlockEntity(currentJob.pos()) instanceof GhostBlockEntity gbe) {
            GhostBlockEntity.GhostState s = gbe.getCurrentState();
            if (s == GhostBlockEntity.GhostState.FETCHING
                    || s == GhostBlockEntity.GhostState.INCOMING
                    || s == GhostBlockEntity.GhostState.REMOVING
                    || s == GhostBlockEntity.GhostState.ASSIGNED) {
                gbe.setState(GhostBlockEntity.GhostState.UNASSIGNED);
            }
        }
        GhostJobManager.get(level()).releaseJob(currentJob.pos(), this.getUUID());
        currentJob   = null;
        noJobBackoff = 0;
        recallTicks  = 0;
    }

    private void resetToIdle() {
        releaseCurrentJob();
        droneState = DroneState.IDLE;
    }

    // ── Public wakeup ─────────────────────────────────────────────────────────

    public void wakeUp() {
        noJobBackoff = 0;
        recallTicks  = 0;
        if (droneState == DroneState.IDLE) droneState = DroneState.FINDING_JOB;
    }

    /** Called by DronePort to give the drone its first job before it enters the world. */
    public void setInitialJob(GhostJobManager.Job job) {
        currentJob = job;
        if (job.type() == GhostJobManager.JobType.CONSTRUCTION) {
            droneState = hasItemInInventory(new ItemStack(job.targetAfter().getBlock().asItem()))
                    ? DroneState.TRAVELING_BUILD : DroneState.TRAVELING_FETCH;
        } else {
            droneState = DroneState.TRAVELING_CLEAR;
        }
    }

    // ── Network port search ───────────────────────────────────────────────────

    private BlockPos findNearestNetworkPort() {
        if (networkId == null) return null;
        BlockPos nearest = null;
        double minDist = Double.MAX_VALUE;
        for (BlockPos p : LogisticsNetworkManager.get(level()).getNetworkMembers(networkId)) {
            if (level().getBlockEntity(p) instanceof IDronePort) {
                double d = p.distSqr(this.blockPosition());
                if (d < minDist) { minDist = d; nearest = p; }
            }
        }
        return nearest;
    }

    // ── Inventory ─────────────────────────────────────────────────────────────

    private boolean isInventoryEmpty() {
        for (int i = 0; i < inventory.getContainerSize(); i++)
            if (!inventory.getItem(i).isEmpty()) return false;
        return true;
    }

    private boolean isInventoryEmptyOfNonEggs() {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (!s.isEmpty() && !s.is(ModItems.DRONE_SPAWN_EGG.get())) return false;
        }
        return true;
    }

    private boolean hasSpace() {
        for (int i = 0; i < inventory.getContainerSize(); i++)
            if (inventory.getItem(i).isEmpty()) return true;
        return false;
    }

    public boolean hasItemInInventory(ItemStack stack) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (!s.isEmpty() && s.is(stack.getItem())) return true;
        }
        return false;
    }

    private int findBestSlot(net.minecraft.world.item.Item item) {
        CompoundTag requiredNbt = null;
        if (currentJob != null && level().getBlockEntity(currentJob.pos()) instanceof GhostBlockEntity gbe) {
            requiredNbt = gbe.getCapturedNbt();
        }
        int fallback = -1;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (!s.is(item)) continue;
            if (requiredNbt != null && s.has(DataComponents.BLOCK_ENTITY_DATA)) return i;
            if (fallback == -1) fallback = i;
        }
        return fallback;
    }

    /** Find a matching item slot in the player's inventory (loose match: item type only). */
    private int findPlayerItemSlot(Player player, ItemStack required) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (!s.isEmpty() && s.is(required.getItem())) return i;
        }
        return -1;
    }

    // ── Container interaction ─────────────────────────────────────────────────

    private BlockPos findNearbyContainerWithItem(ItemStack stack) {
        BlockPos center = this.blockPosition();

        if (networkId != null) {
            BlockPos bestProvider = null, bestGeneric = null;
            for (BlockPos p : LogisticsNetworkManager.get(level()).getNetworkMembers(networkId)) {
                if (!level().isLoaded(p)) continue;
                net.neoforged.neoforge.items.IItemHandler h =
                        level().getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, p, null);
                if (h == null) continue;
                boolean isProvider = isProviderChest(p);
                for (int i = 0; i < h.getSlots(); i++) {
                    if (h.getStackInSlot(i).is(stack.getItem())) {
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
            if (bestProvider != null) return bestProvider;
            if (bestGeneric  != null) return bestGeneric;
        }

        // Local fallback — restricted radius to avoid O(N³) scans
        int rh = 8, rv = 4;
        BlockPos bestLocal = null;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-rh, -rv, -rh), center.offset(rh, rv, rh))) {
            net.neoforged.neoforge.items.IItemHandler h =
                    level().getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, pos, null);
            if (h == null) continue;
            for (int i = 0; i < h.getSlots(); i++) {
                if (h.getStackInSlot(i).is(stack.getItem())) {
                    if (bestLocal == null || pos.distSqr(center) < bestLocal.distSqr(center))
                        bestLocal = pos.immutable();
                    break;
                }
            }
        }
        return bestLocal;
    }

    private boolean isProviderChest(BlockPos pos) {
        if (level().getBlockEntity(pos) instanceof com.example.ghostlib.block.entity.LogisticalChestBlockEntity lc) {
            var t = lc.getChestType();
            return t == com.example.ghostlib.block.LogisticalChestBlock.ChestType.PASSIVE_PROVIDER
                    || t == com.example.ghostlib.block.LogisticalChestBlock.ChestType.ACTIVE_PROVIDER
                    || t == com.example.ghostlib.block.LogisticalChestBlock.ChestType.BUFFER;
        }
        return false;
    }

    private boolean extractFromContainer(BlockPos pos, ItemStack required) {
        net.neoforged.neoforge.items.IItemHandler h =
                level().getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, pos, null);
        if (h == null) return false;
        for (int i = 0; i < h.getSlots(); i++) {
            if (h.getStackInSlot(i).is(required.getItem())) {
                ItemStack sim = h.extractItem(i, 1, true);
                if (!sim.isEmpty() && inventory.canAddItem(sim)) {
                    inventory.addItem(h.extractItem(i, 1, false));
                    return true;
                }
            }
        }
        return false;
    }

    // ── Dumping helpers ───────────────────────────────────────────────────────

    private BlockPos findDumpTarget() {
        BlockPos center = this.blockPosition();

        if (networkId != null) {
            BlockPos bestStorage = null, bestOther = null;
            for (BlockPos p : LogisticsNetworkManager.get(level()).getNetworkMembers(networkId)) {
                if (p.equals(getPortPos().orElse(null))) continue;
                if (!level().isLoaded(p)) continue;
                net.neoforged.neoforge.items.IItemHandler h =
                        level().getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, p, null);
                if (h == null) continue;

                boolean isStorage = isStorageChest(p);
                boolean hasRoom   = inventoryHasRoomIn(h);
                if (!hasRoom) continue;

                if (isStorage) {
                    if (bestStorage == null || p.distSqr(center) < bestStorage.distSqr(center)) bestStorage = p.immutable();
                } else {
                    if (bestOther == null || p.distSqr(center) < bestOther.distSqr(center)) bestOther = p.immutable();
                }
            }
            if (bestStorage != null) return bestStorage;
            if (bestOther   != null) return bestOther;
        }

        // Local fallback
        int radius = 16;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -4, -radius), center.offset(radius, 4, radius))) {
            if (pos.equals(getPortPos().orElse(null))) continue;
            net.neoforged.neoforge.items.IItemHandler h =
                    level().getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, pos, null);
            if (h != null && inventoryHasRoomIn(h)) return pos.immutable();
        }
        return null;
    }

    private boolean isStorageChest(BlockPos pos) {
        if (level().getBlockEntity(pos) instanceof com.example.ghostlib.block.entity.LogisticalChestBlockEntity lc) {
            var t = lc.getChestType();
            return t == com.example.ghostlib.block.LogisticalChestBlock.ChestType.STORAGE
                    || t == com.example.ghostlib.block.LogisticalChestBlock.ChestType.BUFFER;
        }
        return false;
    }

    private boolean inventoryHasRoomIn(net.neoforged.neoforge.items.IItemHandler handler) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (!s.isEmpty() && !s.is(ModItems.DRONE_SPAWN_EGG.get())) {
                if (net.neoforged.neoforge.items.ItemHandlerHelper.insertItemStacked(handler, s, true).getCount() < s.getCount())
                    return true;
            }
        }
        return false;
    }

    private void insertInto(BlockPos p) {
        net.neoforged.neoforge.items.IItemHandler h =
                level().getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, p, null);
        if (h == null) return;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (!s.isEmpty() && !s.is(ModItems.DRONE_SPAWN_EGG.get())) {
                inventory.setItem(i, net.neoforged.neoforge.items.ItemHandlerHelper.insertItemStacked(h, s, false));
            }
        }
    }

    private boolean tryDumpAtPort(BlockPos portPos) {
        if (level().getBlockEntity(portPos) instanceof IDronePort dp) {
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack s = inventory.getItem(i);
                if (!s.isEmpty()) inventory.setItem(i, dp.insertItem(s, false));
            }
            for (int i = 0; i < inventory.getContainerSize(); i++)
                if (!inventory.getItem(i).isEmpty()) return false;
            return true;
        }
        return false;
    }

    // ── Harvest ───────────────────────────────────────────────────────────────

    /**
     * Smart harvest: containers are emptied into drone inventory before the block
     * is broken, preventing item spill and preserving NBT for re-placement.
     */
    private void harvest(BlockPos pos, BlockState state) {
        if (!(level() instanceof ServerLevel sl)) return;

        net.minecraft.world.level.block.entity.BlockEntity be = level().getBlockEntity(pos);
        net.neoforged.neoforge.items.IItemHandler handler =
                sl.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, pos, null);

        if (handler != null) {
            CompoundTag data = be != null ? be.saveWithoutMetadata(sl.registryAccess()) : new CompoundTag();
            ItemStack savedBlock = new ItemStack(state.getBlock());

            // Empty handler before block is removed
            for (int i = 0; i < handler.getSlots(); i++) {
                ItemStack extracted = handler.extractItem(i, 64, false);
                if (!extracted.isEmpty()) {
                    ItemStack rem = inventory.addItem(extracted);
                    if (!rem.isEmpty()) Block.popResource(level(), pos, rem);
                }
            }

            // Logistical chests: strip inventory from NBT to prevent dupe
            boolean isLogistics = state.getBlock() instanceof com.example.ghostlib.block.LogisticalChestBlock;
            if (isLogistics) {
                data = data.copy();
                data.remove("Items");
                data.remove("Inventory");
                savedBlock.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(data));
            }

            ItemStack rem = inventory.addItem(savedBlock);
            if (!rem.isEmpty()) Block.popResource(level(), pos, rem);
            return;
        }

        // Standard harvest (loot table)
        ItemStack tool = new ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE);
        boolean silk = getAttributeValue(ModAttributes.SILK_TOUCH) >= 1.0;
        if (silk) {
            tool.enchant(sl.registryAccess()
                    .lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                    .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.SILK_TOUCH), 1);
        }

        net.minecraft.world.level.storage.loot.LootParams.Builder builder =
                new net.minecraft.world.level.storage.loot.LootParams.Builder(sl)
                        .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                        .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.TOOL, tool)
                        .withOptionalParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_ENTITY, be);

        List<ItemStack> drops = new java.util.ArrayList<>(state.getDrops(builder));
        if (drops.isEmpty() && silk && !state.isAir()) drops.add(new ItemStack(state.getBlock()));

        for (ItemStack drop : drops) {
            ItemStack rem = inventory.addItem(drop);
            if (!rem.isEmpty()) Block.popResource(level(), pos, rem);
        }
    }

    // ── Particle beam ─────────────────────────────────────────────────────────

    private void spawnBeam(Vec3 start, Vec3 end, float r, float g, float b) {
        if (!(level() instanceof ServerLevel sl)) return;
        Vec3 dir  = end.subtract(start);
        double dist = dir.length();
        dir = dir.normalize();
        for (double d = 0; d < dist; d += 0.2) {
            Vec3 p = start.add(dir.scale(d));
            sl.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD, p.x, p.y, p.z, 1, 0, 0, 0, 0.01);
        }
    }

    // ── Smooth movement ───────────────────────────────────────────────────────

    private void moveSmoothlyTo(Vec3 target, double speed) {
        double actualSpeed = lowPowerMode ? speed * 0.2 : speed;
        Vec3   dir  = target.subtract(this.position());
        double dist = dir.length();
        if (dist > 0.01) {
            double s = actualSpeed * (dist < 2.0 ? (dist / 2.0) : 1.0);
            this.setDeltaMovement(dir.scale(s / dist));
            if (droneState != DroneState.IDLE) getLookControl().setLookAt(target.x, target.y, target.z);
        } else {
            this.setDeltaMovement(Vec3.ZERO);
        }
    }

    public void setHomePos(BlockPos pos) { this.setPort(pos); }
}
