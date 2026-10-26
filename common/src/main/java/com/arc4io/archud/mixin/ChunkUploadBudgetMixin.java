package com.arc4io.archud.mixin;

import com.arc4io.archud.client.perf.AdaptiveDistance;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Дальность прорисовки: загрузка готовых чанков в видеопамять не больше ~3 мс за кадр (остальное — в следующих кадрах).
 * Без этого при большой дальности и быстром движении десятки чанков загружаются в один кадр — рывки.
 * Ограничение действует только при покадровой загрузке из LevelRenderer.compileChunks.
 */
@Mixin(ChunkRenderDispatcher.class)
public abstract class ChunkUploadBudgetMixin {
    @Inject(method = "uploadAllPendingUploads", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$budget(CallbackInfo ci) {
        if (!AdaptiveDistance.budgetUploads()) return;
        java.util.Queue<Runnable> q = ((ChunkRenderDispatcherAccessor) this).archud$toUpload();
        long end = System.nanoTime() + 3_000_000L;
        int n = 0;
        Runnable r;
        while ((n < 4 || System.nanoTime() < end) && (r = q.poll()) != null) {
            r.run();
            n++;
        }
        ci.cancel();
    }
}
