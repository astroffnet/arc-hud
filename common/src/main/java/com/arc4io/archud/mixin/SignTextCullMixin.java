package com.arc4io.archud.mixin;

import com.arc4io.archud.client.perf.RenderTweaks;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.SignRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Текст табличек — только вблизи. */
@Mixin(SignRenderer.class)
public abstract class SignTextCullMixin {
    @Inject(method = "renderSignText", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$text(BlockPos pos, SignText text, PoseStack pose, MultiBufferSource buf, int light, int lineHeight, int maxWidth, boolean front, CallbackInfo ci) {
        if (RenderTweaks.skipSignText(pos)) ci.cancel();
    }
}
