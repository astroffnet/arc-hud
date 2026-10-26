package com.arc4io.archud.mixin;

import com.arc4io.archud.client.perf.Culler;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Сундуки, таблички, баннеры и т.п. за стенами не рисуются (кроме тех, что видны издалека — маяки и др.). */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityCullingMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
    private <E extends BlockEntity> void archud$cull(E blockEntity, float partialTick, PoseStack pose, MultiBufferSource buffers, CallbackInfo ci) {
        if (com.arc4io.archud.client.perf.PerfHooks.skipBlockEntity(blockEntity)) {
            ci.cancel();
            return;
        }
        BlockEntityRenderer<E> renderer = ((BlockEntityRenderDispatcher) (Object) this).getRenderer(blockEntity);
        if (renderer == null || renderer.shouldRenderOffScreen(blockEntity)) return;
        if (!Culler.shouldRenderBlockEntity(blockEntity)) ci.cancel();
    }
}
