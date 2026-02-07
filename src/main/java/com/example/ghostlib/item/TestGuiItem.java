package com.example.ghostlib.item;

import com.example.ghostlib.util.GhostGUI;
import com.lowdragmc.lowdraglib2.gui.factory.IContainerUIHolder;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ProgressBar;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Switch;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.MCSprites;
import com.lowdragmc.lowdraglib2.gui.ui.style.LayoutStyle;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Supplier;

public class TestGuiItem extends Item implements MenuProvider, IContainerUIHolder {

    public TestGuiItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            // Ensure stack has NBT
            ItemStack stack = player.getItemInHand(hand);
            if (!stack.has(DataComponents.CUSTOM_DATA)) {
                CompoundTag tag = new CompoundTag();
                tag.putInt("counter", 0);
                tag.putString("text", "Edit me");
                tag.putBoolean("toggle", false);
                stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            }
            
            serverPlayer.openMenu(this, buf -> {
                // We don't strictly need extra data for the Item case if we pull from hand on client
                // but we can send hand index if we want to be precise.
                buf.writeBoolean(hand == InteractionHand.MAIN_HAND); 
            });
        }
        return InteractionResultHolder.consume(player.getItemInHand(hand));
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Test GUI Item");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int windowId, Inventory playerInventory, Player player) {
        return new com.example.ghostlib.menu.TestMenu(windowId, playerInventory, this);
    }

    @Override
    public ModularUI createUI(Player player) {
        // 1. Load from XML
        net.minecraft.resources.ResourceLocation loc = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("ghostlib", "uitemplates/test_xml.xml");
        org.w3c.dom.Document doc = com.lowdragmc.lowdraglib2.utils.XmlUtils.loadXml(loc);
        UI ui = UI.of(doc);

        // Helper to access NBT safely
        Supplier<CompoundTag> getTag = () -> {
            ItemStack stack = player.getMainHandItem();
            if (stack.getItem() instanceof TestGuiItem) return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            stack = player.getOffhandItem();
            if (stack.getItem() instanceof TestGuiItem) return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            return new CompoundTag();
        };

        Consumer<Consumer<CompoundTag>> updateTag = (modifier) -> {
            ItemStack stack = player.getMainHandItem();
            if (!(stack.getItem() instanceof TestGuiItem)) stack = player.getOffhandItem();
            if (stack.getItem() instanceof TestGuiItem) {
                CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
                modifier.accept(tag);
                stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            }
        };

        // 2. Bind Behaviors by ID
        ui.select("test_button", Button.class).forEach(btn -> {
            btn.setOnClick((click) -> {
                updateTag.accept(t -> t.putInt("counter", t.getInt("counter") + 1));
            });
        });

        ui.select("counter_label", Label.class).forEach(label -> {
            label.bindDataSource(GhostGUI.supplier(() -> Component.literal("Count (XML): " + getTag.get().getInt("counter"))));
        });

        ui.select("input_field", TextField.class).forEach(tf -> {
            tf.setText(getTag.get().getString("text"));
            tf.setTextResponder((str) -> {
                updateTag.accept(t -> t.putString("text", str));
            });
        });

        ui.select("toggle_switch", Switch.class).forEach(sw -> {
            sw.setOn(getTag.get().getBoolean("toggle"));
            sw.setOnSwitchChanged((pressed) -> {
                updateTag.accept(t -> t.putBoolean("toggle", pressed));
            });
        });

        ui.select("sync_bar", ProgressBar.class).forEach(pb -> {
            pb.bindDataSource(GhostGUI.supplier(() -> (System.currentTimeMillis() % 2000) / 2000f));
        });

        return ModularUI.of(ui, player);
    }

    @Override
    public boolean isStillValid(Player player) {
        return player.getMainHandItem().getItem() == this || player.getOffhandItem().getItem() == this;
    }
}
