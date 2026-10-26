package com.arc4io.archud.mixin;

import com.arc4io.archud.client.perf.FastPaintings;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.entity.PaintingRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.entity.decoration.Painting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Быстрые картины: меньше геометрии при том же виде. */
@Mixin(PaintingRenderer.class)
public abstract class FastPaintingMixin {
    @Inject(method = "renderPainting", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$fast(PoseStack pose, VertexConsumer vc, Painting painting, int width, int height,
                             TextureAtlasSprite front, TextureAtlasSprite back, CallbackInfo ci) {
        if (!FastPaintings.enabled()) return;
        FastPaintings.render(pose, vc, painting, width, height, front, back);
        ci.cancel();
    }
}
