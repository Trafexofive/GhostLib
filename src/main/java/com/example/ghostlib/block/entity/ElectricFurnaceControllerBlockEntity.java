package com.example.ghostlib.block.entity;

import com.example.ghostlib.multiblock.IMultiblockController;
import com.example.ghostlib.registry.ModBlockEntities;
import com.example.voltlink.api.IVoltReceiver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.EnergyStorage;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.neoforged.neoforge.items.ItemStackHandler;

public class ElectricFurnaceControllerBlockEntity extends BlockEntity implements IMultiblockController, IVoltReceiver {
    
    private final EnergyStorage energyStorage = new EnergyStorage(500000, 10000, 10000);
    private final ItemStackHandler inventory = new ItemStackHandler(2) {
        @Override
        protected void onContentsChanged(int slot) { setChanged(); }
    };

    private boolean isAssembled = false;
    private int cookTime = 0;
    private static final int COOK_TIME_TOTAL = 40; // 2 seconds (Fast)
    private static final int ENERGY_PER_TICK = 100;

    public ElectricFurnaceControllerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ELECTRIC_FURNACE_CONTROLLER.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            com.example.voltlink.network.GridManager.get(level).subscribe(this, level);
            com.example.voltlink.network.GridManager.get(level).addNode(getBlockPos(), level);
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide) {
            com.example.voltlink.network.GridManager.get(level).unsubscribe(this, level);
            com.example.voltlink.network.GridManager.get(level).removeNode(getBlockPos(), level);
        }
        super.setRemoved();
    }

    @Override
    public long getVoltDemand() {
        return energyStorage.getEnergyStored() < energyStorage.getMaxEnergyStored() ? 200 : 0;
    }

    @Override
    public void onVoltReceive(long amount) {
        energyStorage.receiveEnergy((int) amount, false);
    }

    @Override
    public BlockPos getVoltPos() {
        return getBlockPos();
    }

    public static void tick(Level level, BlockPos pos, BlockState state, ElectricFurnaceControllerBlockEntity be) {
        if (level.isClientSide || !be.isAssembled) return;

        ItemStack input = be.inventory.getStackInSlot(0);
        if (!input.isEmpty() && be.energyStorage.getEnergyStored() >= ENERGY_PER_TICK) {
            
            // 1.21.1 Recipe Input
            SingleRecipeInput recipeInput = new SingleRecipeInput(input);
            var recipe = level.getRecipeManager().getRecipeFor(RecipeType.SMELTING, recipeInput, level).orElse(null);

            if (recipe != null) {
                ItemStack result = recipe.value().getResultItem(level.registryAccess());
                if (be.inventory.insertItem(1, result, true).isEmpty()) {
                    be.energyStorage.extractEnergy(ENERGY_PER_TICK, false);
                    be.cookTime++;
                    if (be.cookTime >= COOK_TIME_TOTAL) {
                        be.cookTime = 0;
                        be.inventory.extractItem(0, 1, false);
                        be.inventory.insertItem(1, result.copy(), false);
                    }
                    be.setChanged();
                    return;
                }
            }
        }
        be.cookTime = Math.max(0, be.cookTime - 1);
    }

    public net.minecraft.world.InteractionResult handlePlayerInteraction(Player player, net.minecraft.world.InteractionHand hand) {
        if (level.isClientSide) return net.minecraft.world.InteractionResult.SUCCESS;
        
        ItemStack held = player.getItemInHand(hand);
        if (held.isEmpty()) {
            ItemStack out = inventory.getStackInSlot(1);
            if (!out.isEmpty()) {
                player.getInventory().add(inventory.extractItem(1, 64, false));
                return net.minecraft.world.InteractionResult.SUCCESS;
            }
            player.displayClientMessage(net.minecraft.network.chat.Component.literal("Energy: " + energyStorage.getEnergyStored() + " FE").withStyle(net.minecraft.ChatFormatting.GOLD), false);
            return net.minecraft.world.InteractionResult.SUCCESS;
        } else {
            ItemStack remaining = inventory.insertItem(0, held, false);
            player.setItemInHand(hand, remaining);
            return net.minecraft.world.InteractionResult.SUCCESS;
        }
    }

    @Override
    public boolean validateStructure() {
        BlockPos base = getBlockPos().offset(-1, 0, -1);
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 3; x++) {
                for (int z = 0; z < 3; z++) {
                    BlockPos p = base.offset(x, y, z);
                    if (p.equals(getBlockPos())) continue;
                    if (!(level.getBlockState(p).getBlock() instanceof com.example.ghostlib.block.MaterialStorageBlock)) return false;
                }
            }
        }
        return true;
    }

    @Override
    public void assemble() {
        this.isAssembled = true;
        BlockPos base = getBlockPos().offset(-1, 0, -1);
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 3; x++) {
                for (int z = 0; z < 3; z++) {
                    BlockPos p = base.offset(x, y, z);
                    if (level.getBlockEntity(p) instanceof MaterialStorageBlockEntity member) {
                        member.setControllerPos(getBlockPos());
                    }
                }
            }
        }
        setChanged();
    }

    @Override
    public void disassemble() {
        this.isAssembled = false;
        setChanged();
    }

    @Override
    public boolean isAssembled() {
        return isAssembled;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("inventory", inventory.serializeNBT(registries));
        tag.putInt("energy", energyStorage.getEnergyStored());
        tag.putBoolean("assembled", isAssembled);
        tag.putInt("cookTime", cookTime);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        inventory.deserializeNBT(registries, tag.getCompound("inventory"));
        energyStorage.receiveEnergy(tag.getInt("energy"), false);
        isAssembled = tag.getBoolean("assembled");
        cookTime = tag.getInt("cookTime");
    }

    public ItemStackHandler getInventory() { return inventory; }
    public EnergyStorage getEnergyStorage() { return energyStorage; }
}