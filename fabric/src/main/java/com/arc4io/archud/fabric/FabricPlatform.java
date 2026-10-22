package com.arc4io.archud.fabric;

import com.arc4io.archud.fabric.compat.TrinketsCompat;
import com.arc4io.archud.platform.Platform;
import com.arc4io.archud.util.NbtEnergy;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;

public class FabricPlatform implements Platform {
    @Override
    public String loaderName() {
        return "Fabric";
    }

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public String modName(String namespace) {
        return FabricLoader.getInstance().getModContainer(namespace)
                .map(c -> c.getMetadata().getName())
                .orElse(namespace);
    }

    @Override
    public int modCount() {
        return FabricLoader.getInstance().getAllMods().size();
    }

    @Override
    public ItemStack findEquippedBackpack(Player player) {
        if (isModLoaded("trinkets")) {
            try {
                return TrinketsCompat.findBackpack(player);
            } catch (Throwable ignored) {
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public long[] getItemEnergy(ItemStack stack) {
        return NbtEnergy.read(stack);
    }
}
