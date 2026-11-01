package com.arc4io.archud.mixin;

import com.arc4io.archud.forge.compat.AetherGloves;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Punchy рисует руку от первого лица своей моделью (ванильная рука и проход Aether ему не нужны) —
 * перчатка Aether садится на руку Punchy, со всеми его анимациями.
 */
@Pseudo
@Mixin(targets = "punchy.client.render.PunchyArmRenderer", remap = false)
public abstract class PunchyGlovesMixin {
    @SuppressWarnings("rawtypes")
    @Inject(method = "renderArm", at = @At("HEAD"), remap = false, require = 0)
    private static void archud$armBegin(PlayerModel model, AbstractClientPlayer player, HumanoidArm arm, PoseStack pose, MultiBufferSource buffers,
                                        int light, ResourceLocation skin, boolean flag, float f, CallbackInfo ci) {
        AetherGloves.punchyArm(player, model, arm == HumanoidArm.RIGHT, buffers);
    }

    @SuppressWarnings("rawtypes")
    @Inject(method = "renderArm", at = @At("RETURN"), remap = false, require = 0)
    private static void archud$armEnd(PlayerModel model, AbstractClientPlayer player, HumanoidArm arm, PoseStack pose, MultiBufferSource buffers,
                                      int light, ResourceLocation skin, boolean flag, float f, CallbackInfo ci) {
        AetherGloves.end(player);
    }
}
