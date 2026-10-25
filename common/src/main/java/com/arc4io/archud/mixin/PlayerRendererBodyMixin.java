package com.arc4io.archud.mixin;

import com.arc4io.archud.client.camera.FirstPersonCam;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Своё тело от первого лица: без головы и рук (руки рисует обычный вид от первого лица), чуть сдвинуто назад. */
@Mixin(PlayerRenderer.class)
public abstract class PlayerRendererBodyMixin {
    private static boolean archud$self(AbstractClientPlayer p) {
        return FirstPersonCam.isSelf(p) && FirstPersonCam.bodyVisible()
                && !net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera().isDetached();
    }

    @Inject(method = "render(Lnet/minecraft/client/player/AbstractClientPlayer;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), require = 0)
    private void archud$begin(AbstractClientPlayer p, float yaw, float pt, PoseStack pose, MultiBufferSource buf, int light, CallbackInfo ci) {
        FirstPersonCam.renderingBody = archud$self(p);
    }

    @Inject(method = "render(Lnet/minecraft/client/player/AbstractClientPlayer;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("RETURN"), require = 0)
    private void archud$end(AbstractClientPlayer p, float yaw, float pt, PoseStack pose, MultiBufferSource buf, int light, CallbackInfo ci) {
        FirstPersonCam.renderingBody = false;
    }

    @Inject(method = "setModelProperties", at = @At("TAIL"), require = 0)
    private void archud$hide(AbstractClientPlayer p, CallbackInfo ci) {
        if (!FirstPersonCam.renderingBody) return;
        PlayerModel<AbstractClientPlayer> m = ((PlayerRenderer) (Object) this).getModel();
        m.head.visible = false;
        m.hat.visible = false;
        m.rightArm.visible = false;
        m.leftArm.visible = false;
        m.rightSleeve.visible = false;
        m.leftSleeve.visible = false;
    }

    @Inject(method = "getRenderOffset(Lnet/minecraft/client/player/AbstractClientPlayer;F)Lnet/minecraft/world/phys/Vec3;",
            at = @At("RETURN"), cancellable = true, require = 0)
    private void archud$offset(AbstractClientPlayer p, float pt, CallbackInfoReturnable<Vec3> cir) {
        if (!archud$self(p)) return;
        // назад по направлению взгляда (а не тела), чтобы плечи не попадали в кадр; сильнее, когда смотрим вниз
        float yaw = p.getViewYRot(pt) * Mth.DEG_TO_RAD;
        float pitch = Math.max(0F, p.getViewXRot(pt));
        double back = 0.2 + 0.08 * pitch / 90.0;
        cir.setReturnValue(cir.getReturnValue().add(Mth.sin(yaw) * back, 0, -Mth.cos(yaw) * back));
    }
}
