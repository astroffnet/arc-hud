package com.arc4io.archud.mixin;

import com.google.common.collect.ImmutableList;
import net.minecraft.client.gui.components.TabButton;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TabNavigationBar.class)
public interface TabNavigationBarAccessor {
    @Accessor("width")
    int archud$width();

    @Accessor("tabButtons")
    ImmutableList<TabButton> archud$tabButtons();
}
