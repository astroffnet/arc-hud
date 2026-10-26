package com.arc4io.archud.mixin;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

@Mixin(GuiGraphics.class)
public interface GuiGraphicsInvoker {
    @Invoker("renderTooltipInternal")
    void archud$renderTooltipInternal(Font font, List<ClientTooltipComponent> lines, int x, int y, ClientTooltipPositioner positioner);
}
