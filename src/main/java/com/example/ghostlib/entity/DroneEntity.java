package com.example.ghostlib.entity;

import com.example.ghostlib.block.entity.GhostBlockEntity;
import com.example.ghostlib.util.GhostJobManager;
import com.example.ghostlib.registry.ModItems;
import com.example.ghostlib.registry.ModBlocks;
import com.example.ghostlib.block.GhostBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Represents a construction drone entity that autonomously builds and deconstructs structures.
 * 
 * <p>Behavior:</p>
 * <ul>
 *   <li><b>AI:</b> Uses a Finite State Machine (FSM) to cycle between Idle, Fetching, Building, and Dumping.</li>
 *   <li><b>Logistics:</b> Interacts with the global GhostJobManager to find tasks.</li>
 *   <li><b>Power:</b> Consumes energy to fly and work. Recharges wirelessly from VoltLink grids.</li>
 *   <li><b>Harvesting:</b> Uses Silk Touch logic to ensure blocks are harvested intact.</li>
 * </ul>
 */
public class DroneEntity extends PathfinderMob {
    
    /** States for the drone's AI FSM. */
    public enum DroneState {
        IDLE, 
        FINDING_JOB, 
        TRAVELING_CLEAR, // Moving to remove a block
        TRAVELING_FETCH, // Moving to pick up items
        TRAVELING_BUILD, // Moving to place a block
        DUMPING_ITEMS    // Returning excess items to player/port
    }

    private DroneState droneState = DroneState.IDLE;
    private GhostJobManager.Job currentJob = null;
    private BlockPos homePos = null; // null means Player-owned
    private final SimpleContainer inventory = new SimpleContainer(9); 
    
    // Energy System
    private int energy = 10000;
    private static final int MAX_ENERGY = 10000;
    private static final int FLY_COST = 1;      // Per tick
    private static final int WORK_COST = 50;    // Per block built/broken
    private boolean lowPowerMode = false;

    private int waitTicks = 0;
    private int idleTicks = 0;
    private int lingerTicks = 0; 
    private static final int MAX_IDLE_TICKS = 600; 

