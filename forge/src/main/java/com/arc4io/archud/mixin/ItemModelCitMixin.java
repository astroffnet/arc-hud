package com.arc4io.archud.mixin;

import com.arc4io.archud.forge.cit.CitManager;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** CIT (Forge): своя модель предмета по правилам OptiFine. */
@Mixin(ItemRenderer.class)
public abstract class ItemModelCitMixin {
    @Inject(method = "getModel", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$cit(ItemStack stack, Level level, LivingEntity entity, int seed, CallbackInfoReturnable<BakedModel> cir) {
        BakedModel m = CitManager.find(stack);
        if (m != null) cir.setReturnValue(m);
    }
}
