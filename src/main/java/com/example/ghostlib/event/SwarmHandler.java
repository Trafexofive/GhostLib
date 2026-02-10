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

                            // 1. Check if player even has a drone item before looking for jobs

                            int droneSlot = findDroneInInventory(player);

                            if (droneSlot == -1) break;

                

                            // 2. Scan for a job

                            GhostJobManager.Job job = manager.requestJob(player.blockPosition(), player.getUUID(), true);

                            if (job == null) break;

                

                            // 3. Capability Check: Construction jobs MUST have the item in inventory

                            boolean canFulfill = false;

                            if (job.type() == GhostJobManager.JobType.CONSTRUCTION) {

                                ItemStack required = new ItemStack(job.targetAfter().getBlock().asItem());

                                if (hasItemInInventory(player, required)) {

                                    canFulfill = true;

                                }

                            } else {

                                canFulfill = true; // Deconstruction always fulfillable

                            }

                

                            if (canFulfill) {

                                // 4. Deployment: Reassign job from player temporary ID to new drone ID

                                spawnDroneFromSlot(player, droneSlot, job);

                            } else {

                                // 5. Cleanup: Material missing, release the job back to the manager

                                manager.releaseJob(job.pos(), player.getUUID());

                                

                                // Set ghost state to Halted (Purple) so we don't spam deployment checks for it

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

        DroneEntity drone = new DroneEntity(ModEntities.DRONE.get(), player.level());
        
        // Load data from item before shrinking
        net.minecraft.world.item.component.CustomData customData = stack.get(net.minecraft.core.component.DataComponents.ENTITY_DATA);
        if (customData != null) {
            customData.loadInto(drone);
        }

        stack.shrink(1);
        drone.setPos(player.getX(), player.getY() + 1.5, player.getZ());
        drone.setOwner(player);
        
        // Reassign the job from player to drone
        GhostJobManager.get(player.level()).reassignJob(job.pos(), player.getUUID(), drone.getUUID());
        drone.setInitialJob(job);

        player.level().addFreshEntity(drone);
        return true;
    }
}
