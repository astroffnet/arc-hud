package com.arc4io.archud.mixin;

import com.arc4io.archud.client.camera.ArcCamera;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Камера Arc от третьего лица: позиция за плечом и свободный поворот. */
@Mixin(Camera.class)
public abstract class CameraSetupMixin {
    @Inject(method = "setup", at = @At("RETURN"), require = 0)
    private void archud$setup(BlockGetter level, Entity entity, boolean detached, boolean mirror, float partialTick, CallbackInfo ci) {
        if (!mirror) ArcCamera.setupCamera((Camera) (Object) this, entity, detached, partialTick);
    }
}
