package com.arc4io.archud.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenAccessor {
    @Accessor("leftPos")
    int archud$left();

    @Accessor("topPos")
    int archud$top();

    @Accessor("imageWidth")
    int archud$width();

    @Accessor("imageHeight")
    int archud$height();

    @Accessor("hoveredSlot")
    net.minecraft.world.inventory.Slot archud$hovered();
}
