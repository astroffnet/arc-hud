package com.arc4io.archud.mixin;

import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Пакет мода от клиента: 32 КиБ → 256 МиБ. */
@Mixin(ServerboundCustomPayloadPacket.class)
public abstract class ServerboundPayloadLimitMixin {
    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 32767), require = 0)
    private int archud$limit(int original) {
        return com.arc4io.archud.util.PacketLimits.payload(original);
    }
}
