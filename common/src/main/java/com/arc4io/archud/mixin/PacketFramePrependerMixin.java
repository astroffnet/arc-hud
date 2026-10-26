package com.arc4io.archud.mixin;

import net.minecraft.network.Varint21LengthFieldPrepender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Отправка кадров больше 2 МиБ («unable to fit … into 3»). */
@Mixin(Varint21LengthFieldPrepender.class)
public abstract class PacketFramePrependerMixin {
    @ModifyConstant(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lio/netty/buffer/ByteBuf;Lio/netty/buffer/ByteBuf;)V",
            constant = @Constant(intValue = 3), require = 0)
    private int archud$frameBytes(int original) {
        return com.arc4io.archud.util.PacketLimits.varintBytes(original);
    }
}
