package com.arc4io.archud.mixin;

import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Новый инвентарь расставляет слоты по-своему (координаты слота в ваниле — final). */
@Mixin(Slot.class)
public interface SlotPosAccessor {
    @Mutable
    @Accessor("x")
    void archud$setX(int x);

    @Mutable
    @Accessor("y")
    void archud$setY(int y);
}
