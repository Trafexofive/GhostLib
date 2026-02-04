package com.example.ghostlib.client;

import com.example.ghostlib.GhostLib;
import com.example.ghostlib.client.model.DroneModel;
import com.example.ghostlib.client.renderer.entity.DroneRenderer;
import com.example.ghostlib.client.util.ClientClipboard;
import com.example.ghostlib.client.util.ClientGlobalSelection;
import com.example.ghostlib.registry.ModBlockEntities;
import com.example.ghostlib.registry.ModEntities;
import com.example.ghostlib.util.GhostJobManager;
import com.example.ghostlib.block.GhostBlock;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;

import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.ArrayList;

@EventBusSubscriber(modid = GhostLib.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientModEventSubscriber {

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.GHOST_BLOCK_ENTITY.get(),
                com.example.ghostlib.client.renderer.GhostBlockRenderer::new);
        event.registerEntityRenderer(ModEntities.DRONE.get(), DroneRenderer::new);
    }

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        // BlockUI menus are handled automatically by LDLib2
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

        @SubscribeEvent
        public static void onRightClickItem(
                net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickItem event) {
            if (!event.getLevel().isClientSide)
                return;

            ItemStack stack = event.getItemStack();
            if (stack.getItem() instanceof com.example.ghostlib.item.BlueprintItem) {
                if (stack.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)) {
                    CompoundTag tag = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag();
                    if (tag.contains("Pattern")) {
                        ClientClipboard.setClipboard(tag);
                        ClientGlobalSelection.setMode(ClientGlobalSelection.SelectionMode.PASTE);
                        Minecraft.getInstance().player
                                .displayClientMessage(Component.literal("Blueprint Loaded. Mode: Paste"), true);
                        event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
                        event.setCanceled(true);
                        return;
                    }
                }
                
                // Fallback: Empty blueprint toggles COPY mode
                ClientGlobalSelection.setMode(ClientGlobalSelection.SelectionMode.COPY);
                Minecraft.getInstance().player
                        .displayClientMessage(Component.literal("Empty Blueprint. Mode: Copy (Select Area)"), true);
                event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
            ClientGlobalSelection.checkExpiry();

            if (ClientGlobalSelection.currentMode == ClientGlobalSelection.SelectionMode.NONE)
                return;

            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null)
                return;

            HitResult hit = pickBlock(mc.player, 64.0D);
            BlockPos mousePos = null;
            if (hit.getType() == HitResult.Type.BLOCK) {
                mousePos = ((BlockHitResult) hit).getBlockPos();
                if (ClientGlobalSelection.currentMode == ClientGlobalSelection.SelectionMode.PASTE) {
                    if (ClientGlobalSelection.anchorPos == null) {
                        mousePos = mousePos.above();
                    }
                }
            }

            if (ClientGlobalSelection.isLocked) {
                // LOCK DRAG LOGIC:
                // 1. If NOT selecting, endPos stays at lockedPos (renders single ghost)
                // 2. If SELECTING (dragging), endPos follows mouse (renders tiled ghosts from locked anchor)
                if (ClientGlobalSelection.isSelecting) {
                    if (mousePos != null) {
                        ClientGlobalSelection.currentEndPos = mousePos;
                    }
                } else {
                    if (ClientGlobalSelection.lockedPos == null && mousePos != null) {
                        ClientGlobalSelection.lockedPos = mousePos;
                    }
                    ClientGlobalSelection.currentEndPos = ClientGlobalSelection.lockedPos;
                }
            } else {
                ClientGlobalSelection.updateSelection(mousePos);
            }

            // Handle Drag Release -> Confirm
            if (ClientGlobalSelection.isSelecting) {
                if (!mc.mouseHandler.isLeftPressed()) {
                    // Mouse Released
                    if (ClientGlobalSelection.anchorPos != null && ClientGlobalSelection.currentEndPos != null) {
                        boolean isDrag = !ClientGlobalSelection.anchorPos.equals(ClientGlobalSelection.currentEndPos);

                        if (ClientGlobalSelection.startedSelectionThisClick) {
                            if (isDrag) {
                                confirmSelection(mc, ClientGlobalSelection.currentEndPos);
                            }
                        } else {
                            confirmSelection(mc, ClientGlobalSelection.currentEndPos);
                        }
                    }
                    ClientGlobalSelection.isSelecting = false;
                    ClientGlobalSelection.startedSelectionThisClick = false;
                }
            }
        }

        private static HitResult pickBlock(Player player, double range) {
            Vec3 eyePos = player.getEyePosition(1.0F);
            Vec3 viewVec = player.getViewVector(1.0F);
            Vec3 targetVec = eyePos.add(viewVec.x * range, viewVec.y * range, viewVec.z * range);
            return player.level().clip(new net.minecraft.world.level.ClipContext(
                    eyePos, targetVec,
                    net.minecraft.world.level.ClipContext.Block.OUTLINE,
                    net.minecraft.world.level.ClipContext.Fluid.NONE,
                    player));
        }

        @SubscribeEvent
        public static void onClientLogout(
                net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
            ClientGlobalSelection.resetSelection();
            ClientGlobalSelection.setMode(ClientGlobalSelection.SelectionMode.NONE);
        }

        @SubscribeEvent
        public static void onMouseInput(InputEvent.InteractionKeyMappingTriggered event) {
            if (!event.isAttack())
                return; // Only listen for Left Click (Attack key)
            if (ClientGlobalSelection.currentMode == ClientGlobalSelection.SelectionMode.NONE)
                return;

            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null)
                return;

            event.setCanceled(true);
            event.setSwingHand(false);

            BlockPos hitPos;

            // FIX: If locked, the START of the drag is the locked position.
            if (ClientGlobalSelection.isLocked && ClientGlobalSelection.lockedPos != null) {
                hitPos = ClientGlobalSelection.lockedPos;
            } else {
                HitResult hit = pickBlock(mc.player, 64.0D);
                if (hit == null || hit.getType() != HitResult.Type.BLOCK)
                    return;
                hitPos = ((BlockHitResult) hit).getBlockPos();

                if (ClientGlobalSelection.currentMode == ClientGlobalSelection.SelectionMode.PASTE) {
                    hitPos = hitPos.above();
                }
            }

            // Start Dragging
            if (!ClientGlobalSelection.isSelecting) {
                ClientGlobalSelection.isSelecting = true;

                if (ClientGlobalSelection.anchorPos == null) {
                    // First Click (Start Selection)
                    ClientGlobalSelection.startSelection(hitPos);
                    ClientGlobalSelection.startedSelectionThisClick = true;
                    mc.player.displayClientMessage(Component.literal("Pos1 Set: " + hitPos.toShortString()), true);
                } else {
                    // Subsequent Click (Phase 2 / Adjustment)
                    ClientGlobalSelection.startedSelectionThisClick = false;
                }
            }
        }

        private static void confirmSelection(Minecraft mc, BlockPos endPos) {
            BlockPos start = ClientGlobalSelection.anchorPos;
            BlockPos min = new BlockPos(Math.min(start.getX(), endPos.getX()), Math.min(start.getY(), endPos.getY()),
                    Math.min(start.getZ(), endPos.getZ()));
            BlockPos max = new BlockPos(Math.max(start.getX(), endPos.getX()), Math.max(start.getY(), endPos.getY()),
                    Math.max(start.getZ(), endPos.getZ()));

            // Safety Limit
            if (max.getX() - min.getX() > 64 || max.getY() - min.getY() > 64 || max.getZ() - min.getZ() > 64) {
                mc.player.displayClientMessage(Component.literal("Selection too large! Max 64x64x64."), true);
                ClientGlobalSelection.resetSelection();
                return;
            }

            switch (ClientGlobalSelection.currentMode) {
                case COPY:
                case CUT:
                    // 1. Capture Logic
                    CompoundTag clipboardTag = new CompoundTag();
                    ListTag patternList = new ListTag();
                    for (BlockPos p : BlockPos.betweenClosed(min, max)) {
                        BlockState s = mc.level.getBlockState(p);
                        if (!s.isAir()) {
                            // "Single Entity" Logic: Skip non-base parts of known multiblocks
                            // 1. FactoryCore Poles: Only record BOTTOM
                            if (s.hasProperty(com.example.factorycore.block.ElectricalPoleBlock.PART)) {
                                if (s.getValue(com.example.factorycore.block.ElectricalPoleBlock.PART) != com.example.factorycore.block.ElectricalPoleBlock.PolePart.BOTTOM) continue;
                            }
                            // 2. Vanilla Double Blocks (Tall Grass, Flowers): Only record LOWER
                            if (s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF)) {
                                if (s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF) != net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) continue;
                            }

                            // Extract real state if it's a ghost block
                            if (s.getBlock() instanceof GhostBlock) {
                                net.minecraft.world.level.block.entity.BlockEntity be = mc.level.getBlockEntity(p);
                                if (be instanceof com.example.ghostlib.block.entity.GhostBlockEntity gbe) {
                                    s = gbe.getTargetState();
                                    // If target is air (e.g. uninitialized ghost), skip it or keep as air
                                    if (s.isAir()) continue;
                                }
                            }

                            CompoundTag bTag = new CompoundTag();
                            bTag.put("Rel", net.minecraft.nbt.NbtUtils.writeBlockPos(p.subtract(min)));
                            bTag.put("State", net.minecraft.nbt.NbtUtils.writeBlockState(s));
                            
                            // Capture NBT
                            net.minecraft.world.level.block.entity.BlockEntity be = mc.level.getBlockEntity(p);
                            if (be != null) {
                                bTag.put("Data", be.saveWithFullMetadata(mc.level.registryAccess()));
                            }
                            
                            patternList.add(bTag);
                        }
                    }
                    clipboardTag.put("Pattern", patternList);
                    clipboardTag.putInt("SizeX", (max.getX() - min.getX()) + 1);
                    clipboardTag.putInt("SizeY", (max.getY() - min.getY()) + 1);
                    clipboardTag.putInt("SizeZ", (max.getZ() - min.getZ()) + 1);

                    ClientClipboard.setClipboard(clipboardTag);
                    
                    // Sync to Hand Item if holding Blueprint
                    if (mc.player.getMainHandItem().getItem() instanceof com.example.ghostlib.item.BlueprintItem || 
                        mc.player.getOffhandItem().getItem() instanceof com.example.ghostlib.item.BlueprintItem) {
                        mc.getConnection().send(new com.example.ghostlib.network.payload.ServerboundUpdateBlueprintPacket(clipboardTag));
                    }

                    mc.player.displayClientMessage(Component
                            .literal(ClientGlobalSelection.currentMode == ClientGlobalSelection.SelectionMode.COPY
                                    ? "Copied to Clipboard"
                                    : "Cut to Clipboard"),
                            true);

                    if (ClientGlobalSelection.currentMode == ClientGlobalSelection.SelectionMode.CUT) {
                        // 2. Clear Area (Server)
                        mc.getConnection().send(new com.example.ghostlib.network.payload.ServerboundPlaceGhostsPacket(
                                min, max, 2, 0, 0, Optional.empty())); // Mode 2 = Full Force/Clear
                    }

                    // Transition to PASTE
                    ClientGlobalSelection.setMode(ClientGlobalSelection.SelectionMode.PASTE);
                    mc.player.displayClientMessage(Component.literal("Mode: Paste"), true);
                    break;

                case DECONSTRUCT:
                    mc.getConnection().send(new com.example.ghostlib.network.payload.ServerboundPlaceGhostsPacket(min,
                            max, 2, 0, 0, Optional.empty()));
                    if (com.example.ghostlib.config.GhostLibConfig.EXIT_MODE_AFTER_PLACE) {
                        ClientGlobalSelection.setMode(ClientGlobalSelection.SelectionMode.NONE);
                    } else {
                        ClientGlobalSelection.resetSelection();
                    }
                    mc.player.displayClientMessage(Component.literal("Deconstructing..."), true);
                    break;

                case PASTE:
                    if (ClientClipboard.hasClipboard()) {
                        // Send packet with clipboard data
                        // Bit 0: Grid (Ctrl), Bit 2: Force (Shift/Crouch OR Toggle)
                        boolean isCtrlDown = com.mojang.blaze3d.platform.InputConstants
                                .isKeyDown(mc.getWindow().getWindow(), org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL);
                        boolean isForceActive = mc.player.isCrouching() || ClientGlobalSelection.forceModeToggle;

                        int mode = 0;
                        if (isCtrlDown)
                            mode |= 1;
                        if (isForceActive)
                            mode |= 4;

                        BlockPos finalStart = start.offset(ClientGlobalSelection.patternOffset);
                        BlockPos finalEnd = endPos.offset(ClientGlobalSelection.patternOffset);

                        mc.getConnection().send(new com.example.ghostlib.network.payload.ServerboundPlaceGhostsPacket(
                                finalStart, finalEnd, mode, 
                                ClientGlobalSelection.tilingSpacingX, ClientGlobalSelection.tilingSpacingZ,
                                Optional.of(ClientClipboard.getClipboard())));
                        if (com.example.ghostlib.config.GhostLibConfig.EXIT_MODE_AFTER_PLACE) {
                            ClientGlobalSelection.setMode(ClientGlobalSelection.SelectionMode.NONE);
                        } else {
                            ClientGlobalSelection.resetSelection();
                        }
                    }
                    break;
            }
        }

        @SubscribeEvent
        public static void onRenderLevelStage(RenderLevelStageEvent event) {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
                renderDeconstructionMarkers(event);
                renderDebugInfo(event);
            }

            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS)
                return;
            if (ClientGlobalSelection.currentMode == ClientGlobalSelection.SelectionMode.NONE)
                return;

            Minecraft mc = Minecraft.getInstance();
            Player player = mc.player;
            if (player == null)
                return;

            PoseStack poseStack = event.getPoseStack();
            Vec3 cameraPos = event.getCamera().getPosition();
            MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

            poseStack.pushPose();
            poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

            // Calculate mousePos for rendering
            HitResult hit = pickBlock(player, 64.0D);
            BlockPos mousePos = null;
            if (hit.getType() == HitResult.Type.BLOCK) {
                mousePos = ((BlockHitResult) hit).getBlockPos();
                if (ClientGlobalSelection.currentMode == ClientGlobalSelection.SelectionMode.PASTE) {
                    if (ClientGlobalSelection.anchorPos == null) {
                        mousePos = mousePos.above();
                    }
                }
            }

            // Recalculate lookPos for rendering to match interaction range
            BlockPos lookPos = ClientGlobalSelection.currentEndPos;

            if (ClientGlobalSelection.isLocked) {
                if (ClientGlobalSelection.isSelecting) {
                    // While dragging in lock mode, preview should follow mouse
                    lookPos = mousePos != null ? mousePos : ClientGlobalSelection.lockedPos;
                } else {
                    lookPos = ClientGlobalSelection.lockedPos;
                }
            } else if (!ClientGlobalSelection.isSelecting) {
                lookPos = mousePos;
            }

            if (lookPos == null) {
                poseStack.popPose();
                bufferSource.endBatch();
                return;
            }

            if (ClientGlobalSelection.currentMode == ClientGlobalSelection.SelectionMode.PASTE) {
                if (ClientClipboard.hasClipboard()) {
                    BlockPos start = (ClientGlobalSelection.anchorPos != null ? ClientGlobalSelection.anchorPos
                            : lookPos).offset(ClientGlobalSelection.patternOffset);
                    BlockPos end = lookPos.offset(ClientGlobalSelection.patternOffset);

                    if (ClientGlobalSelection.anchorPos != null) {
                        renderTiledPreview(mc, player, poseStack, bufferSource, ClientClipboard.getClipboard(), start,
                                end);
                    } else {
                        // 1. Primary Preview
                        renderPatternPreview(mc, player, poseStack, bufferSource, ClientClipboard.getClipboard(),
                                start, 1, Direction.NORTH, 1.0f, 1.0f, 1.0f);
                        
                        // 2. Smart Tiling Previews (One in each 4 directions)
                        // ONLY if crouching
                        if (player.isCrouching()) {
                            int sizeX = Math.max(1, ClientClipboard.getClipboard().getInt("SizeX"));
                            int sizeZ = Math.max(1, ClientClipboard.getClipboard().getInt("SizeZ"));
                            int stepX = sizeX + ClientGlobalSelection.tilingSpacingX;
                            int stepZ = sizeZ + ClientGlobalSelection.tilingSpacingZ;

                            // North
                            renderPatternPreview(mc, player, poseStack, bufferSource, ClientClipboard.getClipboard(),
                                    start.north(stepZ), 1, Direction.NORTH, 0.3f, 0.5f, 1.0f);
                            // South
                            renderPatternPreview(mc, player, poseStack, bufferSource, ClientClipboard.getClipboard(),
                                    start.south(stepZ), 1, Direction.NORTH, 0.3f, 0.5f, 1.0f);
                            // East
                            renderPatternPreview(mc, player, poseStack, bufferSource, ClientClipboard.getClipboard(),
                                    start.east(stepX), 1, Direction.NORTH, 0.3f, 0.5f, 1.0f);
                            // West
                            renderPatternPreview(mc, player, poseStack, bufferSource, ClientClipboard.getClipboard(),
                                    start.west(stepX), 1, Direction.NORTH, 0.3f, 0.5f, 1.0f);
                        }
                    }
                }
            } else {
                BlockPos start = ClientGlobalSelection.anchorPos != null ? ClientGlobalSelection.anchorPos : lookPos;
                BlockPos end = lookPos;

                BlockPos min = new BlockPos(Math.min(start.getX(), end.getX()), Math.min(start.getY(), end.getY()),
                        Math.min(start.getZ(), end.getZ()));
                BlockPos max = new BlockPos(Math.max(start.getX(), end.getX()), Math.max(start.getY(), end.getY()),
                        Math.max(start.getZ(), end.getZ()));

                float r = 1f, g = 1f, b = 1f;
                if (ClientGlobalSelection.currentMode == ClientGlobalSelection.SelectionMode.COPY) {
                    r = 0f;
                    g = 1f;
                    b = 0f;
                } // Green
                if (ClientGlobalSelection.currentMode == ClientGlobalSelection.SelectionMode.CUT) {
                    r = 1f;
                    g = 0.5f;
                    b = 0f;
                } // Orange
                if (ClientGlobalSelection.currentMode == ClientGlobalSelection.SelectionMode.DECONSTRUCT) {
                    r = 1f;
                    g = 0f;
                    b = 0f;
                } // Red

                VertexConsumer lines = bufferSource.getBuffer(RenderType.lines());
                VertexConsumer tint = bufferSource.getBuffer(RenderType.translucent());

                drawBox(poseStack, lines, tint, min, max, r, g, b, 0.2f);
            }

            poseStack.popPose();
            bufferSource.endBatch();
        }

        private static void renderDebugInfo(RenderLevelStageEvent event) {
            Minecraft mc = Minecraft.getInstance();
            if (!mc.getDebugOverlay().showDebugScreen())
                return;

            GhostJobManager manager = GhostJobManager.get(mc.level);
            PoseStack poseStack = event.getPoseStack();
            Vec3 cameraPos = event.getCamera().getPosition();
            MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

            poseStack.pushPose();
            poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

            BlockPos pPos = mc.player.blockPosition();
            for (int x = -10; x <= 10; x++) {
                for (int y = -5; y <= 5; y++) {
                    for (int z = -10; z <= 10; z++) {
                        BlockPos pos = pPos.offset(x, y, z);
                        if (mc.level.getBlockEntity(
                                pos) instanceof com.example.ghostlib.block.entity.GhostBlockEntity gbe) {
                            renderFloatingText(poseStack, bufferSource, pos, "G: " + gbe.getCurrentState().name(),
                                    0xFFFFFF);
                        }
                    }
                }
            }

            Map<Long, Map<BlockPos, BlockState>> jobs = manager.getDirectDeconstructJobs();
            for (Map<BlockPos, BlockState> map : jobs.values()) {
                for (BlockPos pos : map.keySet()) {
                    renderFloatingText(poseStack, bufferSource, pos, "Job: Deconstruct", 0xFF0000);
                }
            }

            poseStack.popPose();
            bufferSource.endBatch();
        }

        private static void renderFloatingText(PoseStack poseStack, MultiBufferSource bufferSource, BlockPos pos,
                String text, int color) {
            Minecraft mc = Minecraft.getInstance();
            if (pos.distToCenterSqr(mc.player.position()) > 400)
                return; // Distance cull

            poseStack.pushPose();
            poseStack.translate(pos.getX() + 0.5, pos.getY() + 1.5, pos.getZ() + 0.5);
            poseStack.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
            poseStack.scale(-0.025F, -0.025F, 0.025F);

            org.joml.Matrix4f matrix4f = poseStack.last().pose();
            float f1 = mc.options.getBackgroundOpacity(0.25F);
            int j = (int) (f1 * 255.0F) << 24;
            net.minecraft.client.gui.Font font = mc.font;
            float f2 = (float) (-font.width(text) / 2);

            font.drawInBatch(text, f2, 0, color, false, matrix4f, bufferSource,
                    net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH, j, 15728880);
            font.drawInBatch(text, f2, 0, color, false, matrix4f, bufferSource,
                    net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);

            poseStack.popPose();
        }

        private static void renderDeconstructionMarkers(RenderLevelStageEvent event) {
            Minecraft mc = Minecraft.getInstance();
            GhostJobManager manager = GhostJobManager.get(mc.level);
            Map<Long, Map<BlockPos, BlockState>> jobs = manager.getDirectDeconstructJobs();

            PoseStack poseStack = event.getPoseStack();
            Vec3 cameraPos = event.getCamera().getPosition();
            MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

            VertexConsumer lineConsumer = bufferSource.getBuffer(RenderType.lines());

            poseStack.pushPose();
            poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

            for (Map<BlockPos, BlockState> map : jobs.values()) {
                for (BlockPos pos : map.keySet()) {
                    // Draw Red wireframe for real blocks marked for deconstruction
                    float s = 0.005f;
                    net.minecraft.client.renderer.LevelRenderer.renderLineBox(poseStack, lineConsumer,
                            pos.getX() - s, pos.getY() - s, pos.getZ() - s,
                            pos.getX() + 1 + s, pos.getY() + 1 + s, pos.getZ() + 1 + s,
                            1.0f, 0.0f, 0.0f, 1.0f);
                }
            }

            poseStack.popPose();
        }

        private static void drawBox(PoseStack poseStack, VertexConsumer lineConsumer, VertexConsumer tintConsumer,
                BlockPos min, BlockPos max, float r, float g, float b, float a) {
            float s = 0.005f;
            net.minecraft.client.renderer.LevelRenderer.renderLineBox(poseStack, lineConsumer,
                    min.getX() - s, min.getY() - s, min.getZ() - s,
                    max.getX() + 1 + s, max.getY() + 1 + s, max.getZ() + 1 + s,
                    r, g, b, 1.0f);

            Matrix4f matrix = poseStack.last().pose();
            float x1 = min.getX() - 0.001f;
            float y1 = min.getY() - 0.001f;
            float z1 = min.getZ() - 0.001f;
            float x2 = max.getX() + 1.001f;
            float y2 = max.getY() + 1.001f;
            float z2 = max.getZ() + 1.001f;

            addQuad(matrix, tintConsumer, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, r, g, b, a);
            addQuad(matrix, tintConsumer, x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1, r, g, b, a);
            addQuad(matrix, tintConsumer, x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1, r, g, b, a);
            addQuad(matrix, tintConsumer, x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2, r, g, b, a);
            addQuad(matrix, tintConsumer, x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1, r, g, b, a);
            addQuad(matrix, tintConsumer, x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2, r, g, b, a);
        }

        private static void addQuad(Matrix4f matrix, VertexConsumer consumer, float x1, float y1, float z1, float x2,
                float y2, float z2, float x3, float y3, float z3, float x4, float y4, float z4, float r, float g,
                float b, float a) {
            consumer.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a).setUv(0, 0).setLight(15728880).setNormal(0, 1,
                    0);
            consumer.addVertex(matrix, x2, y2, z2).setColor(r, g, b, a).setUv(0, 0).setLight(15728880).setNormal(0, 1,
                    0);
            consumer.addVertex(matrix, x3, y3, z3).setColor(r, g, b, a).setUv(0, 0).setLight(15728880).setNormal(0, 1,
                    0);
            consumer.addVertex(matrix, x4, y4, z4).setColor(r, g, b, a).setUv(0, 0).setLight(15728880).setNormal(0, 1,
                    0);
        }

        private static void renderTiledPreview(Minecraft mc, Player player, PoseStack poseStack,
                MultiBufferSource bufferSource, CompoundTag tag, BlockPos start, BlockPos end) {
            int sizeX = Math.max(1, tag.getInt("SizeX"));
            int sizeZ = Math.max(1, tag.getInt("SizeZ"));
            
            int stepX = sizeX + ClientGlobalSelection.tilingSpacingX;
            int stepZ = sizeZ + ClientGlobalSelection.tilingSpacingZ;

            int dx = end.getX() - start.getX();
            int dz = end.getZ() - start.getZ();
            Direction dir;

            boolean isGrid = com.mojang.blaze3d.platform.InputConstants.isKeyDown(mc.getWindow().getWindow(),
                    org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL);

            List<BlockPos> origins = new java.util.ArrayList<>();

            if (isGrid) {
                int xDir = dx >= 0 ? 1 : -1;
                int zDir = dz >= 0 ? 1 : -1;
                int xRange = Math.abs(dx);
                int zRange = Math.abs(dz);

                for (int x = 0; x <= xRange; x += Math.max(1, stepX)) {
                    for (int z = 0; z <= zRange; z += Math.max(1, stepZ)) {
                        origins.add(start.offset(x * xDir, 0, z * zDir));
                    }
                }
            } else {
                if (Math.abs(dx) >= Math.abs(dz)) {
                    dir = dx >= 0 ? Direction.EAST : Direction.WEST;
                    int steps = Math.abs(dx) / Math.max(1, stepX);
                    int dirInt = dx >= 0 ? 1 : -1;
                    for (int i = 0; i <= steps; i++)
                        origins.add(start.offset(i * stepX * dirInt, 0, 0));
                } else {
                    dir = dz >= 0 ? Direction.SOUTH : Direction.NORTH;
                    int steps = Math.abs(dz) / Math.max(1, stepZ);
                    int dirInt = dz >= 0 ? 1 : -1;
                    for (int i = 0; i <= steps; i++)
                        origins.add(start.offset(0, 0, i * stepZ * dirInt));
                }
            }

            for (BlockPos origin : origins) {
                renderPatternPreview(mc, player, poseStack, bufferSource, tag, origin, 1, Direction.NORTH, 1.0f, 1.0f, 1.0f);
            }
        }

        private static void renderPatternPreview(Minecraft mc, Player player, PoseStack poseStack,
                MultiBufferSource bufferSource, CompoundTag tag, BlockPos initialOrigin, int count, Direction dir, float alpha, float rMult, float bMult) {
            ListTag patternList = tag.getList("Pattern", 10);
            BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();
            int bpSizeX = Math.max(1, tag.getInt("SizeX"));
            int bpSizeZ = Math.max(1, tag.getInt("SizeZ"));

            for (int r = 0; r < count; r++) {
                BlockPos currentOrigin = initialOrigin;
                if (r > 0) {
                    if (dir.getAxis() == Direction.Axis.X)
                        currentOrigin = initialOrigin.relative(dir, r * bpSizeX);
                    else if (dir.getAxis() == Direction.Axis.Z)
                        currentOrigin = initialOrigin.relative(dir, r * bpSizeZ);
                }

                for (int i = 0; i < patternList.size(); i++) {
                    CompoundTag blockTag = patternList.getCompound(i);
                    BlockPos relative = NbtUtils.readBlockPos(blockTag, "Rel").orElse(BlockPos.ZERO);
                    BlockState state = NbtUtils.readBlockState(
                            player.level().holderLookup(net.minecraft.core.registries.Registries.BLOCK),
                            blockTag.getCompound("State"));
                    BlockPos target = currentOrigin.offset(relative);

                    BlockState existingState = player.level().getBlockState(target);
                    float red, g, b;
                    
                    // HIGHLIGHT LOGIC:
                    // If spacing is positive (Gap), use Cyan/Blue
                    // If spacing is negative (Overlap), use Magenta
                    boolean isOverlapX = ClientGlobalSelection.tilingSpacingX < 0;
                    boolean isOverlapZ = ClientGlobalSelection.tilingSpacingZ < 0;
                    boolean isGapX = ClientGlobalSelection.tilingSpacingX > 0;
                    boolean isGapZ = ClientGlobalSelection.tilingSpacingZ > 0;

                    if (existingState.isAir() || existingState.getBlock() instanceof GhostBlock) {
                        if (isOverlapX || isOverlapZ) {
                            red = 1.0f; g = 0.4f; b = 1.0f; // Magenta overlap
                        } else if (isGapX || isGapZ) {
                            red = 0.4f; g = 1.0f; b = 0.4f; // Green gap
                        } else {
                            red = 0.5f; g = 0.7f; b = 1.0f; // Default Blue
                        }
                    } else {
                        red = 1.0f; g = 0.2f; b = 0.2f; // Obstruction Red
                    }

                    poseStack.pushPose();
                    poseStack.translate(target.getX(), target.getY(), target.getZ());
                    blockRenderer.getModelRenderer().renderModel(poseStack.last(),
                            bufferSource.getBuffer(RenderType.translucent()),
                            state, blockRenderer.getBlockModel(state), red * rMult, g, b * bMult, 15728880,
                            net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, ModelData.EMPTY,
                            RenderType.translucent());
                    poseStack.popPose();
                }
            }
        }
    }
}
