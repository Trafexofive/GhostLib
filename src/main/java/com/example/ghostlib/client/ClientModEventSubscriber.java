package com.example.ghostlib.client;

import com.example.ghostlib.GhostLib;
import com.example.ghostlib.client.model.DroneModel;
import com.example.ghostlib.client.renderer.entity.DroneRenderer;
import com.example.ghostlib.client.util.ClientGhostState;
import com.example.ghostlib.registry.ModBlockEntities;
import com.example.ghostlib.registry.ModEntities;
import com.example.ghostlib.registry.ModItems;
import com.example.ghostlib.util.GhostJobManager;
import com.example.ghostlib.block.GhostBlock;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;

import java.util.Map;

import com.example.ghostlib.client.renderer.entity.PortDroneRenderer;

/**
 * Handles client-side event subscriptions for GhostLib.
 * <p>Responsibilities:</p>
 * <ul>
 *   <li><b>Renderer Registration:</b> Registers BlockEntity and Entity renderers.</li>
 *   <li><b>Key Mapping:</b> Registers client-side keybinds.</li>
 *   <li><b>Input Handling:</b> Intercepts mouse scrolling for tool mode switching.</li>
 *   <li><b>Rendering:</b> Draws the holographic ghost previews, tiling grids, and deconstruction markers in the world.</li>
 * </ul>
 */
