package com.arc4io.archud.mixin;

import com.arc4io.archud.forge.compat.AetherGloves;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Момент, когда рисуется рука игрока, — её итоговое положение со всеми анимациями модов. */
@Mixin(value = ModelPart.class, priority = 500)
public abstract class GloveArmPartMixin {
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V", at = @At("HEAD"), require = 0)
    private void archud$gloveArm(PoseStack pose, VertexConsumer vc, int light, int overlay, float r, float g, float b, float a, CallbackInfo ci) {
        AetherGloves.part((ModelPart) (Object) this, pose, light);
    }
}
