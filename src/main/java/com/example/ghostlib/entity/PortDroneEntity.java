package com.example.ghostlib.entity;

import com.example.ghostlib.block.entity.DronePortControllerBlockEntity;
import com.example.ghostlib.block.entity.GhostBlockEntity;
import com.example.ghostlib.registry.ModItems;
import com.example.ghostlib.util.GhostJobManager;
import com.example.ghostlib.block.GhostBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class PortDroneEntity extends PathfinderMob {
    
    public enum DroneState {
        IDLE, FINDING_JOB, TRAVELING_CLEAR, TRAVELING_FETCH, TRAVELING_BUILD, DUMPING_ITEMS   
    }

    private DroneState droneState = DroneState.IDLE;
    private GhostJobManager.Job currentJob = null;
    private BlockPos homePos = null;
    private final SimpleContainer inventory = new SimpleContainer(9); 
    private int waitTicks = 0;
    private int idleTicks = 0;
    private int lingerTicks = 0; 
    private static final int MAX_IDLE_TICKS = 600; 

    public PortDroneEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
        this.noPhysics = true;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 1.0D);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) return;

        if (waitTicks > 0) {
            waitTicks--;
            return;
        }

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

    private void handleIdle() {
        if (!isInventoryEmpty()) { this.droneState = DroneState.DUMPING_ITEMS; return; }

        if (lingerTicks > 0) {
            lingerTicks--;
            this.setDeltaMovement(this.getDeltaMovement().scale(0.8));
        } else {
            if (homePos != null) {
                 idleTicks++;
                 if (idleTicks > MAX_IDLE_TICKS) { returnToHome(); return; }
            }
        }
        
        if (this.tickCount % 20 == 0) this.droneState = DroneState.FINDING_JOB;
    }

    private void handleFindingJob() {
        if (!hasSpace()) { this.droneState = DroneState.DUMPING_ITEMS; return; }

        // Prioritize jobs closer to home port if possible, or just standard search
        BlockPos searchCenter = homePos != null ? homePos : this.blockPosition();
        GhostJobManager.Job job = GhostJobManager.get(level()).requestJob(searchCenter, this.getUUID(), hasSpace());
        
        if (job != null) {
            this.currentJob = job;
            idleTicks = 0;
            lingerTicks = 0;

            if (job.type() == GhostJobManager.JobType.CONSTRUCTION) {
                ItemStack required = new ItemStack(job.targetAfter().getBlock().asItem());
                
                // 1. Check Internal Inventory
                if (hasItemInInventory(required)) {
                    this.droneState = DroneState.TRAVELING_BUILD;
                    if (level().getBlockEntity(job.pos()) instanceof GhostBlockEntity gbe) {
                         gbe.setAssignedTo(this.getUUID());
                         gbe.setState(GhostBlockEntity.GhostState.INCOMING);
                    }
                    return;
                }

                // 2. Check nearby containers (prioritizing Material Storage via capability)
                if (findNearbyContainerWithItem(required) != null) {
                    this.droneState = DroneState.TRAVELING_FETCH;
                    if (level().getBlockEntity(job.pos()) instanceof GhostBlockEntity gbe) {
                        gbe.setAssignedTo(this.getUUID());
                        gbe.setState(GhostBlockEntity.GhostState.FETCHING);
                    }
                    return;
                }

                // 3. Fallback or Fail (Port Drones rely on containers)
                if (level().getBlockEntity(job.pos()) instanceof GhostBlockEntity gbe) {
                    if (gbe.getCurrentState() != GhostBlockEntity.GhostState.MISSING_ITEMS) {
                        gbe.setState(GhostBlockEntity.GhostState.MISSING_ITEMS);
                    }
                }
                GhostJobManager.get(level()).releaseJob(job.pos(), this.getUUID());
                currentJob = null;
                waitTicks = 100; // Wait longer if failed
                this.droneState = DroneState.IDLE;
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

    // ... (Include handleTravelingClear, harvest, etc. similar to DroneEntity but optimized)
    // For brevity, I'll reuse the core logic but adapted.

    private void handleTravelingClear() {
        if (currentJob == null) { resetToIdle(); return; }
        moveSmoothlyTo(currentJob.pos().getCenter(), 0.6);
        if (this.position().distanceTo(currentJob.pos().getCenter()) < 1.5) {
            BlockPos pos = currentJob.pos();
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
                level().setBlock(pos, com.example.ghostlib.registry.ModBlocks.GHOST_BLOCK.get().defaultBlockState(), 3);
                if (level().getBlockEntity(pos) instanceof GhostBlockEntity gbe) {
                    gbe.setTargetState(targetAfter);
                    gbe.setState(GhostBlockEntity.GhostState.UNASSIGNED);
                }
            }
            
            this.lingerTicks = 5; 
            this.currentJob = null; 
            this.droneState = isInventoryEmpty() ? DroneState.FINDING_JOB : DroneState.DUMPING_ITEMS;
        }
    }

    private void harvest(BlockPos pos, BlockState state) {
        ItemStack drop = new ItemStack(state.getBlock().asItem());
        if (!drop.isEmpty()) {
            ItemStack remainder = this.inventory.addItem(drop);
            if (!remainder.isEmpty()) Block.popResource(level(), pos, remainder);
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
        
        // No player fallback for Port Drones? Or maybe fallback to Port if it has items?
        // For now, simple fail if no container.
        if (level().getBlockEntity(currentJob.pos()) instanceof GhostBlockEntity gbe) {
            gbe.setState(GhostBlockEntity.GhostState.MISSING_ITEMS);
        }
        GhostJobManager.get(level()).releaseJob(currentJob.pos(), this.getUUID());
        currentJob = null;
        waitTicks = 100;
        this.droneState = DroneState.IDLE;
    }

    private void handleTravelingBuild() {
        if (currentJob == null) { resetToIdle(); return; }
        ItemStack required = new ItemStack(currentJob.targetAfter().getBlock().asItem());
        if (!hasItemInInventory(required)) { this.droneState = DroneState.TRAVELING_FETCH; return; }

        moveSmoothlyTo(currentJob.pos().getCenter(), 0.7);
        if (this.position().distanceTo(currentJob.pos().getCenter()) < 1.5) {
            BlockPos pos = currentJob.pos();
            BlockState worldState = level().getBlockState(pos);

            if (!worldState.isAir() && !worldState.canBeReplaced() && !(worldState.getBlock() instanceof GhostBlock)) {
                 GhostJobManager.get(level()).registerDirectDeconstruct(pos, currentJob.targetAfter(), level());
                 this.currentJob = null;
                 this.droneState = DroneState.FINDING_JOB;
                 return;
            }

            this.level().setBlock(pos, currentJob.targetAfter(), 3);
            consumeFromInventory(required);
            GhostJobManager.get(level()).removeJob(pos);
            
            this.lingerTicks = 5;
            this.currentJob = null;
            this.droneState = DroneState.IDLE;
        }
    }

    private void handleDumpingItems() {
        // Dump to Port or Containers?
        // Logic: Dump to Port inventory if possible, else standard dump?
        // Port doesn't have item storage yet. Dump to nearby containers?
        // Or dump to "yellow chests".
        // For simplicity: Scan for container with space.
        
        // Just dump at home pos for now, mimicking previous behavior but at port.
        if (homePos == null) { this.droneState = DroneState.IDLE; return; }
        
        Vec3 dumpPos = Vec3.atCenterOf(homePos).add(0, 1, 0);
        moveSmoothlyTo(dumpPos, 0.6);

        if (this.position().distanceTo(dumpPos) < 2.5) {
            // Try to dump into port (if it had storage) or nearby chests?
            // User mentioned "Yellow storage chest from factorio".
            // For now, dump to any container near home.
            BlockPos dumpTarget = findDumpContainer();
            if (dumpTarget != null) {
                 // Move to specific container
                 moveSmoothlyTo(Vec3.atCenterOf(dumpTarget), 0.6);
                 if (this.position().distanceTo(Vec3.atCenterOf(dumpTarget)) < 2.0) {
                     dumpToContainer(dumpTarget);
                 }
            } else {
                // Drop items if no container?
                // Or just hold them.
                // Drop active items to prevent clogging?
                // Default to old behavior: Drop at home.
                for (int i = 0; i < inventory.getContainerSize(); i++) {
                    ItemStack stack = inventory.getItem(i);
                    if (!stack.isEmpty()) {
                        Block.popResource(level(), homePos, stack.copy()); 
                        inventory.setItem(i, ItemStack.EMPTY); 
                    }
                }
            }
            
            if (isInventoryEmpty()) { this.lingerTicks = 10; this.droneState = DroneState.IDLE; }
        }
    }

    public void setHomePos(BlockPos pos) { this.homePos = pos; }

    private void returnToHome() {
        if (homePos != null && isValidPort(homePos)) {
            if (tryDockAt(homePos)) return;
        }

        // Home is full, missing, or invalid. Search for a new home.
        BlockPos newHome = findNearestFreePort();
        if (newHome != null) {
            this.homePos = newHome;
            tryDockAt(newHome);
        }
        // If no home found, stay hovering (Stranded)
    }

    private boolean isValidPort(BlockPos pos) {
        return level().getBlockEntity(pos) instanceof DronePortControllerBlockEntity;
    }

    private boolean tryDockAt(BlockPos pos) {
        if (level().getBlockEntity(pos) instanceof DronePortControllerBlockEntity port) {
            ItemStack selfStack = new ItemStack(ModItems.DRONE_SPAWN_EGG.get());
            
            // Check if port accepts us
            if (port.getDroneStorage().insertItem(0, selfStack, true).isEmpty()) {
                // Fly to port
                moveSmoothlyTo(Vec3.atCenterOf(pos).add(0, 2, 0), 0.8);
                
                // Docking Distance
                if (this.position().distanceTo(Vec3.atCenterOf(pos).add(0, 2, 0)) < 1.0) {
                    port.getDroneStorage().insertItem(0, selfStack, false);
                    
                    // Dump Inventory into Port's space (conceptually) or just drop items
                    // Ideally, we'd insert into port storage if it existed, for now drop.
                    for(int i=0; i<inventory.getContainerSize(); i++) {
                         ItemStack s = inventory.getItem(i);
                         if (!s.isEmpty()) Block.popResource(level(), pos, s);
                    }
                    this.discard();
                }
                return true; // En route or docked
            }
        }
        return false;
    }

    private BlockPos findNearestFreePort() {
        BlockPos center = this.blockPosition();
        BlockPos bestPos = null;
        double bestDist = Double.MAX_VALUE;
        
        // Scan radius (32 blocks)
        int range = 32;
        
        // Optimization: In a real system we'd ask a Manager. For now, simple scan.
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-range, -16, -range), center.offset(range, 16, range))) {
            if (pos.equals(homePos)) continue; // Skip failed home
            
            if (level().getBlockEntity(pos) instanceof DronePortControllerBlockEntity port) {
                // Check if it allows drones
                ItemStack selfStack = new ItemStack(ModItems.DRONE_SPAWN_EGG.get());
                if (port.getDroneStorage().insertItem(0, selfStack, true).isEmpty()) {
                    double dist = center.distSqr(pos);
                    if (dist < bestDist) {
                        bestDist = dist;
                        bestPos = pos.immutable();
                    }
                }
            }
        }
        return bestPos;
    }

    private void moveSmoothlyTo(Vec3 target, double speed) {
        Vec3 dir = target.subtract(this.position());
        double dist = dir.length();
        if (dist > 0.01) {
            double approachSpeed = speed;
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

    private BlockPos findNearbyContainerWithItem(ItemStack stack) {
        BlockPos center = this.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-16, -8, -16), center.offset(16, 8, 16))) {
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

    private BlockPos findDumpContainer() {
        BlockPos center = this.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-16, -8, -16), center.offset(16, 8, 16))) {
            net.neoforged.neoforge.items.IItemHandler handler = level().getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, pos, null);
            if (handler != null) {
                // Check if it has space
                for (int i = 0; i < handler.getSlots(); i++) {
                    if (handler.insertItem(i, inventory.getItem(0).copy(), true).isEmpty()) return pos.immutable();
                }
            }
        }
        return null;
    }

    private void dumpToContainer(BlockPos pos) {
        net.neoforged.neoforge.items.IItemHandler handler = level().getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler != null) {
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty()) {
                    for (int j = 0; j < handler.getSlots(); j++) {
                        ItemStack remaining = handler.insertItem(j, stack, false);
                        inventory.setItem(i, remaining);
                        if (remaining.isEmpty()) break;
                    }
                }
            }
        }
    }
}
