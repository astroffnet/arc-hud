package com.arc4io.archud.fabric.mixin;

import com.arc4io.archud.client.screen.ScreenSwapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Подмена главного меню и окна назначения клавиш на экраны Arc HUD.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen archud$swapScreen(Screen screen) {
        return ScreenSwapper.swap(screen, ((Minecraft) (Object) this).screen);
    }
}
