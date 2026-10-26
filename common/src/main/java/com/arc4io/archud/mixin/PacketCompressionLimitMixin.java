package com.arc4io.archud.mixin;

import net.minecraft.network.CompressionDecoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Сжатые пакеты: лимиты 2 и 8 МиБ → 512 МиБ. */
@Mixin(CompressionDecoder.class)
public abstract class PacketCompressionLimitMixin {
    @ModifyConstant(method = "decode", constant = {@Constant(intValue = 8388608), @Constant(intValue = 2097152)}, require = 0)
    private int archud$limit(int original) {
        return com.arc4io.archud.util.PacketLimits.frame(original);
    }
}