    public DroneEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
        this.noPhysics = true;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, com.example.ghostlib.config.GhostLibConfig.DRONE_MAX_HEALTH)
                .add(Attributes.MOVEMENT_SPEED, com.example.ghostlib.config.GhostLibConfig.DRONE_MOVEMENT_SPEED);
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getAmbientSound() {
        return com.example.ghostlib.registry.ModSounds.DRONE_FLY.get();
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) return;

        // 1. Energy Consumption
        consumeEnergy();

        // 2. Proximity Recharging (The "Leach" Logic)
        if (level().getGameTime() % 10 == 0) {
            tryRechargeFromGrid();
        }

        if (waitTicks > 0) {
            waitTicks--;
            return;
        }

        // Integrity check: Ensure job wasn't stolen or deleted
        if (currentJob != null && droneState != DroneState.IDLE && droneState != DroneState.DUMPING_ITEMS) {
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
        }
    }

    private void consumeEnergy() {
        if (energy > 0) {
            if (this.getDeltaMovement().lengthSqr() > 0.001) {
                energy -= FLY_COST;
            }
            lowPowerMode = false;
        } else {
            lowPowerMode = true;
        }
    }

    private void tryRechargeFromGrid() {
        if (energy >= MAX_ENERGY) return;

        BlockPos pos = this.blockPosition();
        // Since we are in a composite project, we can access VoltLink classes directly if dependency is correct.
        // If not, we'd use capability lookup or block checks.
        
        com.example.voltlink.network.GridManager grid = com.example.voltlink.network.GridManager.get(level());
        List<BlockPos> nodes = grid.findNearbyNodes(pos, 5);
        
        if (!nodes.isEmpty()) {
            com.example.voltlink.network.GridManager.PowerIsland island = grid.getIsland(nodes.get(0));
            if (island != null && island.potential > 0.1f) {
                int amount = Math.min(500, MAX_ENERGY - energy);
                energy += amount;
                
                if (level() instanceof ServerLevel sl) {
                    sl.sendParticles(net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK, 
                        getX(), getY(), getZ(), 3, 0.1, 0.1, 0.1, 0.05);
                }
            }
        }
    }

    private void handleIdle() {
        if (!isInventoryEmpty()) { this.droneState = DroneState.DUMPING_ITEMS; return; }

        if (lingerTicks > 0) {
            lingerTicks--;
            this.setDeltaMovement(this.getDeltaMovement().scale(0.8));
        } else {
            Player player = this.level().getNearestPlayer(this, 32);
            if (player != null) {
                Vec3 target = player.position().add(1.5, 2.0, 1.5);
                moveSmoothlyTo(target, 0.2);
                this.getLookControl().setLookAt(player);
                idleTicks++;
                if (idleTicks > MAX_IDLE_TICKS) { returnToHome(); return; }
            } else if (homePos != null) {
                 idleTicks++;
                 if (idleTicks > MAX_IDLE_TICKS) { returnToHome(); return; }
            }
        }
        
        if (this.tickCount % 20 == 0) this.droneState = DroneState.FINDING_JOB;
    }

    private void handleFindingJob() {
        if (!hasSpace()) { this.droneState = DroneState.DUMPING_ITEMS; return; }

        GhostJobManager.Job job = GhostJobManager.get(level()).requestJob(this.blockPosition(), this.getUUID(), hasSpace());
        if (job != null) {
            this.currentJob = job;
            idleTicks = 0;
            lingerTicks = 0;

            if (job.type() == GhostJobManager.JobType.CONSTRUCTION) {
                ItemStack required = new ItemStack(job.targetAfter().getBlock().asItem());
                if (hasItemInInventory(required)) {
                    this.droneState = DroneState.TRAVELING_BUILD;
                    if (level().getBlockEntity(job.pos()) instanceof GhostBlockEntity gbe) {
                         gbe.setAssignedTo(this.getUUID());
                         gbe.setState(GhostBlockEntity.GhostState.INCOMING);
                    }
                    return;
                }
                if (findNearbyContainerWithItem(required) != null) {
                    this.droneState = DroneState.TRAVELING_FETCH;
                    if (level().getBlockEntity(job.pos()) instanceof GhostBlockEntity gbe) {
                        gbe.setAssignedTo(this.getUUID());
                        gbe.setState(GhostBlockEntity.GhostState.FETCHING);
                    }
                    return;
                }
                Player player = level().getNearestPlayer(this, 64);
                if (player != null && player.getInventory().findSlotMatchingItem(required) != -1) {
                    this.droneState = DroneState.TRAVELING_FETCH;
                    if (level().getBlockEntity(job.pos()) instanceof GhostBlockEntity gbe) {
                        gbe.setAssignedTo(this.getUUID());
                        gbe.setState(GhostBlockEntity.GhostState.FETCHING);
                    }
                } else {
                    if (level().getBlockEntity(job.pos()) instanceof GhostBlockEntity gbe) {
                        if (gbe.getCurrentState() != GhostBlockEntity.GhostState.MISSING_ITEMS) {
                            gbe.setState(GhostBlockEntity.GhostState.MISSING_ITEMS);
                        }
                    }
                    GhostJobManager.get(level()).releaseJob(job.pos(), this.getUUID());
                    this.currentJob = null;
                    this.waitTicks = 40;
                    this.droneState = DroneState.IDLE;
                }
            } else {
                this.droneState = DroneState.TRAVELING_CLEAR;
                if (level().getBlockEntity(job.pos()) instanceof GhostBlockEntity gbe) {
                    gbe.setAssignedTo(this.getUUID());
                }
            }
        } else {
            this.droneState = DroneState.IDLE;
        }
    }

    private void handleTravelingClear() {
        if (currentJob == null) { resetToIdle(); return; }
        moveSmoothlyTo(currentJob.pos().getCenter(), 0.6);
        if (this.position().distanceTo(currentJob.pos().getCenter()) < com.example.ghostlib.config.GhostLibConfig.DRONE_INTERACTION_RANGE / 8.0) {
            BlockPos pos = currentJob.pos();
            
            // Visual Beam
            if (com.example.ghostlib.config.GhostLibConfig.RENDER_DRONE_BEAMS) {
                spawnBeam(this.position().add(0, 0.2, 0), Vec3.atCenterOf(pos), 1.0f, 0.2f, 0.2f); // Red beam for break
            }

            BlockState existing = level().getBlockState(pos);
            BlockState targetAfter = currentJob.targetAfter();
            if (level().getBlockEntity(pos) instanceof GhostBlockEntity gbe) {
                BlockState captured = gbe.getCapturedState();
                if (!captured.isAir()) harvest(pos, captured);
                else if (!existing.isAir() && !(existing.getBlock() instanceof GhostBlock)) harvest(pos, existing);
            } else if (!existing.isAir() && !(existing.getBlock() instanceof GhostBlock)) {
                harvest(pos, existing);
            }
            level().setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            GhostJobManager.get(level()).removeJob(pos);
            if (targetAfter != null && !targetAfter.isAir()) {
                level().setBlock(pos, ModBlocks.GHOST_BLOCK.get().defaultBlockState(), 3);
                if (level().getBlockEntity(pos) instanceof GhostBlockEntity gbe) {
                    gbe.setTargetState(targetAfter);
                    gbe.setState(GhostBlockEntity.GhostState.UNASSIGNED);
                }
            }
            this.energy -= WORK_COST;
            this.lingerTicks = 5; 
            this.currentJob = null; 
            this.droneState = isInventoryEmpty() ? DroneState.FINDING_JOB : DroneState.DUMPING_ITEMS;
        }
    }

    /**
     * Harvests a block using "Silk Touch" logic (Pick Block).
     * Ensures NBT data and exact item representation are preserved.
     */
    private void harvest(BlockPos pos, BlockState state) {
        if (level() instanceof ServerLevel sl) {
            // Use Pick Block logic to get the exact item representation
            BlockHitResult hitResult = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
            ItemStack item = state.getCloneItemStack(hitResult, level(), pos, null);
            
            if (item.isEmpty()) {
                // Fallback to Silk Touch drops if pick block fails (e.g. some modded blocks)
                ItemStack tool = new ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE);
                tool.enchant(sl.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(net.minecraft.world.item.enchantment.Enchantments.SILK_TOUCH), 1);

                net.minecraft.world.level.storage.loot.LootParams.Builder builder = new net.minecraft.world.level.storage.loot.LootParams.Builder(sl)
                    .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                    .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.TOOL, tool)
                    .withOptionalParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_ENTITY, level().getBlockEntity(pos));
                
                List<ItemStack> drops = state.getDrops(builder);
                for (ItemStack drop : drops) {
                    ItemStack remainder = this.inventory.addItem(drop);
                    if (!remainder.isEmpty()) Block.popResource(level(), pos, remainder);
                }
            } else {
                // getCloneItemStack should handle NBT for things like chests.
                ItemStack remainder = this.inventory.addItem(item);
                if (!remainder.isEmpty()) Block.popResource(level(), pos, remainder);
            }
        }
    }

    private void handleTravelingFetch() {
        if (currentJob == null) { resetToIdle(); return; }
        ItemStack required = new ItemStack(currentJob.targetAfter().getBlock().asItem());
        if (hasItemInInventory(required)) { this.droneState = DroneState.TRAVELING_BUILD; return; }
        BlockPos containerPos = findNearbyContainerWithItem(required);
        if (containerPos != null) {
            moveSmoothlyTo(Vec3.atCenterOf(containerPos), 0.7);
            if (this.position().distanceTo(Vec3.atCenterOf(containerPos)) < 2.0) {
                if (extractFromContainer(containerPos, required)) {
                    this.droneState = DroneState.TRAVELING_BUILD;
                    if (level().getBlockEntity(currentJob.pos()) instanceof GhostBlockEntity gbe) gbe.setState(GhostBlockEntity.GhostState.INCOMING);
                }
            }
            return;
        }
        Player player = level().getNearestPlayer(this, 64);
        if (player == null) { resetToIdle(); return; }
        Vec3 fetchPos = player.position().add(0, player.getEyeHeight(), 0);
        moveSmoothlyTo(fetchPos, 0.7);
        if (this.position().distanceTo(fetchPos) < 2.0) {
            int slot = player.getInventory().findSlotMatchingItem(required);
            boolean acquired = false;
            if (slot != -1) {
                ItemStack stackInSlot = player.getInventory().getItem(slot);
                if (!stackInSlot.isEmpty() && stackInSlot.is(required.getItem())) {
                     ItemStack taken = stackInSlot.split(1);
                     this.inventory.addItem(taken);
                     acquired = true;
                }
            }
            if (acquired) {
                this.droneState = DroneState.TRAVELING_BUILD;
                if (level().getBlockEntity(currentJob.pos()) instanceof GhostBlockEntity gbe) {
                     if (gbe.getCurrentState() != GhostBlockEntity.GhostState.INCOMING) gbe.setState(GhostBlockEntity.GhostState.INCOMING);
                }
            } else {
                if (level().getBlockEntity(currentJob.pos()) instanceof GhostBlockEntity gbe) {
                    gbe.setState(GhostBlockEntity.GhostState.MISSING_ITEMS);
                }
                GhostJobManager.get(level()).releaseJob(currentJob.pos(), this.getUUID());
                currentJob = null;
                waitTicks = 100;
                this.droneState = DroneState.IDLE;
            }
        }
    }

    private BlockPos findNearbyContainerWithItem(ItemStack stack) {
        BlockPos center = this.blockPosition();
        int rh = com.example.ghostlib.config.GhostLibConfig.DRONE_SEARCH_RANGE_H / 2;
        int rv = com.example.ghostlib.config.GhostLibConfig.DRONE_SEARCH_RANGE_V / 2;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-rh, -rv, -rh), center.offset(rh, rv, rh))) {
            net.neoforged.neoforge.items.IItemHandler handler = level().getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, pos, null);
            if (handler != null) {
                for (int i = 0; i < handler.getSlots(); i++) {
                    if (handler.getStackInSlot(i).is(stack.getItem())) return pos.immutable();
                }
            }
        }
        return null;
    }

    private boolean extractFromContainer(BlockPos pos, ItemStack stack) {
        net.neoforged.neoforge.items.IItemHandler handler = level().getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler != null) {
            for (int i = 0; i < handler.getSlots(); i++) {
                if (handler.getStackInSlot(i).is(stack.getItem())) {
                    ItemStack taken = handler.extractItem(i, 1, false);
                    if (!taken.isEmpty()) {
                        this.inventory.addItem(taken);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void handleTravelingBuild() {
        if (currentJob == null) { resetToIdle(); return; }
        ItemStack required = new ItemStack(currentJob.targetAfter().getBlock().asItem());
        if (!hasItemInInventory(required)) { this.droneState = DroneState.TRAVELING_FETCH; return; }
        moveSmoothlyTo(currentJob.pos().getCenter(), 0.7);
        if (this.position().distanceTo(currentJob.pos().getCenter()) < 1.5) {
            BlockPos pos = currentJob.pos();
            
            // Visual Beam
            if (com.example.ghostlib.config.GhostLibConfig.RENDER_DRONE_BEAMS) {
                spawnBeam(this.position().add(0, 0.2, 0), Vec3.atCenterOf(pos), 0.2f, 0.2f, 1.0f); // Blue beam for build
            }

            BlockState worldState = level().getBlockState(pos);
            if (!worldState.isAir() && !worldState.canBeReplaced() && !(worldState.getBlock() instanceof GhostBlock)) {
                 GhostJobManager.get(level()).registerDirectDeconstruct(pos, currentJob.targetAfter(), level());
                 this.currentJob = null;
                 this.droneState = DroneState.FINDING_JOB;
                 return;
            }
            this.level().setBlock(pos, currentJob.targetAfter(), 3);
            this.playSound(com.example.ghostlib.registry.ModSounds.DRONE_WORK.get(), 1.0f, 1.0f);
            consumeFromInventory(required);
            GhostJobManager.get(level()).removeJob(pos);
            this.energy -= WORK_COST;
            this.lingerTicks = 5;
            this.currentJob = null;
            this.droneState = DroneState.IDLE;
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

    private void handleDumpingItems() {
        Player player = level().getNearestPlayer(this, 32);
        if (player == null) { this.droneState = DroneState.IDLE; return; }
        Vec3 dumpPos = player.position().add(0, player.getEyeHeight(), 0);
        moveSmoothlyTo(dumpPos, 0.6);
        if (this.position().distanceTo(dumpPos) < 2.5) {
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty()) {
                    if (player.getInventory().add(stack)) { inventory.setItem(i, ItemStack.EMPTY); }
                    else { Block.popResource(level(), player.blockPosition(), stack.copy()); inventory.setItem(i, ItemStack.EMPTY); }
                }
            }
            if (isInventoryEmpty()) { this.lingerTicks = 10; this.droneState = DroneState.IDLE; }
        }
    }

    public void setHomePos(BlockPos pos) { this.homePos = pos; }

    private void returnToHome() {
        if (homePos != null) {
            if (level().getBlockEntity(homePos) instanceof com.example.ghostlib.block.entity.DronePortControllerBlockEntity port) {
                ItemStack selfStack = new ItemStack(ModItems.DRONE_SPAWN_EGG.get());
                if (port.getDroneStorage().insertItem(0, selfStack, true).isEmpty()) {
                    moveSmoothlyTo(Vec3.atCenterOf(homePos).add(0, 2, 0), 0.8);
                    if (this.position().distanceTo(Vec3.atCenterOf(homePos).add(0, 2, 0)) < 1.0) {
                        port.getDroneStorage().insertItem(0, selfStack, false);
                        for(int i=0; i<inventory.getContainerSize(); i++) {
                             ItemStack s = inventory.getItem(i);
                             if (!s.isEmpty()) Block.popResource(level(), homePos, s);
                        }
                        this.discard();
                    }
                    return;
                }
            }
        }
        Player player = this.level().getNearestPlayer(this, 32);
        if (player != null) returnToPlayer(player);
    }

    private void returnToPlayer(Player player) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) Block.popResource(level(), blockPosition(), stack);
        }
        ItemStack egg = new ItemStack(ModItems.DRONE_SPAWN_EGG.get());
        if (!player.getInventory().add(egg)) Block.popResource(level(), blockPosition(), egg);
        this.discard();
    }

    private void moveSmoothlyTo(Vec3 target, double speed) {
        double actualSpeed = lowPowerMode ? speed * 0.2 : speed;
        Vec3 dir = target.subtract(this.position());
        double dist = dir.length();
        if (dist > 0.01) {
            double approachSpeed = actualSpeed;
            if (dist < 2.0) approachSpeed *= (dist / 2.0); 
            this.setDeltaMovement(dir.scale(approachSpeed / dist));
            if (droneState != DroneState.IDLE) this.getLookControl().setLookAt(target.x, target.y, target.z);
        } else { this.setDeltaMovement(Vec3.ZERO); }
    }

    private void resetToIdle() {
        if (currentJob != null) GhostJobManager.get(level()).releaseJob(currentJob.pos(), this.getUUID());
        this.currentJob = null;
        this.droneState = DroneState.IDLE;
    }

    private boolean isInventoryEmpty() {
        for (int i = 0; i < inventory.getContainerSize(); i++) if (!inventory.getItem(i).isEmpty()) return false;
        return true;
    }

    private boolean hasSpace() {
        for (int i = 0; i < inventory.getContainerSize(); i++) if (inventory.getItem(i).isEmpty()) return true;
        return false;
    }

    private boolean hasItemInInventory(ItemStack stack) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (!s.isEmpty() && s.is(stack.getItem())) return true;
        }
        return false;
    }

    private void consumeFromInventory(ItemStack stack) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (!s.isEmpty() && s.is(stack.getItem())) { s.shrink(1); return; }
        }
    }
}