@EventBusSubscriber(modid = GhostLib.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientModEventSubscriber {

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.GHOST_BLOCK_ENTITY.get(), com.example.ghostlib.client.renderer.GhostBlockRenderer::new);
        event.registerEntityRenderer(ModEntities.DRONE.get(), DroneRenderer::new);
        event.registerEntityRenderer(ModEntities.PORT_DRONE.get(), PortDroneRenderer::new);
    }

    @SubscribeEvent
    public static void registerLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(DroneRenderer.DRONE_LAYER, DroneModel::createBodyLayer);
    }

    @SubscribeEvent
    public static void registerKeyMappings(net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent event) {
        Keybinds.registerKeys(event);
    }

    @EventBusSubscriber(modid = GhostLib.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
    public static class GameBusEvents {
        
        /**
         * Intercepts mouse scrolling to cycle tool modes (PLACE, DECONSTRUCT, CUT) when crouching.
         */
        @SubscribeEvent
        public static void onMouseScroll(net.neoforged.neoforge.client.event.InputEvent.MouseScrollingEvent event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.player.isCrouching()) {
                ItemStack stack = mc.player.getMainHandItem();
                if (stack.getItem() instanceof com.example.ghostlib.item.GhostPlacerItem item) {
                    event.setCanceled(true);
                    com.example.ghostlib.item.GhostPlacerItem.ToolMode current = item.getMode(stack);
                    int nextOrdinal = (current.ordinal() + (event.getScrollDeltaY() > 0 ? 1 : -1)) % com.example.ghostlib.item.GhostPlacerItem.ToolMode.values().length;
                    if (nextOrdinal < 0) nextOrdinal += com.example.ghostlib.item.GhostPlacerItem.ToolMode.values().length;
                    
                    mc.getConnection().send(new com.example.ghostlib.network.payload.ServerboundToolModePacket(nextOrdinal));
                    
                    // Client-side prediction for responsiveness
                    item.setMode(stack, nextOrdinal);
                    mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("Tool Mode: " + com.example.ghostlib.item.GhostPlacerItem.ToolMode.values()[nextOrdinal].name()), true);
                }
            }
        }

        /**
         * Renders the world overlays:
         * <ul>
         *   <li>Deconstruction boxes (Red wireframes) for active jobs.</li>
         *   <li>Ghost Pattern Previews (Blue holograms) for the Ghost Placer tool.</li>
         *   <li>Selection boxes for Cut/Deconstruct modes.</li>
         * </ul>
         */
        @SubscribeEvent
        public static void onRenderLevelStage(RenderLevelStageEvent event) {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
                renderDeconstructionMarkers(event);
            }

            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

            Minecraft mc = Minecraft.getInstance();
            Player player = mc.player;
            if (player == null) return;

            ItemStack stack = player.getMainHandItem();
            if (!stack.is(ModItems.GHOST_PLACER.get())) stack = player.getOffhandItem();
            if (!stack.is(ModItems.GHOST_PLACER.get())) return;
            
            com.example.ghostlib.item.GhostPlacerItem.ToolMode mode = ((com.example.ghostlib.item.GhostPlacerItem)stack.getItem()).getMode(stack);
            CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();

            HitResult hit = player.pick(64.0D, 0.0F, false); 
            if (hit == null || hit.getType() != HitResult.Type.BLOCK) return;

            PoseStack poseStack = event.getPoseStack();
            Vec3 cameraPos = event.getCamera().getPosition();
            MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

            poseStack.pushPose();
            poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
            BlockPos lookPos = ((BlockHitResult)hit).getBlockPos();

            if (mode == com.example.ghostlib.item.GhostPlacerItem.ToolMode.PLACE) {
                 if (tag.contains("Pattern")) {
                    if (ClientGhostState.isDragging) {
                        renderTiledPreview(mc, player, poseStack, bufferSource, tag, ClientGhostState.anchorPos, lookPos);
                    } else {
                        BlockPos origin = lookPos.relative(((BlockHitResult)hit).getDirection());
                        renderPatternPreview(mc, player, poseStack, bufferSource, tag, origin, 1, player.getDirection());
                    }
                 }
            } else {
                // Deconstruct / Cut Preview (Red Box)
                BlockPos start = ClientGhostState.isDragging ? ClientGhostState.anchorPos : lookPos;
                BlockPos end = lookPos;
                if (start != null) {
                    BlockPos min = new BlockPos(Math.min(start.getX(), end.getX()), Math.min(start.getY(), end.getY()), Math.min(start.getZ(), end.getZ()));
                    BlockPos max = new BlockPos(Math.max(start.getX(), end.getX()), Math.max(start.getY(), end.getY()), Math.max(start.getZ(), end.getZ()));
                    
                    VertexConsumer lines = bufferSource.getBuffer(RenderType.lines());
                    VertexConsumer tint = bufferSource.getBuffer(RenderType.translucent());
                    
                    // Render big box
                    float s = 0.005f;
                    net.minecraft.client.renderer.LevelRenderer.renderLineBox(poseStack, lines, 
                        min.getX() - s, min.getY() - s, min.getZ() - s, 
                        max.getX() + 1 + s, max.getY() + 1 + s, max.getZ() + 1 + s, 
                        1.0f, 0.0f, 0.0f, 1.0f);
                    
                    // Fill volume slightly
                    // (Optional: iterate blocks inside to check validity?)
                }
            }
            
            poseStack.popPose();
            bufferSource.endBatch();
        }

        private static void renderDeconstructionMarkers(RenderLevelStageEvent event) {
            Minecraft mc = Minecraft.getInstance();
            GhostJobManager manager = GhostJobManager.get(mc.level);
            Map<Long, Map<BlockPos, BlockState>> jobs = manager.getDirectDeconstructJobs();
            
            PoseStack poseStack = event.getPoseStack();
            Vec3 cameraPos = event.getCamera().getPosition();
            MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
            
            VertexConsumer lineConsumer = bufferSource.getBuffer(RenderType.lines());
            VertexConsumer tintConsumer = bufferSource.getBuffer(RenderType.translucent());

            poseStack.pushPose();
            poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

            for (Map<BlockPos, BlockState> map : jobs.values()) {
                for (BlockPos pos : map.keySet()) {
                    drawDeconstructionBox(poseStack, lineConsumer, tintConsumer, pos);
                }
            }

            int cx = net.minecraft.core.SectionPos.blockToSectionCoord(cameraPos.x);
            int cz = net.minecraft.core.SectionPos.blockToSectionCoord(cameraPos.z);
            for (int x = cx - 2; x <= cx + 2; x++) {
                for (int z = cz - 2; z <= cz + 2; z++) {
                    net.minecraft.world.level.chunk.LevelChunk chunk = mc.level.getChunk(x, z);
                    for (net.minecraft.world.level.block.entity.BlockEntity be : chunk.getBlockEntities().values()) {
                        if (be instanceof com.example.ghostlib.block.entity.GhostBlockEntity gbe) {
                            if (gbe.getCurrentState() == com.example.ghostlib.block.entity.GhostBlockEntity.GhostState.TO_REMOVE) {
                                drawDeconstructionBox(poseStack, lineConsumer, tintConsumer, be.getBlockPos());
                            }
                        }
                    }
                }
            }

            poseStack.popPose();
        }

        private static void drawDeconstructionBox(PoseStack poseStack, VertexConsumer lineConsumer, VertexConsumer tintConsumer, BlockPos pos) {
            float s = 0.005f;
            net.minecraft.client.renderer.LevelRenderer.renderLineBox(poseStack, lineConsumer, 
                pos.getX() - s, pos.getY() - s, pos.getZ() - s, 
                pos.getX() + 1 + s, pos.getY() + 1 + s, pos.getZ() + 1 + s, 
                1.0f, 0.0f, 0.0f, 1.0f);
            renderFilledBox(poseStack, tintConsumer, pos, 1.0f, 0.0f, 0.0f, 0.25f);
        }

        private static void renderFilledBox(PoseStack poseStack, VertexConsumer consumer, BlockPos pos, float r, float g, float b, float a) {
            Matrix4f matrix = poseStack.last().pose();
            float x1 = pos.getX() - 0.001f;
            float y1 = pos.getY() - 0.001f;
            float z1 = pos.getZ() - 0.001f;
            float x2 = pos.getX() + 1.001f;
            float y2 = pos.getY() + 1.001f;
            float z2 = pos.getZ() + 1.001f;

            addQuad(matrix, consumer, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, r, g, b, a); 
            addQuad(matrix, consumer, x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1, r, g, b, a); 
            addQuad(matrix, consumer, x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1, r, g, b, a); 
            addQuad(matrix, consumer, x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2, r, g, b, a); 
            addQuad(matrix, consumer, x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1, r, g, b, a); 
            addQuad(matrix, consumer, x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2, r, g, b, a); 
        }

        private static void addQuad(Matrix4f matrix, VertexConsumer consumer, float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3, float x4, float y4, float z4, float r, float g, float b, float a) {
            consumer.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a).setUv(0, 0).setLight(15728880).setNormal(0, 1, 0);
            consumer.addVertex(matrix, x2, y2, z2).setColor(r, g, b, a).setUv(0, 0).setLight(15728880).setNormal(0, 1, 0);
            consumer.addVertex(matrix, x3, y3, z3).setColor(r, g, b, a).setUv(0, 0).setLight(15728880).setNormal(0, 1, 0);
            consumer.addVertex(matrix, x4, y4, z4).setColor(r, g, b, a).setUv(0, 0).setLight(15728880).setNormal(0, 1, 0);
        }
        
        private static void renderTiledPreview(Minecraft mc, Player player, PoseStack poseStack, MultiBufferSource bufferSource, CompoundTag tag, BlockPos start, BlockPos end) {
            int sizeX = tag.getInt("SizeX");
            int sizeZ = tag.getInt("SizeZ");
            int dx = end.getX() - start.getX();
            int dz = end.getZ() - start.getZ();
            Direction dir;
            int count = 1;
            if (Math.abs(dx) > Math.abs(dz)) {
                dir = dx > 0 ? Direction.EAST : Direction.WEST;
                if (sizeX > 0) count = 1 + Math.abs(dx) / sizeX;
            } else {
                dir = dz > 0 ? Direction.SOUTH : Direction.NORTH;
                if (sizeZ > 0) count = 1 + Math.abs(dz) / sizeZ;
            }
            renderPatternPreview(mc, player, poseStack, bufferSource, tag, start, count, dir);
        }

        private static void renderPatternPreview(Minecraft mc, Player player, PoseStack poseStack, MultiBufferSource bufferSource, CompoundTag tag, BlockPos initialOrigin, int count, Direction dir) {
            ListTag patternList = tag.getList("Pattern", 10);
            BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();
            int bpSizeX = tag.getInt("SizeX");
            int bpSizeZ = tag.getInt("SizeZ");

            for (int r = 0; r < count; r++) {
                BlockPos currentOrigin = initialOrigin;
                 if (r > 0) {
                    if (dir.getAxis() == Direction.Axis.X) currentOrigin = initialOrigin.relative(dir, r * bpSizeX);
                    else if (dir.getAxis() == Direction.Axis.Z) currentOrigin = initialOrigin.relative(dir, r * bpSizeZ);
                }

                for (int i = 0; i < patternList.size(); i++) {
                    CompoundTag blockTag = patternList.getCompound(i);
                    BlockPos relative = NbtUtils.readBlockPos(blockTag, "Rel").orElse(BlockPos.ZERO);
                    BlockState state = NbtUtils.readBlockState(player.level().holderLookup(net.minecraft.core.registries.Registries.BLOCK), blockTag.getCompound("State"));
                    BlockPos target = currentOrigin.offset(relative);
                    
                    BlockState existingState = player.level().getBlockState(target);
                    float red, g, b;
                    if (existingState.isAir() || existingState.getBlock() instanceof GhostBlock) {
                        red = 0.5f; g = 0.7f; b = 1.0f;
                    } else {
                        red = 1.0f; g = 0.2f; b = 0.2f;
                    }

                    poseStack.pushPose();
                    poseStack.translate(target.getX(), target.getY(), target.getZ());
                    blockRenderer.getModelRenderer().renderModel(poseStack.last(), bufferSource.getBuffer(RenderType.translucent()), 
                        state, blockRenderer.getBlockModel(state), red, g, b, 15728880, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, ModelData.EMPTY, RenderType.translucent());
                    poseStack.popPose();
                }
            }
        }
    }
}