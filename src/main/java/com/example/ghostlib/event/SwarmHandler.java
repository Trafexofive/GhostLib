package com.example.ghostlib.event;

import com.example.ghostlib.GhostLib;
import com.example.ghostlib.entity.DroneEntity;
import com.example.ghostlib.registry.ModEntities;
import com.example.ghostlib.registry.ModItems;
import com.example.ghostlib.util.GhostJobManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

@EventBusSubscriber(modid = GhostLib.MODID)
public class SwarmHandler {

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide || !(player.level() instanceof ServerLevel serverLevel))
            return;

        // Run logic every 20 ticks (1 second) to prevent lag
        if (player.tickCount % 20 != 0)
            return;

        GhostJobManager manager = GhostJobManager.get(serverLevel);

        // Cap spawn rate at 5 per second
        for (int i = 0; i < 5; i++) {
            // Find a job for a potential drone at the player's location
            GhostJobManager.Job job = manager.requestJob(player.blockPosition(), player.getUUID(), true);
            if (job == null) break;

            // CRITICAL: Drones must NEVER deploy for halted jobs.
            // requestJob only looks in constructionJobs, but let's double check the BE state.
            if (player.level().getBlockEntity(job.pos()) instanceof com.example.ghostlib.block.entity.GhostBlockEntity gbe) {
                if (gbe.getCurrentState() == com.example.ghostlib.block.entity.GhostBlockEntity.GhostState.MISSING_ITEMS) {
                    manager.releaseJob(job.pos(), player.getUUID());
                    continue; 
                }
            }

            // Check capability BEFORE spawning
            boolean canFulfill = false;
            if (job.type() == GhostJobManager.JobType.CONSTRUCTION) {
                ItemStack required = new ItemStack(job.targetAfter().getBlock().asItem());
                // Player drones fetch from player inventory
                if (hasItemInInventory(player, required)) {
                    canFulfill = true;
                }
            } else {
                canFulfill = true; // Deconstruction always fulfillable by fresh drone
            }

            if (canFulfill) {
                int droneSlot = findDroneInInventory(player);
                if (droneSlot != -1) {
                    spawnDroneFromSlot(player, droneSlot, job);
                } else {
                    manager.releaseJob(job.pos(), player.getUUID());
                    break;
                }
            } else {
                // If we can't fulfill, the job remains unassigned or moves to Halted
                // Drones should stay home.
                manager.releaseJob(job.pos(), player.getUUID());
                
                // OPTIMIZATION: If this job is unfulfillable, tell the ghost to halt 
                // so we don't keep picking it up every second.
                if (player.level().getBlockEntity(job.pos()) instanceof com.example.ghostlib.block.entity.GhostBlockEntity gbe) {
                    gbe.setState(com.example.ghostlib.block.entity.GhostBlockEntity.GhostState.MISSING_ITEMS);
                }
                continue;
            }
        }
    }

    private static int findDroneInInventory(Player player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.getItem() == ModItems.DRONE_SPAWN_EGG.get()) {
                return i;
            }
        }
        return -1;
    }

    private static boolean hasItemInInventory(Player player, ItemStack required) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (!s.isEmpty() && s.is(required.getItem())) return true;
        }
        return false;
    }

    private static boolean spawnDroneFromSlot(Player player, int slot, GhostJobManager.Job job) {
        ItemStack stack = player.getInventory().getItem(slot);
        stack.shrink(1);

        DroneEntity drone = new DroneEntity(ModEntities.DRONE.get(), player.level());
        drone.setPos(player.getX(), player.getY() + 1.5, player.getZ());
        drone.setOwner(player);
        
        // Reassign the job from player to drone
        GhostJobManager.get(player.level()).reassignJob(job.pos(), player.getUUID(), drone.getUUID());
        drone.setInitialJob(job);

        player.level().addFreshEntity(drone);
        return true;
    }
}
