package com.arc4io.archud.mixin;

import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Сброс «текущей программы» Minecraft после своих GL-программ (шейдерпаки Arc). */
@Mixin(ShaderInstance.class)
public interface ShaderProgramCacheAccessor {
    @Accessor("lastProgramId")
    static void archud$setShaderLastProgram(int id) {
        throw new AssertionError();
    }

    @Accessor("lastAppliedShader")
    static void archud$setShaderLastApplied(ShaderInstance s) {
        throw new AssertionError();
    }
}
