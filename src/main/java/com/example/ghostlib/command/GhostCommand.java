package com.example.ghostlib.command;

import com.example.ghostlib.registry.ModItems;
import com.example.ghostlib.util.BlueprintManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

public class GhostCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ghost")
            .then(Commands.literal("save")
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(context -> saveBlueprint(context.getSource(), StringArgumentType.getString(context, "name")))
                )
            )
            .then(Commands.literal("load")
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(context -> loadBlueprint(context.getSource(), StringArgumentType.getString(context, "name")))
                )
            )
            .then(Commands.literal("debug_gui")
                .executes(context -> openDebugGui(context.getSource()))
            )
            .then(Commands.literal("assembler")
                .executes(context -> openAssemblerGui(context.getSource()))
            )
        );
    }

    private static int openAssemblerGui(CommandSourceStack source) {
        try {
            if (source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
                System.out.println("GhostCommand: Attempting to open Assembler GUI for " + player.getName().getString());
                // Use the registered singleton item
                player.openMenu((net.minecraft.world.MenuProvider)com.example.ghostlib.registry.ModItems.ASSEMBLER_TEST_ITEM.get(), buf -> {
                    buf.writeBoolean(true); // dummy main hand
                });
                return 1;
            }
        } catch (Exception e) {
            System.err.println("GhostCommand: Error opening Assembler GUI");
            e.printStackTrace();
        }
        return 0;
    }

    private static int openDebugGui(CommandSourceStack source) {
        if (source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            // Use the registered singleton item
            player.openMenu((net.minecraft.world.MenuProvider)com.example.ghostlib.registry.ModItems.TEST_ITEM.get(), buf -> {
                buf.writeBoolean(true); // isMainHand dummy
            });
            return 1;
        }
        return 0;
    }

    private static int saveBlueprint(CommandSourceStack source, String name) {
        if (source.getEntity() instanceof Player player) {
            ItemStack stack = player.getMainHandItem();
            if (stack.getItem() instanceof com.example.ghostlib.item.BlueprintItem) {
                CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
                if (tag.contains("Pattern")) {
                    BlueprintManager.saveBlueprint(name, tag);
                    source.sendSuccess(() -> Component.literal("Saved blueprint '" + name + "' to disk."), true);
                    return 1;
                } else {
                    source.sendFailure(Component.literal("Handheld item has no pattern to save."));
                }
            } else {
                source.sendFailure(Component.literal("Must hold a Blueprint Item with a pattern."));
            }
        }
        return 0;
    }

    private static int loadBlueprint(CommandSourceStack source, String name) {
         if (source.getEntity() instanceof Player player) {
            ItemStack stack = player.getMainHandItem();
            if (stack.getItem() instanceof com.example.ghostlib.item.BlueprintItem) {
                CompoundTag loaded = BlueprintManager.loadBlueprint(name);
                if (loaded != null) {
                    stack.set(DataComponents.CUSTOM_DATA, CustomData.of(loaded));
                    source.sendSuccess(() -> Component.literal("Loaded blueprint '" + name + "' into hand."), true);
                    return 1;
                } else {
                     source.sendFailure(Component.literal("Blueprint '" + name + "' not found."));
                }
            } else {
                 source.sendFailure(Component.literal("Must hold a Blueprint Item."));
            }
         }
         return 0;
    }
}
