package com.arc4io.archud.mixin;

import com.arc4io.archud.client.screen.ArcWidgets;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.TabButton;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Полоса вкладок: тёмная панель с акцентной линией вместо чёрной полосы и разделителя. */
@Mixin(TabNavigationBar.class)
public abstract class TabNavigationBarMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$render(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (!ArcWidgets.enabled()) return;
        TabNavigationBarAccessor acc = (TabNavigationBarAccessor) this;
        ArcWidgets.drawTabBar(g, acc.archud$width());
        for (TabButton b : acc.archud$tabButtons()) b.render(g, mouseX, mouseY, partialTick);
        ci.cancel();
    }
}
