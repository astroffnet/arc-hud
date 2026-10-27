package com.arc4io.archud.mixin;

import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(KeyMapping.class)
public interface KeyMappingClickAccessor {
    @Accessor("clickCount")
    int archud$clicks();

    @Accessor("clickCount")
    void archud$setClicks(int v);
}
