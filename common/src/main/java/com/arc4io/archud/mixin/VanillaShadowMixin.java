package com.arc4io.archud.mixin;

import com.arc4io.archud.config.ArcConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelReader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ванильный круглый «блин» под сущностями не рисуется, (настройка «Убрать круглую тень»). */
@Mixin(EntityRenderDispatcher.class)
public abstract class VanillaShadowMixin {
    @Inject(method = "renderShadow", at = @At("HEAD"), cancellable = true, require = 0)
    private static void archud$noBlob(PoseStack pose, MultiBufferSource buffers, Entity entity, float strength, float pt,
                                      LevelReader level, float radius, CallbackInfo ci) {
        if (ArcConfig.get().hideVanillaShadows) ci.cancel();
    }
}
