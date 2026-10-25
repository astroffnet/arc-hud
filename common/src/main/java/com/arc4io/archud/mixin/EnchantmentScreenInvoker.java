package com.arc4io.archud.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Доступ к анимированной книге стола зачарования для нового экрана. */
@Mixin(EnchantmentScreen.class)
public interface EnchantmentScreenInvoker {
    @Invoker("renderBook")
    void archud$renderBook(GuiGraphics g, int x, int y, float partialTick);
}
