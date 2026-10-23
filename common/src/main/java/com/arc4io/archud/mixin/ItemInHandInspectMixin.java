package com.arc4io.archud.mixin;

import com.arc4io.archud.client.fx.WeaponInspect;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Осмотр предмета: поворот предмета от первого лица перед его отрисовкой. */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandInspectMixin {
    @Inject(method = "renderArmWithItem", require = 0,
            at = @At(value = "INVOKE", shift = At.Shift.BEFORE,
                    target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"))
    private void archud$inspect(AbstractClientPlayer player, float partialTick, float pitch, InteractionHand hand, float swing, ItemStack stack,
                                float equip, PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo ci) {
        if (player instanceof LocalPlayer lp) WeaponInspect.apply(pose, hand, lp, partialTick);
    }
}
