package com.arc4io.archud.mixin;

import com.arc4io.archud.client.fx.Waving;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.ChunkRenderTypeSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Forge берёт слой блока из getRenderLayers — листва в слой cutout для шевеления. */
@Mixin(ItemBlockRenderTypes.class)
public abstract class WavingLeavesForgeMixin {
    @Inject(method = "getRenderLayers", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void archud$leaves(BlockState state, CallbackInfoReturnable<ChunkRenderTypeSet> cir) {
        if (state.getBlock() instanceof LeavesBlock && Waving.leavesInCutout()
                && net.minecraft.client.Minecraft.getInstance().options.graphicsMode().get() != net.minecraft.client.GraphicsStatus.FAST) {
            cir.setReturnValue(ChunkRenderTypeSet.of(RenderType.cutout()));
        }
    }
}
