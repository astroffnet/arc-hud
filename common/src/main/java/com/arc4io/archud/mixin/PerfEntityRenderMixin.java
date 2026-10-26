package com.arc4io.archud.mixin;

import com.arc4io.archud.client.perf.PerfHooks;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Оптимизация: дальность сущностей по типам, лимит за кадр, огонь на дальних сущностях. */
@Mixin(EntityRenderDispatcher.class)
public abstract class PerfEntityRenderMixin {
    @Inject(method = "shouldRender", at = @At("RETURN"), cancellable = true, require = 0)
    private void archud$perfCull(Entity entity, Frustum frustum, double camX, double camY, double camZ, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && PerfHooks.skipEntity(entity)) cir.setReturnValue(false);
    }

    @Inject(method = "renderFlame", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$perfFire(PoseStack pose, MultiBufferSource buffers, Entity entity, CallbackInfo ci) {
        if (PerfHooks.skipFire(entity)) ci.cancel();
    }
}
