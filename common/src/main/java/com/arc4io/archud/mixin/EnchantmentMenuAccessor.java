package com.arc4io.archud.mixin;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/** Доступ к внутренностям стола зачарования для превью, выборочного зачарования и переброса вариантов. */
@Mixin(EnchantmentMenu.class)
public interface EnchantmentMenuAccessor {
    @Accessor("access")
    ContainerLevelAccess archud$access();

    @Accessor("enchantSlots")
    Container archud$slots();

    @Accessor("enchantmentSeed")
    DataSlot archud$seed();

    @Invoker("getEnchantmentList")
    List<net.minecraft.world.item.enchantment.EnchantmentInstance> archud$list(ItemStack stack, int slot, int cost);
}
