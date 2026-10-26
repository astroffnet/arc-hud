package com.arc4io.archud.mixin;

import com.arc4io.archud.client.crash.AntiCrash;
import net.minecraft.CrashReport;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Антикрашер: тик клиента обёрнут в try/catch. Низкий приоритет — если стоит другой антикрашер
 * (Not Enough Crashes и т. п.), его перехват важнее и игра не падает из-за конфликта.
 */
@Mixin(value = Minecraft.class, priority = 500)
public abstract class AntiCrashMixin {
    @Redirect(method = "run", require = 0, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runTick(Z)V"))
    private void archud$guardedTick(Minecraft mc, boolean renderLevel) {
        AntiCrash.beforeTick(mc);
        try {
            ((MinecraftInvoker) mc).archud$runTick(renderLevel);
        } catch (Throwable t) {
            if (!AntiCrash.recover(mc, t, null)) throw AntiCrash.sneaky(t);
        }
    }

    @Inject(method = "delayCrashRaw", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$delayed(CrashReport report, CallbackInfo ci) {
        if (AntiCrash.delay(report)) ci.cancel();
    }
}
