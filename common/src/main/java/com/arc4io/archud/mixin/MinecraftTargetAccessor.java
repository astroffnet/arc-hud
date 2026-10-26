package com.arc4io.archud.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Подмена основного буфера кадра на время отрисовки мира (масштаб рендера). */
@Mixin(Minecraft.class)
public interface MinecraftTargetAccessor {
    @Mutable
    @Accessor("mainRenderTarget")
    void archud$setMainRenderTarget(RenderTarget target);
}
