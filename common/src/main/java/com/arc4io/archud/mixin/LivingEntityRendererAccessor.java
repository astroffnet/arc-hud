package com.arc4io.archud.mixin;

import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(LivingEntityRenderer.class)
public interface LivingEntityRendererAccessor {
    @SuppressWarnings("rawtypes")
    @Accessor("layers")
    List archud$getLayers();

    @SuppressWarnings("rawtypes")
    @Mutable
    @Accessor("layers")
    void archud$setLayers(List layers);
}
