package com.arc4io.archud.mixin;

import com.arc4io.archud.client.camera.FirstPersonCam;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Улучшенный вид / бодикам: своё тело рисуется в мире и от первого лица. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererBodyMixin {
    @Redirect(method = "renderLevel", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;isDetached()Z"))
    private boolean archud$detached(Camera camera) {
        return camera.isDetached() || (FirstPersonCam.bodyVisible() && FirstPersonCam.isSelf(camera.getEntity()));
    }
}
