package com.arc4io.archud.mixin;

import com.arc4io.archud.client.extras.TooltipExtras;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Дополнительные строки подсказки предмета (только клиент). */
@Mixin(ItemStack.class)
public abstract class ItemTooltipMixin {
    @Inject(method = "getTooltipLines", at = @At("RETURN"), require = 0)
    private void archud$tooltip(Player player, TooltipFlag flag, CallbackInfoReturnable<List<Component>> cir) {
        TooltipExtras.append((ItemStack) (Object) this, flag, cir.getReturnValue());
    }
}
