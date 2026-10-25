package com.arc4io.archud.mixin;

import com.arc4io.archud.client.camera.FirstPersonCam;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.world.entity.EquipmentSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Своё тело от первого лица: у брони нет шлема и рукавов (иначе они висят перед камерой). */
@Mixin(HumanoidArmorLayer.class)
public abstract class BodyLayersMixin {
    @Inject(method = "setPartVisibility", at = @At("TAIL"), require = 0)
    private void archud$armor(HumanoidModel<?> model, EquipmentSlot slot, CallbackInfo ci) {
        if (!FirstPersonCam.renderingBody) return;
        model.head.visible = false;
        model.hat.visible = false;
        model.rightArm.visible = false;
        model.leftArm.visible = false;
    }
}
