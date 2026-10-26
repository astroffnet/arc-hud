package com.arc4io.archud.mixin;

import com.arc4io.archud.client.perf.RenderTweaks;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelReader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Тени сущностей — только вблизи. */
@Mixin(EntityRenderDispatcher.class)
public abstract class ShadowCullMixin {
    @Inject(method = "renderShadow", at = @At("HEAD"), cancellable = true, require = 0)
    private static void archud$shadow(PoseStack pose, MultiBufferSource buf, Entity e, float a, float b, LevelReader level, float r, CallbackInfo ci) {
        if (RenderTweaks.skipShadow(e)) ci.cancel();
    }
}
