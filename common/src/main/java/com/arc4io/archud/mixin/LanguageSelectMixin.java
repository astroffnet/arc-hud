package com.arc4io.archud.mixin;

import com.arc4io.archud.client.resources.FastReload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LanguageSelectScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.CompletableFuture;

/** Ванильный экран языка (если новые экраны выключены): тоже быстрая смена. */
@Mixin(value = LanguageSelectScreen.class, priority = 500)
public abstract class LanguageSelectMixin {
    @Redirect(method = "onDone", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;reloadResourcePacks()Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Void> archud$reload(Minecraft mc) {
        if (!FastReload.enabled()) return mc.reloadResourcePacks();
        FastReload.applyLanguage(mc);
        return CompletableFuture.completedFuture(null);
    }
}
