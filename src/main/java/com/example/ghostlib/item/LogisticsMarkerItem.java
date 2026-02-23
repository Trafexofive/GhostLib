package com.example.ghostlib.item;

import com.example.ghostlib.util.LogisticsNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Marks inventories as part of the logistics network.
 * Right-click on a chest/barrel to add it to the network.
 * Sneak + right-click in air to unmark the block you're looking at.
 */
public class LogisticsMarkerItem extends Item {

    public LogisticsMarkerItem() {
        super(new Properties().stacksTo(64));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();

        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            player.displayClientMessage(Component.literal("No block entity here").withStyle(net.minecraft.ChatFormatting.RED), true);
            return InteractionResult.FAIL;
        }

        // Check if the block has item capability
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler == null) {
            player.displayClientMessage(Component.literal("Not a valid inventory").withStyle(net.minecraft.ChatFormatting.RED), true);
            return InteractionResult.FAIL;
        }

        // Add to logistics network
        LogisticsNetworkManager netMgr = LogisticsNetworkManager.get(level);
        int networkId = netMgr.joinOrCreateNetwork(pos, level);

        player.displayClientMessage(
            Component.literal("Marked for logistics (Network #" + networkId + ")").withStyle(net.minecraft.ChatFormatting.GREEN),
            true
        );

        // Spawn particles
        spawnMarkParticles(level, pos);

        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (player.isShiftKeyDown() && !level.isClientSide) {
            // Unmark mode - look at block and right-click
            var hit = player.pick(6.0, 0.0f, false);
            BlockPos pos = BlockPos.containing(hit.getLocation());

            LogisticsNetworkManager netMgr = LogisticsNetworkManager.get(level);
            Integer oldId = netMgr.getNetworkId(pos);
            netMgr.leaveNetwork(pos);

            if (oldId != null) {
                player.displayClientMessage(
                    Component.literal("Removed from logistics network").withStyle(net.minecraft.ChatFormatting.RED),
                    true
                );
                spawnUnmarkParticles(level, pos);
            } else {
                player.displayClientMessage(Component.literal("Not marked").withStyle(net.minecraft.ChatFormatting.YELLOW), true);
            }

            return InteractionResultHolder.success(stack);
        }

        return InteractionResultHolder.pass(stack);
    }

    private void spawnMarkParticles(Level level, BlockPos pos) {
        if (level.isClientSide) {
            BlockState state = level.getBlockState(pos);
            for (int i = 0; i < 10; i++) {
                double x = pos.getX() + 0.5 + (level.random.nextDouble() - 0.5) * 0.8;
                double y = pos.getY() + 0.5 + (level.random.nextDouble() - 0.5) * 0.8;
                double z = pos.getZ() + 0.5 + (level.random.nextDouble() - 0.5) * 0.8;
                level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, state), x, y, z, 0, 0.1, 0);
            }
        }
    }

    private void spawnUnmarkParticles(Level level, BlockPos pos) {
        if (level.isClientSide) {
            for (int i = 0; i < 8; i++) {
                double x = pos.getX() + 0.5 + (level.random.nextDouble() - 0.5) * 0.5;
                double y = pos.getY() + 1.0 + (level.random.nextDouble() - 0.5) * 0.5;
                double z = pos.getZ() + 0.5 + (level.random.nextDouble() - 0.5) * 0.5;
                level.addParticle(ParticleTypes.SMOKE, x, y, z, 0, -0.05, 0);
            }
        }
    }
}
