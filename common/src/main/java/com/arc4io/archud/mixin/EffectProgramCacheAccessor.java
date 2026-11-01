package com.arc4io.archud.mixin;

import net.minecraft.client.renderer.EffectInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Сброс «текущей программы» эффектов постобработки после своих GL-программ (шейдерпаки Arc). */
@Mixin(EffectInstance.class)
public interface EffectProgramCacheAccessor {
    @Accessor("lastProgramId")
    static void archud$setEffectLastProgram(int id) {
        throw new AssertionError();
    }

    @Accessor("lastAppliedEffect")
    static void archud$setEffectLastApplied(EffectInstance e) {
        throw new AssertionError();
    }
}
