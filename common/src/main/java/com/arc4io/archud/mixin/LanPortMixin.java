package com.arc4io.archud.mixin;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ShareToLanScreen;
import net.minecraft.util.HttpUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** «Открыть для сети»: порт по умолчанию 25565 (если он свободен), а не случайный. */
@Mixin(ShareToLanScreen.class)
public abstract class LanPortMixin {
    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void archud$defaultPort(Screen last, CallbackInfo ci) {
        if (HttpUtil.isPortAvailable(25565)) ((ShareToLanScreenAccessor) this).archud$setPort(25565);
    }
}
