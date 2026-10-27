package com.arc4io.archud.mixin;

import com.arc4io.archud.client.compat.PlayerAnimCompat;
import net.minecraft.client.model.HumanoidModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Аксессуары следуют сгибам рук/ног анимаций playerAnimator (после его собственного копирования). */
@Mixin(value = HumanoidModel.class, priority = 1100)
public abstract class HumanoidCopyMixin {
    @Inject(method = "copyPropertiesTo", at = @At("RETURN"), require = 0)
    private void archud$afterCopy(HumanoidModel<?> target, CallbackInfo ci) {
        PlayerAnimCompat.afterCopy((HumanoidModel<?>) (Object) this, target);
    }
}
