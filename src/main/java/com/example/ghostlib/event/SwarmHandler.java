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

        // Calculate unassigned jobs
        // We use the raw map getters added in previous updates
        int totalJobs = manager.getConstructionJobsMap().values().stream()
                .mapToInt(map -> map.size())
                .sum();

        int assignedJobs = manager.getAssignments().size();
        int unassigned = totalJobs - assignedJobs;

        if (unassigned <= 0)
            return;

        // Cap spawn rate at 5 per second to avoid flooding but allow rapid deployment
        int toSpawn = Math.min(unassigned, 5);

        // Check player inventory for Drone Items and spawn
        for (int i = 0; i < toSpawn; i++) {
            if (!spawnDroneFromInventory(player))
                break;
        }
    }

    private static boolean spawnDroneFromInventory(Player player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.getItem() == ModItems.DRONE_SPAWN_EGG.get()) {
                stack.shrink(1);

                DroneEntity drone = new DroneEntity(ModEntities.DRONE.get(), player.level());
                drone.setPos(player.getX(), player.getY() + 1.5, player.getZ());
                drone.setOwner(player);

                player.level().addFreshEntity(drone);

                return true;
            }
        }
        return false;
    }
}
