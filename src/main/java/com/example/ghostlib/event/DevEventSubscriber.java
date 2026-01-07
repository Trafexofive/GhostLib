package com.example.ghostlib.event;

import com.example.ghostlib.GhostLib;
import com.example.ghostlib.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.Random;

@EventBusSubscriber(modid = GhostLib.MODID)
public class DevEventSubscriber {

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        Player player = event.getEntity();
        if (!player.level().isClientSide) {
            // Basic kit
            if (!player.getInventory().hasAnyOf(java.util.Set.of(ModItems.GHOST_PLACER.get()))) {
                player.getInventory().add(new ItemStack(ModItems.GHOST_PLACER.get()));
                player.getInventory().add(new ItemStack(ModItems.DRONE_SPAWN_EGG.get(), 5));
                player.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));
                player.getInventory().add(new ItemStack(Items.MOSSY_COBBLESTONE, 16));
                player.getInventory().add(new ItemStack(Items.OAK_LOG, 64));
                
                // Special Pre-loaded Blueprint Stick
                ItemStack structureStick = new ItemStack(ModItems.GHOST_PLACER.get());
                structureStick.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Starter House Blueprint"));
                
                CompoundTag tag = new CompoundTag();
                ListTag patternList = new ListTag();
                
                int sizeX = 5;
                int sizeY = 4;
                int sizeZ = 5;
                
                // Generate a simple 5x5x4 box with logs at corners and cobble walls
                for (int x = 0; x < sizeX; x++) {
                    for (int y = 0; y < sizeY; y++) {
                        for (int z = 0; z < sizeZ; z++) {
                            // Skip inside
                            if (x > 0 && x < sizeX - 1 && y > 0 && y < sizeY - 1 && z > 0 && z < sizeZ - 1) continue;
                            // Skip top except edges
                            if (y == sizeY - 1 && x > 0 && x < sizeX - 1 && z > 0 && z < sizeZ - 1) continue;

                            BlockState state;
                            boolean isCorner = (x == 0 || x == sizeX - 1) && (z == 0 || z == sizeZ - 1);
                            
                            if (isCorner) {
                                state = Blocks.OAK_LOG.defaultBlockState();
                            } else {
                                state = Blocks.COBBLESTONE.defaultBlockState();
                                // Leave specific hole for "door"
                                if (x == 2 && z == 0 && y < 2) continue; 
                            }
                            
                            CompoundTag blockTag = new CompoundTag();
                            blockTag.put("Rel", NbtUtils.writeBlockPos(new BlockPos(x, y, z)));
                            blockTag.put("State", NbtUtils.writeBlockState(state));
                            patternList.add(blockTag);
                        }
                    }
                }
                
                tag.put("Pattern", patternList);
                tag.putInt("SizeX", sizeX);
                tag.putInt("SizeY", sizeY);
                tag.putInt("SizeZ", sizeZ);
                
                structureStick.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
                player.getInventory().add(structureStick);
            }
        }
    }
}