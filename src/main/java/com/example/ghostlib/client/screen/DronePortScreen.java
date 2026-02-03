package com.example.ghostlib.client.screen;

import com.example.ghostlib.GhostLib;
import com.example.ghostlib.menu.DronePortMenu;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

public class DronePortScreen extends AbstractContainerScreen<DronePortMenu> {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(GhostLib.MODID, "textures/gui/drone_port.png");

    public DronePortScreen(DronePortMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.setShaderTexture(0, TEXTURE);
        int x = (width - imageWidth) / 2;
        int y = (height - imageHeight) / 2;
        guiGraphics.blit(TEXTURE, x, y, 0, 0, imageWidth, imageHeight);

        // Energy Bar
        int energy = menu.getEnergy();
        int maxEnergy = 1000000;
        int scaled = maxEnergy > 0 ? energy * 50 / maxEnergy : 0;
        // Draw energy bar at specific coord (placeholder: right side)
        guiGraphics.fill(x + 150, y + 20 + (50 - scaled), x + 160, y + 70, 0xFFFF0000);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        renderTooltip(guiGraphics, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, 4210752, false);
        guiGraphics.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, 4210752, false);
        
        guiGraphics.drawString(this.font, "Energy: " + menu.getEnergy() + " FE", 10, 70, 4210752, false);
        guiGraphics.drawString(this.font, "Formed: " + (menu.isFormed() ? "Yes" : "No"), 10, 80, 4210752, false);
    }
}
