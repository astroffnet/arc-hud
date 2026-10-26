package com.arc4io.archud.mixin;

import com.mojang.datafixers.DSL;
import net.minecraft.util.datafix.DataFixers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Set;

/**
 * Ленивый DataFixer (как LazyDFU): при запуске правила обновления старых миров не компилируются заранее
 * (это до десятков секунд работы процессора в фоне), а собираются при первом обращении. На Forge это
 * уже сделано загрузчиком (disableOptimizedDFU), на Fabric — этим миксином.
 */
@Mixin(DataFixers.class)
public abstract class LazyDfuMixin {
    @ModifyVariable(method = "createFixerUpper", at = @At("HEAD"), argsOnly = true, require = 0)
    private static Set<DSL.TypeReference> archud$lazy(Set<DSL.TypeReference> types) {
        return Set.of();
    }
}
