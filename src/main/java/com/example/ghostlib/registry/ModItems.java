package com.example.ghostlib.registry;

import com.example.ghostlib.GhostLib;
import com.example.ghostlib.item.GhostPlacerItem;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(GhostLib.MODID);

    public static final DeferredItem<Item> GHOST_PLACER = ITEMS.register("ghost_placer",
            () -> new GhostPlacerItem(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<Item> DRONE_SPAWN_EGG = ITEMS.register("drone_spawn_egg",
            () -> new DeferredSpawnEggItem(ModEntities.DRONE, 0x000000, 0x00FFFF, new Item.Properties()));

    public static final DeferredItem<Item> DRONE_PORT_SPAWNER = ITEMS.register("drone_port_spawner",
            () -> new com.example.ghostlib.item.DronePortSpawnerItem(new Item.Properties().stacksTo(1)) {
                @Override
                public void appendHoverText(ItemStack stack, TooltipContext context, java.util.List<net.minecraft.network.chat.Component> tooltip, net.minecraft.world.item.TooltipFlag flag) {
                    tooltip.add(net.minecraft.network.chat.Component.literal("Instantly deploys a 3x3 Drone Port structure").withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE));
                    tooltip.add(net.minecraft.network.chat.Component.literal("Pre-charged with 1,000,000 FE").withStyle(net.minecraft.ChatFormatting.YELLOW));
                }
            });

    public static final DeferredItem<Item> DRONE_PORT_CONTROLLER_ITEM = ITEMS.register("drone_port_controller",
            () -> new com.example.ghostlib.item.DronePortItem(ModBlocks.DRONE_PORT_CONTROLLER.get(), new Item.Properties()));

    public static final DeferredItem<Item> DRONE_PORT_MEMBER_ITEM = ITEMS.register("drone_port_member",
            () -> new net.minecraft.world.item.BlockItem(ModBlocks.DRONE_PORT_MEMBER.get(), new Item.Properties()));

    public static final DeferredItem<Item> MATERIAL_STORAGE = ITEMS.register("material_storage",
            () -> new BlockItem(ModBlocks.MATERIAL_STORAGE.get(), new Item.Properties()));

    public static final DeferredItem<Item> ELECTRIC_FURNACE_CONTROLLER = ITEMS.register("electric_furnace_controller",
            () -> new BlockItem(ModBlocks.ELECTRIC_FURNACE_CONTROLLER.get(), new Item.Properties()));

    public static final DeferredItem<Item> BLUEPRINT_FURNACE = ITEMS.register("blueprint_furnace",
            () -> new com.example.ghostlib.item.BlueprintItem(com.example.ghostlib.item.BlueprintItem.BlueprintType.ELECTRIC_FURNACE, new Item.Properties().stacksTo(1)));

    public static final DeferredItem<Item> DECONSTRUCTION_STICK = ITEMS.register("deconstruction_stick",
            () -> new Item(new Item.Properties().stacksTo(1)) {
                @Override
                public InteractionResult useOn(net.minecraft.world.item.context.UseOnContext context) {
                    if (!context.getLevel().isClientSide) {
                        com.example.ghostlib.util.GhostJobManager.get(context.getLevel())
                            .registerDirectDeconstruct(context.getClickedPos(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), context.getLevel());
                        context.getPlayer().displayClientMessage(net.minecraft.network.chat.Component.literal("Marked for Deconstruction").withStyle(net.minecraft.ChatFormatting.RED), true);
                    }
                    return InteractionResult.SUCCESS;
                }
            });

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}