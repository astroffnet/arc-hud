package com.arc4io.archud.client.resources;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Theme;
import com.arc4io.archud.config.ArcConfig;
import com.arc4io.archud.mixin.LoadingOverlayAccessor;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Перезагрузка ресурсов (смена ресурспака, F3+T, OptiFine) в стиле Arc HUD вместо красного экрана Mojang.
 * Рисуется поверх ванильного экрана с той же прозрачностью, сама загрузка остаётся ванильной — поэтому работает
 * и с OptiFine. Первая загрузка игры не трогается: шрифты и текстуры мода тогда ещё не загружены.
 */
public final class ArcLoadingCover {
    private static final ResourceLocation LOGO = ArcHud.id("textures/gui/logo.png");
    private static float smooth;

    private ArcLoadingCover() {}

    public static void draw(LoadingOverlay lo, GuiGraphics g) {
        if (!ArcConfig.get().styledLoading) return;
        LoadingOverlayAccessor a = (LoadingOverlayAccessor) lo;
        if (!a.archud$fadeIn()) return;
        Minecraft mc = Minecraft.getInstance();
        long now = Util.getMillis();
        long out = a.archud$fadeOutStart(), in = a.archud$fadeInStart();
        float alpha;
        if (out > -1L) alpha = 1F - Mth.clamp((now - out) / 1000F - 1F, 0F, 1F);
        else alpha = in > -1L ? Mth.clamp((now - in) / 500F, 0F, 1F) : 0F;
        if (alpha <= 0.01F) return;
        int w = g.guiWidth(), h = g.guiHeight();
        int accent = Theme.accent();
        int ai = Math.round(alpha * 255);
        g.pose().pushPose();
        g.pose().translate(0, 0, 500);
        g.fill(0, 0, w, h, Draw.alpha(0x0B0F14, ai));
        g.fillGradient(0, 0, w, h / 2, Draw.alpha(accent, Math.round(alpha * 0x26)), Draw.alpha(accent, 0));
        // логотип
        int logoW = Math.min(w - 40, 300), logoH = logoW / 4;
        int lx = (w - logoW) / 2, ly = h / 2 - logoH - 14;
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        g.setColor(1F, 1F, 1F, alpha);
        g.blit(LOGO, lx, ly, logoW, logoH, 0, 0, 512, 128, 512, 128);
        g.setColor(1F, 1F, 1F, 1F);
        // прогресс
        float p = Mth.clamp(a.archud$progress(), 0F, 1F);
        if (out > -1L) p = 1F;
        smooth += (p - smooth) * 0.25F;
        if (smooth > p + 0.5F) smooth = p; // новая загрузка
        int bw = Math.min(w - 60, 260), bx = (w - bw) / 2, by = h / 2 + 10;
        g.fill(bx, by, bx + bw, by + 4, Draw.alpha(0xFFFFFF, Math.round(alpha * 0x22)));
        g.fill(bx, by, bx + Math.round(bw * smooth), by + 4, Draw.alpha(accent, ai));
        // блик на краю полосы
        int ex = bx + Math.round(bw * smooth);
        g.fill(Math.max(bx, ex - 6), by, ex, by + 4, Draw.alpha(0xFFFFFF, Math.round(alpha * 0x70)));
        String label = Component.translatable("archud.loading.resources").getString();
        String pct = Math.round(smooth * 100) + "%";
        g.drawString(mc.font, label, bx, by + 9, Draw.alpha(0xCFD8DC, ai), false);
        g.drawString(mc.font, pct, bx + bw - mc.font.width(pct), by + 9, Draw.alpha(accent, ai), false);
        g.pose().popPose();
    }
}
