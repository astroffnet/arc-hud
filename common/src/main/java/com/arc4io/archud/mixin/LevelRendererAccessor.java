package com.arc4io.archud.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.atomic.AtomicBoolean;

@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
    @SuppressWarnings("rawtypes")
    @Accessor("renderChunksInFrustum")
    ObjectArrayList archud$renderChunksInFrustum();

    @Accessor("needsFrustumUpdate")
    AtomicBoolean archud$needsFrustumUpdate();
}
