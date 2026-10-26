package com.arc4io.archud.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Смена ника на главном экране (офлайн-профиль). */
@Mixin(Minecraft.class)
public interface MinecraftUserAccessor {
    @Mutable
    @Accessor("user")
    void archud$setUser(User user);
}
