package com.example.ghostlib.client.renderer;

import com.example.ghostlib.block.entity.GhostBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import org.joml.Matrix4f;

public class GhostBlockRenderer implements BlockEntityRenderer<GhostBlockEntity> {
    public GhostBlockRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(GhostBlockEntity tile, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        GhostBlockEntity.GhostState state = tile.getCurrentState();
        
        float r, g, b;
        float alpha = 1.0f;

        switch (state.id) {
            case 0: r = 0.0f; g = 0.0f; b = 1.0f; break; // UNASSIGNED - Blue
            case 1: r = 0.0f; g = 1.0f; b = 1.0f; break; // ASSIGNED - Cyan
            case 2: r = 0.0f; g = 0.0f; b = 0.5f; break; // FETCHING - Dark Blue
            case 3: r = 1.0f; g = 1.0f; b = 0.0f; break; // INCOMING - Yellow
            case 4: r = 1.0f; g = 0.0f; b = 0.0f; break; // TO_REMOVE - Red
            case 5: r = 0.5f; g = 0.0f; b = 0.5f; break; // MISSING_ITEMS - Purple
            case 6: r = 1.0f; g = 0.0f; b = 0.0f; break; // REMOVING - Red
            default: r = 1.0f; g = 1.0f; b = 1.0f; break;
        }

        VertexConsumer lineConsumer = bufferSource.getBuffer(RenderType.lines());
        poseStack.pushPose();
        
        float s = 0.005f;
        net.minecraft.client.renderer.LevelRenderer.renderLineBox(poseStack, lineConsumer, 
            -s, -s, -s, 1 + s, 1 + s, 1 + s, r, g, b, alpha);
            
        poseStack.popPose();
    }
}