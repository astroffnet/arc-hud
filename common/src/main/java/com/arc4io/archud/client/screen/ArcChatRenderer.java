package com.arc4io.archud.client.screen;

import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Theme;
import com.arc4io.archud.config.ArcConfig;
import com.arc4io.archud.mixin.ChatComponentAccessor;
import net.minecraft.Util;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.ChatVisiblity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Новый вид сообщений чата:
 *  - каждое сообщение — отдельная «карточка» по ширине текста с цветной полосой слева
 *    (игроки — цвет темы, системные — серый, подписи сервера — цвет их метки);
 *  - новые сообщения выезжают слева и подсвечиваются;
 *  - в открытом чате — единая стеклянная панель с заголовком, подсветкой строки под курсором,
 *    своей полосой прокрутки и плашкой «новые сообщения».
 * Расположение строк совпадает с ванильным, поэтому клики по ссылкам и подсказки работают как прежде.
 */
public final class ArcChatRenderer {
    private static final Map<GuiMessage.Line, Long> SEEN = new WeakHashMap<>();

    private record Row(int index, GuiMessage.Line line, int alpha) {}

    private ArcChatRenderer() {}

    public static boolean enabled() {
        return ArcConfig.get().chatMessagesStyle;
    }

    private static double timeFactor(int age) {
        double d = age / 200.0;
        d = 1.0 - d;
        d *= 10.0;
        d = Mth.clamp(d, 0.0, 1.0);
        return d * d;
    }

    private static String plain(FormattedCharSequence seq) {
        StringBuilder sb = new StringBuilder();
        seq.accept((index, style, codePoint) -> {
            sb.appendCodePoint(codePoint);
            return true;
        });
        return sb.toString();
    }

    public static void render(ChatComponent chat, GuiGraphics g, int tickCount, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.chatVisibility().get() == ChatVisiblity.HIDDEN) return;
        ChatComponentAccessor acc = (ChatComponentAccessor) chat;
        List<GuiMessage.Line> lines = acc.archud$getTrimmedMessages();
        if (lines.isEmpty()) return;

        boolean focused = mc.screen instanceof ChatScreen;
        Font font = mc.font;
        float scale = (float) chat.getScale();
        int width = Mth.ceil(chat.getWidth() / scale);
        int perPage = chat.getLinesPerPage();
        int scrollPos = acc.archud$getScrollPos();
        double opacity = mc.options.chatOpacity().get() * 0.9 + 0.1;
        double spacing = mc.options.chatLineSpacing().get();
        int lh = (int) (9.0 * (spacing + 1.0));
        int textOff = (int) Math.round(-8.0 * (spacing + 1.0) + 4.0 * spacing);
        int base = Mth.floor((g.guiHeight() - 40 - com.arc4io.archud.client.hud.HudRenderer.chatLift()) / scale);
        long now = Util.getMillis();
        int accent = Theme.accent();
        int gut = ChatHeads.gutter();

        List<Row> rows = new ArrayList<>();
        for (int j = 0; j < perPage && j + scrollPos < lines.size(); j++) {
            GuiMessage.Line line = lines.get(j + scrollPos);
            if (line == null) continue;
            int age = tickCount - line.addedTime();
            if (age >= 200 && !focused) continue;
            double fade = focused ? 1.0 : timeFactor(age);
            int a = (int) (255.0 * fade * opacity);
            if (a > 3) rows.add(new Row(j, line, a));
        }
        if (rows.isEmpty() && !focused) return;

        g.pose().pushPose();
        g.pose().scale(scale, scale, 1F);
        g.pose().translate(4F, 0F, 50F);

        if (focused) {
            renderFocusedPanel(g, font, acc, lines.size(), perPage, scrollPos, base, lh, width, accent, mouseX, mouseY, scale);
        }

        // группируем строки в сообщения (нижняя строка сообщения имеет endOfEntry = true)
        List<List<Row>> groups = new ArrayList<>();
        for (Row r : rows) {
            if (groups.isEmpty() || r.line().endOfEntry()) groups.add(new ArrayList<>());
            groups.get(groups.size() - 1).add(r);
        }

