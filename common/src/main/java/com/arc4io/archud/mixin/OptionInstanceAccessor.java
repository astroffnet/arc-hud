package com.arc4io.archud.mixin;

import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.function.Function;

/** Свой экран графики: название, текст значения и подсказка настройки. */
@Mixin(OptionInstance.class)
public interface OptionInstanceAccessor {
    @Accessor("caption")
    Component archud$caption();

    @Accessor("toString")
    Function<Object, Component> archud$toString();

    @Accessor("tooltip")
    OptionInstance.TooltipSupplier<Object> archud$tooltip();
}
