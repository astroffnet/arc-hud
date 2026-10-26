package com.arc4io.archud.mixin;

import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Пакет мода от сервера: 1 МиБ → 256 МиБ. */
@Mixin(ClientboundCustomPayloadPacket.class)
public abstract class ClientboundPayloadLimitMixin {
    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 1048576), require = 0)
    private int archud$limit(int original) {
        return com.arc4io.archud.util.PacketLimits.payload(original);
    }
}