        for (List<Row> group : groups) {
            Row bottom = group.get(0);
            Row topRow = group.get(group.size() - 1);
            int alpha = 0;
            int textW = 0;
            for (Row r : group) {
                alpha = Math.max(alpha, r.alpha());
                textW = Math.max(textW, font.width(r.line().content()));
            }
            long seen = SEEN.computeIfAbsent(bottom.line(), k -> now);
            float appear = Draw.easeOut((now - seen) / 260F);
            int off = Math.round((1F - appear) * -36F);
            int a = (int) (alpha * Math.min(1F, 0.25F + appear));

            int y2 = base - bottom.index() * lh;
            int y1 = base - topRow.index() * lh - lh;
            int x1 = -4 + off;
            int x2 = (focused ? width + 4 : textW + gut + 6) + off;

            String firstText = plain(topRow.line().content());
            GuiMessageTag tag = bottom.line().tag();
            ChatHeads.Sender sender = gut > 0 ? ChatHeads.sender(topRow.line().content()) : null;
            int typeColor = tag != null ? 0xFF000000 | tag.indicatorColor()
                    : sender != null && sender.name() != null ? 0xFF000000 | ChatHeads.nameColor(sender.name())
                    : sender != null || firstText.startsWith("<") ? accent
                    : 0xFF90A4AE;

            if (!focused) {
                int bgA = (int) (0xC8 * (a / 255F));
                g.fill(x1 + 1, y1, x2, y2 - 1, Draw.alpha(0x0D1117, bgA));
                g.fill(x1 + 2, y1, x2 - 1, y1 + 1, Draw.alpha(typeColor, (int) (0x40 * (a / 255F))));
                g.fill(x2, y1 + 1, x2 + 1, y2 - 2, Draw.alpha(0x0D1117, bgA / 2));
            }
            // цветная полоса сообщения
            g.fill(x1, y1, x1 + 2, y2 - 1, Draw.alpha(typeColor, a));
            // вспышка у нового сообщения
            float glow = 1F - Mth.clamp((now - seen) / 900F, 0F, 1F);
            if (glow > 0F) Draw.outline(g, x1, y1, x2 - x1 + 1, y2 - y1, Draw.alpha(typeColor, (int) (0xB0 * glow * (a / 255F))));

            if (gut > 0) {
                int hy = base - topRow.index() * lh + textOff;
                if (sender != null) {
                    // голова игрока с тонкой рамкой
                    g.fill(2 + off, hy - 1, 12 + off, hy + 9, Draw.alpha(0x000000, (int) (0x90 * (a / 255F))));
                    ChatHeads.draw(g, sender, 3 + off, hy, a);
                } else {
                    // системное сообщение — маленький ромб цвета сообщения
                    int cx = 7 + off, cy = hy + 4;
                    int c = Draw.alpha(typeColor, (int) (0xC0 * (a / 255F)));
                    g.fill(cx - 1, cy - 2, cx + 1, cy + 2, c);
                    g.fill(cx - 2, cy - 1, cx + 2, cy + 1, c);
                }
            }
            for (Row r : group) {
                int ly = base - r.index() * lh;
                g.drawString(font, r.line().content(), 2 + gut + off, ly + textOff, Draw.alpha(0xFFFFFF, Math.max(4, a)));
            }
        }
        g.pose().popPose();
    }

    private static void renderFocusedPanel(GuiGraphics g, Font font, ChatComponentAccessor acc, int total, int perPage, int scrollPos,
                                           int base, int lh, int width, int accent, int mouseX, int mouseY, float scale) {
        int shown = Math.min(perPage, total);
        int top = base - shown * lh - 2;
        int bottom = base + 1;
        int x1 = -6, x2 = width + 8;
        // стеклянная панель
        g.fill(x1, top, x2, bottom, 0xB80B0F14);
        Draw.outline(g, x1 - 1, top - 12, x2 - x1 + 2, bottom - top + 13, Draw.alpha(accent, 0x70));
        // заголовок
        g.fill(x1, top - 11, x2, top, 0xE0101419);
        g.fill(x1, top - 1, x2, top, Draw.alpha(accent, 0xB0));
        String title = Component.translatable("archud.chat.label").getString().toUpperCase();
        Draw.small(g, font, title, x1 + 5, top - 8.5F, Draw.alpha(accent, 0xFF), 0.75F);
        String count = Component.translatable("archud.chat.count", acc.archud$getAllMessages().size()).getString();
        Draw.small(g, font, count, x2 - 4 - font.width(count) * 0.6F, top - 7.5F, Draw.TEXT_DIM, 0.6F);

        // подсветка строки под курсором
        double mx = mouseX / scale - 4, my = mouseY / scale;
        if (mx >= x1 && mx < x2) {
            for (int j = 0; j < shown; j++) {
                int ly2 = base - j * lh, ly1 = ly2 - lh;
                if (my >= ly1 && my < ly2) {
                    g.fill(x1 + 2, ly1, x2, ly2, 0x1CFFFFFF);
                    break;
                }
            }
        }
        // полоса прокрутки
        if (total > perPage) {
            int trackH = bottom - top;
            int thumbH = Math.max(8, trackH * perPage / total);
            int maxScroll = total - perPage;
            int thumbY = bottom - thumbH - (int) ((trackH - thumbH) * (scrollPos / (float) maxScroll));
            g.fill(x2 - 3, top, x2 - 1, bottom, 0x30FFFFFF);
            g.fill(x2 - 3, thumbY, x2 - 1, thumbY + thumbH, Draw.alpha(accent, 0xE0));
        }
        // новые сообщения ниже
        if (scrollPos > 0 && acc.archud$hasNewSinceScroll()) {
            String s = Component.translatable("archud.chat.new").getString();
            int sw = Math.round(font.width(s) * 0.75F) + 10;
            int sx = (x1 + x2) / 2 - sw / 2;
            Draw.panel(g, sx, bottom - 11, sw, 10, 0xF0101419, Draw.alpha(accent, 0xFF));
            Draw.small(g, font, s, sx + 5, bottom - 9, Draw.alpha(accent, 0xFF), 0.75F);
        }
    }
}
