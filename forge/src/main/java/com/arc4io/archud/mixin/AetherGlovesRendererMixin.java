package com.arc4io.archud.mixin;

import com.arc4io.archud.forge.compat.AetherGloves;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.theillusivec4.curios.api.SlotContext;

/** Aether не рисует перчатку от третьего лица, если Arc HUD уже нарисовал её вместе с рукой. */
@Pseudo
@Mixin(targets = "com.aetherteam.aether.client.renderer.accessory.GlovesRenderer", remap = false)
public abstract class AetherGlovesRendererMixin {
    @SuppressWarnings("rawtypes")
    @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void archud$skipGloves(ItemStack stack, SlotContext ctx, PoseStack pose, RenderLayerParent parent, MultiBufferSource buffers, int light,
                                   float limbSwing, float limbSwingAmount, float pt, float age, float headYaw, float headPitch, CallbackInfo ci) {
        if (AetherGloves.handled(ctx.entity())) ci.cancel();
    }
}
