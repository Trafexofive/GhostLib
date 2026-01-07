package com.example.ghostlib.client.renderer;

import com.example.ghostlib.client.util.ClientGhostState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.data.ModelData;

public class GhostPlacerItemRenderer extends BlockEntityWithoutLevelRenderer {
    public GhostPlacerItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext displayContext, PoseStack poseStack, MultiBufferSource buffer, int packedLight, int packedOverlay) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        // Visual feedback only in hands
        if (displayContext != ItemDisplayContext.FIRST_PERSON_RIGHT_HAND &&
            displayContext != ItemDisplayContext.FIRST_PERSON_LEFT_HAND &&
            displayContext != ItemDisplayContext.THIRD_PERSON_RIGHT_HAND &&
            displayContext != ItemDisplayContext.THIRD_PERSON_LEFT_HAND) {
            return;
        }

        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!tag.contains("Pattern")) return;

        // Robust raytrace for preview positioning
        HitResult hit = mc.player.pick(64.0D, 0.0F, false);
        BlockPos lookPos = null;
        Direction lookFace = Direction.UP;

        if (hit.getType() == HitResult.Type.BLOCK) {
            BlockHitResult blockHit = (BlockHitResult) hit;
            lookPos = blockHit.getBlockPos();
            lookFace = blockHit.getDirection();
        } else {
            // Fallback: place preview a few blocks in front of the player if looking at sky
            lookPos = mc.player.blockPosition().relative(mc.player.getDirection(), 5);
        }

        poseStack.pushPose();
        // Shift to world coordinates
        var camPos = mc.gameRenderer.getMainCamera().getPosition();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        if (ClientGhostState.isDragging && ClientGhostState.anchorPos != null && lookPos != null) {
            renderTiled(mc, poseStack, buffer, tag, ClientGhostState.anchorPos, lookPos);
        } else if (lookPos != null) {
            BlockPos origin = (hit.getType() == HitResult.Type.BLOCK) ? lookPos.relative(lookFace) : lookPos;
            renderBlueprint(mc, poseStack, buffer, tag, origin, 1, mc.player.getDirection());
        }

        poseStack.popPose();
    }

    private void renderTiled(Minecraft mc, PoseStack poseStack, MultiBufferSource buffer, CompoundTag tag, BlockPos start, BlockPos end) {
        if (start == null || end == null) return;

        int sizeX = Math.max(1, tag.getInt("SizeX"));
        int sizeZ = Math.max(1, tag.getInt("SizeZ"));
        if (sizeX <= 0 || sizeZ <= 0) return;

        // Calculate direction based on player's look vector instead of just X/Z axis
        Vec3 startVec = new Vec3(start.getX() + 0.5, start.getY() + 0.5, start.getZ() + 0.5);
        Vec3 endVec = new Vec3(end.getX() + 0.5, end.getY() + 0.5, end.getZ() + 0.5);
        Vec3 direction = endVec.subtract(startVec).normalize();

        // Determine the primary axis based on the largest component of the direction vector
        Direction dir;
        double absX = Math.abs(direction.x);
        double absZ = Math.abs(direction.z);

        if (absX > absZ) {
            dir = direction.x > 0 ? Direction.EAST : Direction.WEST;
        } else {
            dir = direction.z > 0 ? Direction.SOUTH : Direction.NORTH;
        }

        // Calculate count based on the distance and pattern size
        int count;
        if (dir.getAxis() == Direction.Axis.X) {
            count = 1 + Math.abs(end.getX() - start.getX()) / sizeX;
        } else {
            count = 1 + Math.abs(end.getZ() - start.getZ()) / sizeZ;
        }

        // Limit the count to prevent excessive rendering
        count = Math.min(count, 32); // reasonable limit to prevent performance issues

        renderBlueprint(mc, poseStack, buffer, tag, start, count, dir);
    }

    private void renderBlueprint(Minecraft mc, PoseStack poseStack, MultiBufferSource buffer, CompoundTag tag, BlockPos origin, int count, Direction dir) {
        ListTag pattern = tag.getList("Pattern", 10);
        BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();
        int sizeX = tag.getInt("SizeX");
        int sizeZ = tag.getInt("SizeZ");

        for (int r = 0; r < count; r++) {
            BlockPos currentOrigin = origin;
            if (r > 0) {
                currentOrigin = origin.relative(dir, r * (dir.getAxis() == Direction.Axis.X ? sizeX : sizeZ));
            }

            for (int i = 0; i < pattern.size(); i++) {
                CompoundTag bTag = pattern.getCompound(i);
                BlockPos rel = NbtUtils.readBlockPos(bTag, "Rel").orElse(BlockPos.ZERO);
                BlockState state = NbtUtils.readBlockState(mc.level.holderLookup(net.minecraft.core.registries.Registries.BLOCK), bTag.getCompound("State"));
                BlockPos target = currentOrigin.offset(rel);

                poseStack.pushPose();
                poseStack.translate(target.getX(), target.getY(), target.getZ());

                BakedModel model = blockRenderer.getBlockModel(state);

                // Check if the target position is obstructed (has a solid block)
                boolean isObstructed = !mc.level.getBlockState(target).isAir() &&
                                      !(mc.level.getBlockEntity(target) instanceof com.example.ghostlib.block.entity.GhostBlockEntity);

                // Use appropriate colors for preview based on obstruction
                float red, green, blue;
                if (isObstructed) {
                    // Red for obstructed positions
                    red = 1.0f; green = 0.2f; blue = 0.2f;
                } else {
                    // Light blue for valid ghost positions
                    red = 0.5f; green = 0.7f; blue = 1.0f;
                }

                blockRenderer.getModelRenderer().renderModel(
                    poseStack.last(),
                    buffer.getBuffer(RenderType.translucent()),
                    state,
                    model,
                    red, green, blue, // Consistent colors for preview
                    15728880, // packedLight
                    net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                    ModelData.EMPTY, // modelData
                    RenderType.translucent()
                );
                poseStack.popPose();
            }
        }
    }
}
