package com.arc4io.archud.mixin;

import net.minecraft.network.PacketEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Отправка: «Packet too big (… should be less than 8388608)» → 512 МиБ. */
@Mixin(PacketEncoder.class)
public abstract class PacketEncoderLimitMixin {
    @ModifyConstant(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V",
            constant = @Constant(intValue = 8388608), require = 0)
    private int archud$limit(int original) {
        return com.arc4io.archud.util.PacketLimits.frame(original);
    }
}
