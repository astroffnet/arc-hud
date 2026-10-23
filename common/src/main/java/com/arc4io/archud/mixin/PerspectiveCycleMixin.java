package com.arc4io.archud.mixin;

import com.arc4io.archud.client.camera.ArcCamera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** F5: камера Arc встаёт в цикл видов (первое лицо → Arc → обычное третье → первое лицо). */
@Mixin(Minecraft.class)
public abstract class PerspectiveCycleMixin {
    @Redirect(method = "handleKeybinds", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/CameraType;cycle()Lnet/minecraft/client/CameraType;"))
    private CameraType archud$cycle(CameraType current) {
        return ArcCamera.nextPerspective(current);
    }
}
