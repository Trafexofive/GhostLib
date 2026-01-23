package com.example.ghostlib.client.renderer;

import com.example.ghostlib.block.entity.GhostBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;

public class GhostBlockRenderer implements BlockEntityRenderer<GhostBlockEntity> {
    public GhostBlockRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(GhostBlockEntity tile, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        GhostBlockEntity.GhostState state = tile.getCurrentState();
        BlockState captured = tile.getCapturedState();
        
        // Render Red Overlay for both TO_REMOVE (Queued) and REMOVING (Active)
        if ((state == GhostBlockEntity.GhostState.TO_REMOVE || state == GhostBlockEntity.GhostState.REMOVING) && !captured.isAir()) {
            BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
            poseStack.pushPose();
            poseStack.translate(0.01, 0.01, 0.01);
            poseStack.scale(0.98f, 0.98f, 0.98f);
            
            dispatcher.getModelRenderer().renderModel(
                poseStack.last(),
                bufferSource.getBuffer(RenderType.translucent()),
                captured,
                dispatcher.getBlockModel(captured),
                1.0f, 0.3f, 0.3f, // Red tint
                15728880,
                OverlayTexture.NO_OVERLAY,
                ModelData.EMPTY,
                RenderType.translucent()
            );
            poseStack.popPose();
            return;
        }
        
        // If TO_REMOVE but captured is Air (e.g. deleting a Ghost Block), we fall through to render the Red Cube below.

        float r, g, b;
        float alpha = 0.6f;

        switch (state.id) {
            case 0: r = 0.0f; g = 0.0f; b = 0.5f; break; // UNASSIGNED - Deep Blue
            case 1: r = 0.4f; g = 0.8f; b = 1.0f; break; // ASSIGNED - Light Blue
            case 2: r = 0.0f; g = 0.3f; b = 1.0f; break; // FETCHING - Dark Blue
            case 3: r = 1.0f; g = 1.0f; b = 0.0f; alpha = 0.8f; break; // INCOMING - Yellow (Higher Alpha)
            case 4: r = 1.0f; g = 0.0f; b = 0.0f; break; // TO_REMOVE - Red
            case 5: r = 1.0f; g = 0.0f; b = 1.0f; break; // MISSING_ITEMS - Purple
            case 6: r = 1.0f; g = 0.0f; b = 0.0f; break; // REMOVING - Red
            default: r = 1.0f; g = 1.0f; b = 1.0f; break;
        }

        TextureAtlasSprite sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(ResourceLocation.withDefaultNamespace("block/white_concrete"));
        
        VertexConsumer vertexConsumer = bufferSource.getBuffer(RenderType.entityTranslucent(InventoryMenu.BLOCK_ATLAS));
        poseStack.pushPose();
        poseStack.translate(0.001f, 0.001f, 0.001f);
        poseStack.scale(0.998f, 0.998f, 0.998f);
        renderCube(poseStack, vertexConsumer, r, g, b, alpha, 15728880, sprite);
        poseStack.popPose();
    }

    private void renderCube(PoseStack poseStack, VertexConsumer consumer, float r, float g, float b, float a, int light, TextureAtlasSprite sprite) {
        Matrix4f matrix = poseStack.last().pose();
        float u0 = sprite.getU0();
        float u1 = sprite.getU1();
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();
        addQuad(matrix, consumer, 0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1, r, g, b, a, u0, u1, v0, v1, light, 0, -1, 0);
        addQuad(matrix, consumer, 0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0, r, g, b, a, u0, u1, v0, v1, light, 0, 1, 0);
        addQuad(matrix, consumer, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0, 0, r, g, b, a, u0, u1, v0, v1, light, 0, 0, -1);
        addQuad(matrix, consumer, 0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1, r, g, b, a, u0, u1, v0, v1, light, 0, 0, 1);
        addQuad(matrix, consumer, 0, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0, r, g, b, a, u0, u1, v0, v1, light, -1, 0, 0);
        addQuad(matrix, consumer, 1, 0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, r, g, b, a, u0, u1, v0, v1, light, 1, 0, 0);
    }
    
    private void addQuad(Matrix4f matrix, VertexConsumer consumer, float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3, float x4, float y4, float z4, float r, float g, float b, float a, float u0, float u1, float v0, float v1, int light, float nx, float ny, float nz) {
        consumer.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a).setUv(u0, v0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(nx, ny, nz);
        consumer.addVertex(matrix, x2, y2, z2).setColor(r, g, b, a).setUv(u0, v1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(nx, ny, nz);
        consumer.addVertex(matrix, x3, y3, z3).setColor(r, g, b, a).setUv(u1, v1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(nx, ny, nz);
        consumer.addVertex(matrix, x4, y4, z4).setColor(r, g, b, a).setUv(u1, v0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(nx, ny, nz);
    }
}
