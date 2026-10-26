package com.arc4io.archud.mixin;

import com.arc4io.archud.client.resources.FastReload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.server.packs.repository.PackRepository;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Применение пакетов: языковые пакеты — без полной перезагрузки ресурсов. */
@Mixin(Options.class)
public abstract class OptionsPacksMixin {
    @Inject(method = "updateResourcePacks", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$update(PackRepository repo, CallbackInfo ci) {
        if (!FastReload.enabled()) return;
        FastReload.applyPacks(Minecraft.getInstance(), repo);
        ci.cancel();
    }
}
