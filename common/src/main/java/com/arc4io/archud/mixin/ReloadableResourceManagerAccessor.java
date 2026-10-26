package com.arc4io.archud.mixin;

import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Быстрая смена языка / языковых пакетов: подмена набора пакетов без полной перезагрузки ресурсов. */
@Mixin(ReloadableResourceManager.class)
public interface ReloadableResourceManagerAccessor {
    @Accessor("resources")
    CloseableResourceManager archud$resources();

    @Mutable
    @Accessor("resources")
    void archud$setResources(CloseableResourceManager resources);
}
