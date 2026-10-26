package com.arc4io.archud.mixin;

import com.arc4io.archud.client.perf.PerfHooks;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.LivingEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Оптимизация: слои моделей (броня, предметы в руках, плащи, стрелы…) рисуются только вблизи.
 * Без @Redirect на вызов слоя — его перехватывают другие моды (Legends и др.), и два перехвата
 * одного места роняют игру. Вместо этого на время отрисовки дальней сущности список слоёв
 * подменяется отфильтрованной копией, после — возвращается исходный.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class PerfLayerMixin {
    @SuppressWarnings("rawtypes")
    @Unique private List archud$original;

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), require = 0)
    private void archud$filterLayers(LivingEntity entity, float yaw, float pt, PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo ci) {
        LivingEntityRendererAccessor acc = (LivingEntityRendererAccessor) (Object) this;
        if (archud$original != null) { acc.archud$setLayers(archud$original); archud$original = null; } // прошлый кадр прервался исключением
        List src = acc.archud$getLayers();
        List kept = null;
        for (int i = 0, n = src.size(); i < n; i++) {
            RenderLayer layer = (RenderLayer) src.get(i);
            boolean skip;
            try {
                skip = PerfHooks.skipLayer(layer, entity);
            } catch (Throwable t) {
                skip = false;
            }
            if (skip) {
                if (kept == null) kept = new ArrayList(src.subList(0, i));
            } else if (kept != null) kept.add(layer);
        }
        if (kept != null) {
            archud$original = src;
            acc.archud$setLayers(kept);
        }
    }

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("RETURN"), require = 0)
    private void archud$restoreLayers(LivingEntity entity, float yaw, float pt, PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo ci) {
        if (archud$original != null) {
            ((LivingEntityRendererAccessor) (Object) this).archud$setLayers(archud$original);
            archud$original = null;
        }
    }
}
