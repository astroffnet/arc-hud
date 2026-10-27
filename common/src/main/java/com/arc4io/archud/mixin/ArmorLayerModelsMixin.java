package com.arc4io.archud.mixin;

import com.arc4io.archud.client.compat.PlayerAnimCompat;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.resources.model.ModelManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Запоминаем модели брони — их сгибы playerAnimator обрабатывает сам. */
@Mixin(HumanoidArmorLayer.class)
public abstract class ArmorLayerModelsMixin {
    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void archud$remember(RenderLayerParent<?, ?> parent, HumanoidModel<?> inner, HumanoidModel<?> outer, ModelManager models, CallbackInfo ci) {
        PlayerAnimCompat.registerArmorModel(inner);
        PlayerAnimCompat.registerArmorModel(outer);
    }
}
