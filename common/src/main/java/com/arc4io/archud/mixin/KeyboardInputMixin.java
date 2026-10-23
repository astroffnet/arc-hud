package com.arc4io.archud.mixin;

import com.arc4io.archud.client.camera.ArcCamera;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Движение относительно камеры (W — «от камеры»), персонаж поворачивается по ходу движения. */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {
    @Inject(method = "tick", at = @At("RETURN"), require = 0)
    private void archud$remap(boolean slow, float sneakFactor, CallbackInfo ci) {
        ArcCamera.remapInput((Input) (Object) this);
    }
}
