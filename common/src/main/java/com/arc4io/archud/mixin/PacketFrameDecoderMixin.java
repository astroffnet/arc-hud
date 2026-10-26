package com.arc4io.archud.mixin;

import net.minecraft.network.Varint21FrameDecoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Длина кадра: 3 байта VarInt (2 МиБ, «length wider than 21-bit») → 5 байт (полный int). */
@Mixin(Varint21FrameDecoder.class)
public abstract class PacketFrameDecoderMixin {
    @ModifyConstant(method = "decode", constant = @Constant(intValue = 3), require = 0)
    private int archud$frameBytes(int original) {
        return com.arc4io.archud.util.PacketLimits.varintBytes(original);
    }
}
