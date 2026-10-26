package com.arc4io.archud.mixin;

import com.arc4io.archud.client.perf.PerfHooks;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Оптимизация: слои моделей (броня, предметы в руках, плащи, стрелы…) рисуются только вблизи. */
@Mixin(LivingEntityRenderer.class)
public abstract class PerfLayerMixin {
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/layers/RenderLayer;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/Entity;FFFFFF)V"),
            require = 0)
    private void archud$layer(RenderLayer layer, PoseStack pose, MultiBufferSource buffers, int light, Entity entity,
                              float a, float b, float c, float d, float e, float f) {
        if (PerfHooks.skipLayer(layer, entity)) return;
        layer.render(pose, buffers, light, entity, a, b, c, d, e, f);
    }
}
