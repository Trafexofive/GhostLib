package com.example.ghostlib.item;

import com.example.ghostlib.util.GhostGUI;
import com.lowdragmc.lowdraglib2.gui.factory.IContainerUIHolder;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Supplier;

public class AssemblerTestItem extends Item implements MenuProvider, IContainerUIHolder {

    public AssemblerTestItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            serverPlayer.openMenu(this, buf -> buf.writeBoolean(hand == InteractionHand.MAIN_HAND));
        }
        return InteractionResultHolder.consume(player.getItemInHand(hand));
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Assembler Test Tool");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int windowId, Inventory playerInventory, Player player) {
        return new com.example.ghostlib.menu.AssemblerTestMenu(windowId, playerInventory, this);
    }

    @Override
    public ModularUI createUI(Player player) {
        try {
            net.minecraft.resources.ResourceLocation loc = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("ghostlib", "gui/assembler_test.xml");
            System.out.println("AssemblerTestItem: Loading XML from " + loc);
            org.w3c.dom.Document doc = com.lowdragmc.lowdraglib2.utils.XmlUtils.loadXml(loc);
            if (doc == null) {
                System.out.println("AssemblerTestItem: Document is NULL");
                return ModularUI.of(UI.empty(), player);
            }
            UI ui = UI.of(doc);
            System.out.println("AssemblerTestItem: UI Loaded");

            // Access NBT Helpers
            Supplier<CompoundTag> getTag = () -> {
                ItemStack stack = player.getMainHandItem();
                if (!(stack.getItem() instanceof AssemblerTestItem)) stack = player.getOffhandItem();
                if (stack.getItem() instanceof AssemblerTestItem) return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
                return new CompoundTag();
            };

            Consumer<Consumer<CompoundTag>> updateTag = (modifier) -> {
                ItemStack stack = player.getMainHandItem();
                if (!(stack.getItem() instanceof AssemblerTestItem)) stack = player.getOffhandItem();
                if (stack.getItem() instanceof AssemblerTestItem) {
                    CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
                    modifier.accept(tag);
                    stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
                }
            };

            // 1. Bind Inventory (9 inputs + 1 output)
            // For a test item, we'll wrap its NBT in a handler. 
            // Note: This won't sync items back to NBT automatically without custom binding,
            // but for UI layout testing it is perfect.
            ItemStackHandler dummyHandler = new ItemStackHandler(10); 

            for (int i = 0; i < 9; i++) {
                final int index = i;
                ui.select("in_" + i, ItemSlot.class).forEach(slot -> slot.bind(dummyHandler, index));
            }
            ui.select("out_0", ItemSlot.class).forEach(slot -> slot.bind(dummyHandler, 9));

            // 2. Bind Buttons
            ui.select("lock_btn", Button.class).forEach(btn -> {
                btn.setOnClick(click -> {
                    updateTag.accept(t -> t.putBoolean("locked", !t.getBoolean("locked")));
                });
                btn.setText(getTag.get().getBoolean("locked") ? "Locked" : "Lock");
            });

            ui.select("toggle_btn", Button.class).forEach(btn -> {
                btn.setOnClick(click -> {
                    updateTag.accept(t -> t.putBoolean("active", !t.getBoolean("active")));
                });
                btn.setText(getTag.get().getBoolean("active") ? "ON" : "OFF");
            });

            return ModularUI.of(ui, player);
        } catch (Exception e) {
            e.printStackTrace();
            return ModularUI.of(UI.empty(), player);
        }
    }

    @Override
    public boolean isStillValid(Player player) {
        return player.getMainHandItem().getItem() == this || player.getOffhandItem().getItem() == this;
    }
}
