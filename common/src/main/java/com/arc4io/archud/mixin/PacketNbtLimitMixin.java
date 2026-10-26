package com.arc4io.archud.mixin;

import net.minecraft.network.FriendlyByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** NBT в пакете: 2 МиБ → 256 МиБ. */
@Mixin(FriendlyByteBuf.class)
public abstract class PacketNbtLimitMixin {
    @ModifyConstant(method = "readNbt()Lnet/minecraft/nbt/CompoundTag;", constant = @Constant(longValue = 2097152L), require = 0)
    private long archud$nbtLimit(long original) {
        return com.arc4io.archud.util.PacketLimits.nbt(original);
    }
}
