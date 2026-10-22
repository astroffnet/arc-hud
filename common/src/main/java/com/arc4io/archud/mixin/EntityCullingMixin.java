package com.arc4io.archud.mixin;

import com.arc4io.archud.client.perf.Culler;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Сущности за стенами (и дальний декор) не рисуются. Проверка идёт после ванильного отсечения по полю зрения. */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityCullingMixin {
    @Inject(method = "shouldRender", at = @At("RETURN"), cancellable = true, require = 0)
    private void archud$cull(Entity entity, Frustum frustum, double camX, double camY, double camZ, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && !Culler.shouldRenderEntity(entity)) cir.setReturnValue(false);
    }
}
