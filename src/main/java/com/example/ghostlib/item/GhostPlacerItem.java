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
                if (this.renderer == null) {
                    this.renderer = new GhostPlacerItemRenderer();
                }
                return this.renderer;
            }
        });
    }

    @Override
    public int getUseDuration(ItemStack pStack, LivingEntity p_344979_) { return 72000; }
    @Override
    public UseAnim getUseAnimation(ItemStack pStack) { return UseAnim.BOW; }

    private boolean hasPattern(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().contains("Pattern");
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level pLevel, Player pPlayer, InteractionHand pUsedHand) {
        ItemStack stack = pPlayer.getItemInHand(pUsedHand);
        BlockHitResult hitResult = raycast(pPlayer, 64.0D);

        if (hitResult.getType() == HitResult.Type.BLOCK) {
            BlockPos clickedPos = hitResult.getBlockPos();

            if (pPlayer.isShiftKeyDown() && !hasPattern(stack)) {
                CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
                handleCopy(pLevel, pPlayer, tag, clickedPos);
                setTag(stack, tag);
                return InteractionResultHolder.success(stack);
            }

            if (hasPattern(stack)) {
                if (pLevel.isClientSide) {
                    ClientGhostState.startDrag(clickedPos.relative(hitResult.getDirection()));
                }
                pPlayer.startUsingItem(pUsedHand);
                return InteractionResultHolder.consume(stack);
            }
        }

        pPlayer.startUsingItem(pUsedHand);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;

        ItemStack stack = context.getItemInHand();
        BlockHitResult hitResult = raycast(player, 64.0D);
        BlockPos clickedPos = (hitResult.getType() == HitResult.Type.BLOCK) ? hitResult.getBlockPos() : context.getClickedPos();

        if (player.isShiftKeyDown() && !hasPattern(stack)) {
            CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            handleCopy(context.getLevel(), player, tag, clickedPos);
            setTag(stack, tag);
            return InteractionResult.SUCCESS;
        }

        if (hasPattern(stack)) {
            if (context.getLevel().isClientSide) {
                Direction face = hitResult.getType() == HitResult.Type.BLOCK ? hitResult.getDirection() : context.getClickedFace();
                ClientGhostState.startDrag(clickedPos.relative(face));
            }
            return InteractionResult.PASS; 
        }

        return InteractionResult.FAIL;
    }

    private BlockHitResult raycast(Player player, double distance) {
        Vec3 eyePos = player.getEyePosition();
        Vec3 viewVec = player.getViewVector(1.0F);
        Vec3 targetVec = eyePos.add(viewVec.scale(distance));
        return player.level().clip(new net.minecraft.world.level.ClipContext(
            eyePos, targetVec, 
            net.minecraft.world.level.ClipContext.Block.OUTLINE, 
            net.minecraft.world.level.ClipContext.Fluid.NONE, 
            player
        ));
    }

    @Override
    public void releaseUsing(ItemStack pStack, Level pLevel, LivingEntity pLivingEntity, int pTimeCharged) {
        if (pLevel.isClientSide && pLivingEntity instanceof Player player) {
            if (ClientGhostState.isDragging) {
                BlockHitResult hitResult = raycast(player, 64.0D);
                BlockPos endPos = hitResult.getBlockPos();
                
                long window = Minecraft.getInstance().getWindow().getWindow();
                boolean isCtrl = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL) || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL);
                boolean isShift = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT) || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT);
                
                int mode = 0; // Normal
                if (isCtrl && isShift) mode = 2; // Full Force
                else if (isCtrl) mode = 1; // Semi Force
                
                Minecraft.getInstance().getConnection().send(new ServerboundPlaceGhostsPacket(ClientGhostState.anchorPos, endPos, mode));
                ClientGhostState.stopDrag();
            }
        }
    }

    /**
     * Processes the placement of ghost blocks based on a start and end position (drag area).
     * Handles different placement modes: Normal (Air only), Semi-Force (Replace solids), Full-Force (Clear volume).
     */
    public void handlePlacementPacket(ServerLevel level, ServerPlayer player, ItemStack stack, BlockPos start, BlockPos end, int placementMode) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!tag.contains("Pattern")) return;

        ListTag patternList = tag.getList("Pattern", 10);
        int sizeX = Math.max(1, tag.getInt("SizeX"));
        int sizeY = Math.max(1, tag.getInt("SizeY"));
        int sizeZ = Math.max(1, tag.getInt("SizeZ"));
        
        int dx = end.getX() - start.getX();
        int dz = end.getZ() - start.getZ();

        Direction dir;
        int count;

        // Determine tiling direction based on major axis of the drag
        if (Math.abs(dx) > Math.abs(dz)) {
            dir = dx > 0 ? Direction.EAST : Direction.WEST;
            count = 1 + Math.abs(dx) / sizeX;
        } else {
            dir = dz > 0 ? Direction.SOUTH : Direction.NORTH;
            count = 1 + Math.abs(dz) / sizeZ;
        }
        
        int placedCount = 0;
        List<GhostHistoryManager.GhostRecord> historyRecords = new ArrayList<>();
        GhostJobManager jobManager = GhostJobManager.get(level);
        
        for (int r = 0; r < count; r++) {
            BlockPos currentOrigin = start;
            if (r > 0) {
                 if (dir.getAxis() == Direction.Axis.X) currentOrigin = start.relative(dir, r * sizeX);
                 else if (dir.getAxis() == Direction.Axis.Z) currentOrigin = start.relative(dir, r * sizeZ);
            }
            
            Set<BlockPos> patternPositions = new HashSet<>();
            for (int i = 0; i < patternList.size(); i++) {
                patternPositions.add(NbtUtils.readBlockPos(patternList.getCompound(i), "Rel").orElse(BlockPos.ZERO));
            }

            for (int i = 0; i < patternList.size(); i++) {
                CompoundTag blockTag = patternList.getCompound(i);
                BlockPos relative = NbtUtils.readBlockPos(blockTag, "Rel").orElse(BlockPos.ZERO);
                BlockState bpState = NbtUtils.readBlockState(level.holderLookup(net.minecraft.core.registries.Registries.BLOCK), blockTag.getCompound("State"));
                
                if (bpState != null && !bpState.isAir()) {
                    BlockPos target = currentOrigin.offset(relative);
                    BlockState worldState = level.getBlockState(target);
                    
                    // Already correct? Skip.
                    if (worldState.equals(bpState)) {
                        jobManager.removeJob(target);
                        continue;
                    }

                    // Check for obstructions.
                    boolean isGhost = worldState.getBlock() instanceof GhostBlock;
                    boolean isFluid = !worldState.getFluidState().isEmpty();
                    boolean isAir = worldState.isAir();
                    boolean isReplaceable = worldState.canBeReplaced();

                    if (!isAir && !isGhost && !isFluid) {
                        // It is a solid object (Vegetation or Block).
                        
                        if (isReplaceable) {
                             // Case 1: Vegetation/Snow (Soft Obstruction).
                             // Always deconstruct these to "harvest" them (Silk Touch),
                             // ensuring the player has the item for potential Undos.
                             jobManager.registerDirectDeconstruct(target, bpState, level);
                             historyRecords.add(new GhostHistoryManager.GhostRecord(target.immutable(), worldState, bpState));
                             placedCount++;
                        } else {
                             // Case 2: Hard Obstruction (Stone, Logs, Machines).
                             // Only deconstruct if in Semi-Force (1) or Full-Force (2) mode.
                             if (placementMode >= 1) { 
                                jobManager.registerDirectDeconstruct(target, bpState, level);
                                historyRecords.add(new GhostHistoryManager.GhostRecord(target.immutable(), worldState, bpState));
                                placedCount++;
                             }
                        }
                    } else {
                        // Position is Clear (Air), Liquid, or existing Ghost.
                        // Place construction ghost.
                        level.setBlock(target, ModBlocks.GHOST_BLOCK.get().defaultBlockState(), 3);
                        if (level.getBlockEntity(target) instanceof GhostBlockEntity gbe) {
                            gbe.setTargetState(bpState);
                            gbe.setState(GhostBlockEntity.GhostState.UNASSIGNED);
                        }
                        
                        // History: If it was Fluid, record as AIR (Drones can't restore fluids).
                        // If it was Air/Ghost, record as is.
                        BlockState historyState = isFluid ? Blocks.AIR.defaultBlockState() : worldState;
                        historyRecords.add(new GhostHistoryManager.GhostRecord(target.immutable(), historyState, bpState));
                        placedCount++;
                    }
                }
            }

            // Full-Force Mode: Clear areas that should be air according to the blueprint
            if (placementMode == 2) {
                for (int x = 0; x < sizeX; x++) {
                    for (int y = 0; y < sizeY; y++) {
                        for (int z = 0; z < sizeZ; z++) {
                            BlockPos rel = new BlockPos(x, y, z);
                            if (!patternPositions.contains(rel)) {
                                BlockPos target = currentOrigin.offset(rel);
                                BlockState worldState = level.getBlockState(target);
                                // If there is a non-replaceable obstruction where the blueprint says AIR
                                if (!worldState.isAir() && !worldState.canBeReplaced() && !(worldState.getBlock() instanceof GhostBlock)) {
                                    jobManager.registerDirectDeconstruct(target, Blocks.AIR.defaultBlockState(), level);
                                    historyRecords.add(new GhostHistoryManager.GhostRecord(target.immutable(), worldState, Blocks.AIR.defaultBlockState()));
                                    placedCount++;
                                }
                            }
                        }
                    }
                }
            }
        }
        
        GhostHistoryManager.recordPlacement(player, historyRecords);
        player.displayClientMessage(Component.literal("Placed " + placedCount + " jobs."), true);
    }

    private void handleCopy(Level level, Player player, CompoundTag tag, BlockPos clickedPos) {
        if (!tag.contains("Pos1")) {
            tag.putLong("Pos1", clickedPos.asLong());
            if (level.isClientSide) player.displayClientMessage(Component.literal("First position set."), true);
        } else {
            if (level.isClientSide) return;
            BlockPos pos1 = BlockPos.of(tag.getLong("Pos1"));
            BlockPos min = new BlockPos(Math.min(pos1.getX(), clickedPos.getX()), Math.min(pos1.getY(), clickedPos.getY()), Math.min(pos1.getZ(), clickedPos.getZ()));
            BlockPos max = new BlockPos(Math.max(pos1.getX(), clickedPos.getX()), Math.max(pos1.getY(), clickedPos.getY()), Math.max(pos1.getZ(), clickedPos.getZ()));
            
            ListTag patternList = new ListTag();
            int realBlocksFound = 0;
            
            for (BlockPos p : BlockPos.betweenClosed(min, max)) {
                BlockState state = level.getBlockState(p);
                if (!state.isAir()) {
                    CompoundTag blockTag = new CompoundTag();
                    blockTag.put("Rel", NbtUtils.writeBlockPos(p.subtract(min)));
                    blockTag.put("State", NbtUtils.writeBlockState(state));
                    patternList.add(blockTag);
                    realBlocksFound++;
                }
            }
            tag.put("Pattern", patternList);
            tag.putInt("SizeX", max.getX() - min.getX() + 1);
            tag.putInt("SizeY", max.getY() - min.getY() + 1);
            tag.putInt("SizeZ", max.getZ() - min.getZ() + 1);
            tag.remove("Pos1");
            player.displayClientMessage(Component.literal("Copied " + realBlocksFound + " blocks."), false);
        }
    }

    private void setTag(ItemStack stack, CompoundTag tag) {
        if (tag.isEmpty()) stack.remove(DataComponents.CUSTOM_DATA);
        else stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }
}
