package com.arc4io.archud.mixin;

import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Queue;

@Mixin(ChunkRenderDispatcher.class)
public interface ChunkRenderDispatcherAccessor {
    @Accessor("toUpload")
    Queue<Runnable> archud$toUpload();
}
