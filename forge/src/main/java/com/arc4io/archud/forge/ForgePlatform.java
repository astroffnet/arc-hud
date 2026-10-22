package com.arc4io.archud.forge;

import com.arc4io.archud.forge.compat.CuriosCompat;
import com.arc4io.archud.platform.Platform;
import com.arc4io.archud.util.NbtEnergy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.Optional;

public class ForgePlatform implements Platform {
    @Override
    public String loaderName() {
        return "Forge";
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public String modName(String namespace) {
        return ModList.get().getModContainerById(namespace)
                .map(c -> c.getModInfo().getDisplayName())
                .orElse(namespace);
    }

    @Override
    public int modCount() {
        return ModList.get().size();
    }

    @Override
    public ItemStack findEquippedBackpack(Player player) {
        if (isModLoaded("curios")) {
            try {
                return CuriosCompat.findBackpack(player);
            } catch (Throwable ignored) {
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public long[] getItemEnergy(ItemStack stack) {
        Optional<IEnergyStorage> energy = stack.getCapability(ForgeCapabilities.ENERGY).resolve();
        if (energy.isPresent()) {
            return new long[]{energy.get().getEnergyStored(), energy.get().getMaxEnergyStored()};
        }
        return NbtEnergy.read(stack);
    }
}
