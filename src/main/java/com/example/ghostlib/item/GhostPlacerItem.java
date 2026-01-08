package com.example.ghostlib.item;

import com.example.ghostlib.GhostLib;
import com.example.ghostlib.block.entity.GhostBlockEntity;
import com.example.ghostlib.client.util.ClientGhostState;
import com.example.ghostlib.network.payload.ServerboundPlaceGhostsPacket;
import com.example.ghostlib.registry.ModBlocks;
import com.example.ghostlib.util.GhostJobManager;
import com.example.ghostlib.history.GhostHistoryManager;
import com.example.ghostlib.block.GhostBlock;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import com.example.ghostlib.client.renderer.GhostPlacerItemRenderer;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public class GhostPlacerItem extends Item {
    public GhostPlacerItem(Properties properties) {
        super(properties);
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            private GhostPlacerItemRenderer renderer;
            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (this.renderer == null) this.renderer = new GhostPlacerItemRenderer();
                return this.renderer;
            }
        });
    }

    @Override public int getUseDuration(ItemStack pStack, LivingEntity p_344979_) { return 72000; }
    @Override public UseAnim getUseAnimation(ItemStack pStack) { return UseAnim.BOW; }

    private boolean hasPattern(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().contains("Pattern");
    }
    
    public enum ToolMode {
        PLACE, DECONSTRUCT, CUT
    }

    public void setMode(ItemStack stack, int modeOrdinal) {
        if (modeOrdinal >= 0 && modeOrdinal < ToolMode.values().length) {
            CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            tag.putInt("ToolMode", modeOrdinal);
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
    }

    public ToolMode getMode(ItemStack stack) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (tag.contains("ToolMode")) {
            return ToolMode.values()[Math.max(0, Math.min(ToolMode.values().length - 1, tag.getInt("ToolMode")))];
        }
        return ToolMode.PLACE;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level pLevel, Player pPlayer, InteractionHand pUsedHand) {
        ItemStack stack = pPlayer.getItemInHand(pUsedHand);
        BlockHitResult hitResult = raycast(pPlayer, 64.0D);
        if (hitResult.getType() == HitResult.Type.BLOCK) {
            BlockPos clickedPos = hitResult.getBlockPos();
            ToolMode mode = getMode(stack);
            
            // Selection Logic (Shift+Click) for ALL modes that might need it (Copy, Cut, Deconstruct)
            if (pPlayer.isShiftKeyDown()) {
                if (mode == ToolMode.PLACE && !hasPattern(stack)) {
                    // Copy Mode
                    CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
                    handleCopy(pLevel, pPlayer, tag, clickedPos);
                    stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
                    return InteractionResultHolder.success(stack);
                } else if (mode == ToolMode.CUT || mode == ToolMode.DECONSTRUCT) {
                    // Cut/Deconstruct Selection Mode
                    CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
                    if (!tag.contains("Pos1")) {
                        tag.putLong("Pos1", clickedPos.asLong());
                        if (pLevel.isClientSide) pPlayer.displayClientMessage(Component.literal("First position set."), true);
                    } else {
                        // Second position set -> Execute immediately
                        BlockPos pos1 = BlockPos.of(tag.getLong("Pos1"));
                        tag.remove("Pos1");
                        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
                        
                        if (!pLevel.isClientSide) {
                            executeAreaAction((ServerLevel)pLevel, (ServerPlayer)pPlayer, stack, pos1, clickedPos, mode);
                        }
                    }
                    stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
                    return InteractionResultHolder.success(stack);
                }
            }
            
            // Dragging Logic (Normal Click)
            if (hasPattern(stack) || mode == ToolMode.CUT || mode == ToolMode.DECONSTRUCT) {
                if (pLevel.isClientSide) ClientGhostState.startDrag(clickedPos.relative(hitResult.getDirection()));
                pPlayer.startUsingItem(pUsedHand);
                return InteractionResultHolder.consume(stack);
            }
        }
        pPlayer.startUsingItem(pUsedHand);
        return InteractionResultHolder.consume(stack);
    }

    private BlockHitResult raycast(Player player, double distance) {
        Vec3 eyePos = player.getEyePosition();
        Vec3 targetVec = eyePos.add(player.getViewVector(1.0F).scale(distance));
        return player.level().clip(new net.minecraft.world.level.ClipContext(eyePos, targetVec, net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, player));
    }

    @Override
    public void releaseUsing(ItemStack pStack, Level pLevel, LivingEntity pLivingEntity, int pTimeCharged) {
        if (pLevel.isClientSide && pLivingEntity instanceof Player player) {
            if (ClientGhostState.isDragging) {
                BlockHitResult hitResult = raycast(player, 64.0D);
                int mode = 0;
                long window = Minecraft.getInstance().getWindow().getWindow();
                if (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL)) mode = 1;
                Minecraft.getInstance().getConnection().send(new ServerboundPlaceGhostsPacket(ClientGhostState.anchorPos, hitResult.getBlockPos(), mode));
                ClientGhostState.stopDrag();
            }
        }
    }

    public void handlePlacementPacket(ServerLevel level, ServerPlayer player, ItemStack stack, BlockPos start, BlockPos end, int placementMode) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        ToolMode toolMode = getMode(stack);

        // Handle Cut/Deconstruct drag without needing a pattern first
        if (toolMode != ToolMode.PLACE && !tag.contains("Pattern")) {
            executeAreaAction(level, player, stack, start, end, toolMode);
            return;
        }

        if (!tag.contains("Pattern")) return;
        ListTag patternList = tag.getList("Pattern", 10);
        
        List<GhostHistoryManager.StateChange> changes = new ArrayList<>();
        
        // Calculate Tiling Positions
        List<BlockPos> placementOrigins = new ArrayList<>();
        
        // Determine pattern size/bounds to calculate spacing
        int sizeX = tag.getInt("SizeX");
        int sizeY = tag.getInt("SizeY");
        int sizeZ = tag.getInt("SizeZ");
        if (sizeX == 0) sizeX = 1; // Sanity check
        if (sizeZ == 0) sizeZ = 1;

        if (placementMode == 1) { // Area Mode (Grid)
             int xDir = end.getX() >= start.getX() ? 1 : -1;
             int zDir = end.getZ() >= start.getZ() ? 1 : -1;
             
             int xRange = Math.abs(end.getX() - start.getX());
             int zRange = Math.abs(end.getZ() - start.getZ());
             
             for (int x = 0; x <= xRange; x += sizeX) {
                 for (int z = 0; z <= zRange; z += sizeZ) {
                     placementOrigins.add(start.offset(x * xDir, 0, z * zDir));
                 }
             }
        } else { // Line Mode (Default)
             int dx = end.getX() - start.getX();
             int dz = end.getZ() - start.getZ();
             
             if (Math.abs(dx) >= Math.abs(dz)) {
                 int steps = Math.abs(dx) / sizeX;
                 int dir = dx >= 0 ? 1 : -1;
                 for (int i=0; i<=steps; i++) {
                     placementOrigins.add(start.offset(i * sizeX * dir, 0, 0));
                 }
             } else {
                 int steps = Math.abs(dz) / sizeZ;
                 int dir = dz >= 0 ? 1 : -1;
                 for (int i=0; i<=steps; i++) {
                     placementOrigins.add(start.offset(0, 0, i * sizeZ * dir));
                 }
             }
        }

        // Execute Placement for each origin
        for (BlockPos origin : placementOrigins) {
            for (int i = 0; i < patternList.size(); i++) {
                CompoundTag blockTag = patternList.getCompound(i);
                BlockPos rel = NbtUtils.readBlockPos(blockTag, "Rel").orElse(BlockPos.ZERO);
                BlockState bpState = NbtUtils.readBlockState(level.holderLookup(net.minecraft.core.registries.Registries.BLOCK), blockTag.getCompound("State"));
                
                BlockPos target = origin.offset(rel);
                BlockState worldState = level.getBlockState(target);

                if (toolMode == ToolMode.PLACE) {
                    if (bpState != null && !bpState.isAir() && !worldState.equals(bpState)) {
                        if (!worldState.isAir() && !worldState.canBeReplaced() && !(worldState.getBlock() instanceof GhostBlock)) {
                            changes.add(new GhostHistoryManager.StateChange(target.immutable(), worldState, bpState));
                            GhostJobManager.get(level).registerDirectDeconstruct(target, bpState, level);
                        } else {
                            if (worldState.getBlock() == ModBlocks.GHOST_BLOCK.get()) {
                                if (level.getBlockEntity(target) instanceof GhostBlockEntity gbe && gbe.getTargetState().equals(bpState)) continue; 
                            }
                            changes.add(new GhostHistoryManager.StateChange(target.immutable(), worldState, bpState));
                            level.setBlock(target, ModBlocks.GHOST_BLOCK.get().defaultBlockState(), 3);
                            if (level.getBlockEntity(target) instanceof GhostBlockEntity gbe) {
                                gbe.setTargetState(bpState);
                                gbe.setState(GhostBlockEntity.GhostState.UNASSIGNED);
                            }
                        }
                    }
                } else if (toolMode == ToolMode.DECONSTRUCT || toolMode == ToolMode.CUT) {
                     if (!worldState.isAir()) {
                         GhostJobManager.get(level).registerDirectDeconstruct(target, Blocks.AIR.defaultBlockState(), level);
                     }
                }
            }
        }
        GhostHistoryManager.recordAction(player, changes);
    }

    private void executeAreaAction(ServerLevel level, ServerPlayer player, ItemStack stack, BlockPos start, BlockPos end, ToolMode mode) {
         BlockPos min = new BlockPos(Math.min(start.getX(), end.getX()), Math.min(start.getY(), end.getY()), Math.min(start.getZ(), end.getZ()));
         BlockPos max = new BlockPos(Math.max(start.getX(), end.getX()), Math.max(start.getY(), end.getY()), Math.max(start.getZ(), end.getZ()));
         
         List<GhostHistoryManager.StateChange> changes = new ArrayList<>();

         if (mode == ToolMode.CUT) {
             CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
             handleCopyLogic(tag, level, min, max);
             stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
             player.displayClientMessage(Component.literal("Cut to clipboard."), true);
         }
         
         for (BlockPos p : BlockPos.betweenClosed(min, max)) {
             BlockState worldState = level.getBlockState(p);
             if (!worldState.isAir()) {
                 changes.add(new GhostHistoryManager.StateChange(p.immutable(), worldState, Blocks.AIR.defaultBlockState()));
                 GhostJobManager.get(level).registerDirectDeconstruct(p, Blocks.AIR.defaultBlockState(), level);
             }
         }
         GhostHistoryManager.recordAction(player, changes);
    }

    private void handleCopy(Level level, Player player, CompoundTag tag, BlockPos clickedPos) {
        if (!tag.contains("Pos1")) {
            tag.putLong("Pos1", clickedPos.asLong());
            if (level.isClientSide) player.displayClientMessage(Component.literal("First position set."), true);
        } else {
            BlockPos pos1 = BlockPos.of(tag.getLong("Pos1"));
            BlockPos min = new BlockPos(Math.min(pos1.getX(), clickedPos.getX()), Math.min(pos1.getY(), clickedPos.getY()), Math.min(pos1.getZ(), clickedPos.getZ()));
            BlockPos max = new BlockPos(Math.max(pos1.getX(), clickedPos.getX()), Math.max(pos1.getY(), clickedPos.getY()), Math.max(pos1.getZ(), clickedPos.getZ()));
            
            handleCopyLogic(tag, level, min, max);

            tag.remove("Pos1");
            if (level.isClientSide) player.displayClientMessage(Component.literal("Structure Copied."), false);
        }
    }

    private void handleCopyLogic(CompoundTag tag, Level level, BlockPos min, BlockPos max) {
        ListTag patternList = new ListTag();
        for (BlockPos p : BlockPos.betweenClosed(min, max)) {
            BlockState s = level.getBlockState(p);
            if (!s.isAir()) {
                CompoundTag bTag = new CompoundTag();
                bTag.put("Rel", NbtUtils.writeBlockPos(p.subtract(min)));
                bTag.put("State", NbtUtils.writeBlockState(s));
                patternList.add(bTag);
            }
        }
        tag.put("Pattern", patternList);
        tag.putInt("SizeX", (max.getX() - min.getX()) + 1);
        tag.putInt("SizeY", (max.getY() - min.getY()) + 1);
        tag.putInt("SizeZ", (max.getZ() - min.getZ()) + 1);
    }
}