package com.arc4io.archud.client.screen;

import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Theme;
import com.arc4io.archud.config.ArcConfig;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Новый вид строки чата: анимация открытия (выезд снизу), неоновая рамка, вкладка «Чат»,
 * счётчик символов и подсказка для команд. Сам ChatScreen не подменяется —
 * поэтому всё работает вместе с модами на чат.
 */
public final class ChatStyler {
    private static long openTime;
    private static boolean pushed;

    private ChatStyler() {}

    public static void onInit(Screen screen) {
        com.arc4io.archud.compat.TempadCompat.onScreenInit(screen);
        if (screen instanceof ChatScreen) openTime = Util.getMillis();
    }

    private static float progress() {
        if (!ArcConfig.get().chatAnimation) return 1F;
        return Draw.easeOut((Util.getMillis() - openTime) / 220F);
    }

    public static void preRender(Screen screen, GuiGraphics g) {
        pushed = false;
        if (!(screen instanceof ChatScreen) || !ArcConfig.get().chatStyle) return;
        int w = screen.width, h = screen.height;
        int accent = Theme.accent();
        float p = progress();
        g.pose().pushPose();
        pushed = true;
        g.pose().translate(0, (1F - p) * 18F, 0);

        int a = (int) (255 * p);
        // свечение над строкой
        g.fillGradient(2, h - 26, w - 2, h - 15, Draw.alpha(accent, 0), Draw.alpha(accent, (int) (0x38 * p)));
        // рамка вокруг поля ввода
        g.fill(1, h - 15, w - 1, h - 14, Draw.alpha(accent, (int) (0xC0 * p)));
        g.fill(1, h - 2, w - 1, h - 1, Draw.alpha(accent, (int) (0x70 * p)));
        g.fill(1, h - 14, 2, h - 2, Draw.alpha(accent, (int) (0x70 * p)));
        g.fill(w - 2, h - 14, w - 1, h - 2, Draw.alpha(accent, (int) (0x70 * p)));
        // вкладка
        Minecraft mc = Minecraft.getInstance();
        Component tab = Component.translatable("archud.chat.label");
        int tw = mc.font.width(tab) + 12;
        Draw.panel(g, 2, h - 27, tw, 12, Draw.alpha(0x101419, (int) (0xD0 * p)), Draw.alpha(accent, a));
        g.drawString(mc.font, tab, 8, h - 25, Draw.alpha(0xEAF2FA, Math.max(8, a)), true);
    }

    public static void postRender(Screen screen, GuiGraphics g) {
        if (!pushed) return;
        pushed = false;
        int w = screen.width, h = screen.height;
        int accent = Theme.accent();
        Minecraft mc = Minecraft.getInstance();
        g.fill(2, h - 14, 3, h - 2, Draw.alpha(accent, 0xFF));

        EditBox input = findInput(screen);
        if (input != null) {
            String value = input.getValue();
            int len = value.length();
            String counter = len + "/256";
            int color = len > 230 ? 0xFFFF8A80 : 0xFF8C9AAB;
            int cw = Math.round(mc.font.width(counter) * 0.75F) + 10;
            Draw.panel(g, w - 2 - cw, h - 27, cw, 12, 0xD0101419, Draw.alpha(accent, 0x90));
            Draw.small(g, mc.font, counter, w - cw + 3, h - 24, color, 0.75F);
            if (value.startsWith("/")) {
                Component hint = Component.translatable("archud.chat.command");
                int hw = Math.round(mc.font.width(hint) * 0.75F) + 10;
                int hx = w - 6 - cw - hw;
                Draw.panel(g, hx, h - 27, hw, 12, 0xD0101419, 0xFFFFD54F);
                Draw.small(g, mc.font, hint.getString(), hx + 5, h - 24, 0xFFFFD54F, 0.75F);
            }
        }
        g.pose().popPose();
    }

    private static EditBox findInput(Screen screen) {
        for (GuiEventListener l : screen.children()) {
            if (l instanceof EditBox box) return box;
        }
        return null;
    }
}
