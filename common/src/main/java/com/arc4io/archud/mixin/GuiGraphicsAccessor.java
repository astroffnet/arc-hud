package com.arc4io.archud.mixin;

import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GuiGraphics.class)
public interface GuiGraphicsAccessor {
    @Accessor("managed")
    boolean archud$managed();

    @Accessor("managed")
    void archud$setManaged(boolean v);
}
