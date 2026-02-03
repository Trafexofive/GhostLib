package com.example.ghostlib.client.gui;

import com.example.ghostlib.menu.DronePortMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

public class DronePortScreen extends AbstractContainerScreen<DronePortMenu> {
    private static final ResourceLocation TEXTURE = ResourceLocation.withDefaultNamespace("textures/gui/container/shulker_box.png");

    public DronePortScreen(DronePortMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = (width - imageWidth) / 2;
        int y = (height - imageHeight) / 2;
        graphics.blit(TEXTURE, x, y, 0, 0, imageWidth, imageHeight);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        this.renderTooltip(graphics, mouseX, mouseY);
        
        // Display Energy
        String energyText = formatEnergy(this.menu.getEnergy()) + " FE";
        graphics.drawString(this.font, energyText, this.leftPos + 8, this.topPos + 6, 0x404040, false);
        
        // Display Formation Status
        String status = this.menu.isFormed() ? "✓ FORMED" : "✗ INCOMPLETE";
        int color = this.menu.isFormed() ? 0x00FF00 : 0xFF0000;
        graphics.drawString(this.font, status, this.leftPos + imageWidth - font.width(status) - 8, this.topPos + 6, color, false);
    }

    private String formatEnergy(int energy) {
        if (energy >= 1_000_000) return String.format("%.1fM", energy / 1_000_000.0);
        if (energy >= 1_000) return String.format("%.1fK", energy / 1_000.0);
        return String.valueOf(energy);
    }
}
