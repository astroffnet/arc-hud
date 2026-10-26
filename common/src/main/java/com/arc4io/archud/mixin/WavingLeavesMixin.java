package com.arc4io.archud.mixin;

import com.arc4io.archud.client.fx.Waving;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Шевеление листвы: листва в «красивой» графике рисуется в слое cutout (см. {@link Waving}). */
@Mixin(ItemBlockRenderTypes.class)
public abstract class WavingLeavesMixin {
    @Inject(method = "getChunkRenderType", at = @At("HEAD"), cancellable = true, require = 0)
    private static void archud$leaves(BlockState state, CallbackInfoReturnable<RenderType> cir) {
        if (state.getBlock() instanceof LeavesBlock && Waving.leavesInCutout()
                && net.minecraft.client.Minecraft.getInstance().options.graphicsMode().get() != net.minecraft.client.GraphicsStatus.FAST) {
            cir.setReturnValue(RenderType.cutout());
        }
    }
}
