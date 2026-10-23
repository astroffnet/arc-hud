package com.arc4io.archud.client.hud;

import com.arc4io.archud.client.ArcClientNet;
import com.arc4io.archud.compat.VoiceCompat;
import com.arc4io.archud.config.ArcConfig;
import com.arc4io.archud.mixin.PlayerTabOverlayAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.GameType;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.List;

/**
 * Список игроков (Tab) в стиле Arc: шапка сервера, онлайн и средний пинг, карточки игроков с головой скина,
 * цветным пингом, режимом игры, очками табло, измерением и расстоянием (если игрок не скрыл себя с карты),
 * значком «говорит» (голосовой чат) и подсветкой вас самих. Колонки подстраиваются под ширину экрана.
 */
public final class ArcTabList {
    private static final int CARD_H = 22;
    private static float appear;
    private static long lastFrame;

    private ArcTabList() {}

    public static boolean enabled() {
        return ArcConfig.get().customTabList;
    }

    public static void render(PlayerTabOverlay overlay, GuiGraphics g, int width, Scoreboard scoreboard, Objective objective) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        PlayerTabOverlayAccessor acc = (PlayerTabOverlayAccessor) overlay;
        List<PlayerInfo> infos = acc.archud$playerInfos();
        int height = g.guiHeight();
        int accent = Theme.accent();
        long now = System.currentTimeMillis();
        if (now - lastFrame > 200) appear = 0F;
        lastFrame = now;
        appear = Math.min(1F, appear + 0.12F);
        float ease = Draw.easeOut(appear);

        // колонки
        int n = infos.size();
        int maxRows = Math.max(4, (height - 110) / CARD_H);
        int cols = Math.max(1, (n + maxRows - 1) / maxRows);
        int rows = n == 0 ? 0 : (n + cols - 1) / cols;
        boolean hasScore = objective != null;
        int cardW = Math.min(200, Math.max(120, (width - 40) / cols - 4));
        if (cols * (cardW + 4) > width - 20) cardW = Math.max(90, (width - 20) / cols - 4);
        int panelW = Math.max(220, cols * (cardW + 4) + 12);
        panelW = Math.min(width - 12, panelW);

        // шапка / подвал
        Component header = acc.archud$header(), footer = acc.archud$footer();
        List<FormattedCharSequence> headLines = header == null ? List.of() : font.split(header, panelW - 20);
        List<FormattedCharSequence> footLines = footer == null ? List.of() : font.split(footer, panelW - 20);
        int top = 10 + Math.round((1F - ease) * -12);
        int headH = 20 + headLines.size() * 10;
        int listH = rows * CARD_H;
        int footH = footLines.isEmpty() ? 0 : footLines.size() * 10 + 8;
        int panelH = headH + listH + footH + 10;
        int px = (width - panelW) / 2;

        g.pose().pushPose();
        g.pose().translate(0, 0, 200);
        Draw.panel(g, px, top, panelW, panelH, Draw.alpha(0xF00A0E13, (int) (0xF0 * ease)), Draw.alpha(accent, (int) (0x90 * ease)));
        g.fill(px + 1, top + 1, px + panelW - 1, top + 2, Draw.alpha(accent, (int) (0xFF * ease)));

        int y = top + 6;
        for (FormattedCharSequence l : headLines) {
            g.drawString(font, l, px + (panelW - font.width(l)) / 2, y, 0xFFFFFFFF, true);
            y += 10;
        }
        // строка статуса: сервер, онлайн, средний пинг
        String server = mc.getCurrentServer() != null ? mc.getCurrentServer().name
                : mc.hasSingleplayerServer() ? Component.translatable("menu.singleplayer").getString() : "";
        long ping = 0;
        for (PlayerInfo p : infos) ping += p.getLatency();
        ping = n == 0 ? 0 : ping / n;
        String status = Component.translatable("archud.tab.status", n, ping).getString();
        Draw.small(g, font, server, px + 8, y + 2, Draw.alpha(accent, 0xFF), 0.75F);
        Draw.small(g, font, status, px + panelW - 8 - font.width(status) * 0.75F, y + 2, Draw.TEXT_DIM, 0.75F);
        y += 12;
        g.fill(px + 6, y, px + panelW - 6, y + 1, Draw.alpha(accent, 0x40));
        y += 4;

        // карточки
        int gridW = cols * (cardW + 4) - 4;
        int gx = px + (panelW - gridW) / 2;
        String myDim = mc.level == null ? "" : mc.level.dimension().location().toString();
        for (int i = 0; i < n; i++) {
            PlayerInfo info = infos.get(i);
            int col = i / rows, row = i % rows;
            int cx = gx + col * (cardW + 4), cy = y + row * CARD_H;
            float a = Math.min(1F, Math.max(0F, ease * 1.6F - row * 0.04F));
            drawCard(g, font, overlay, info, cx, cy, cardW, scoreboard, objective, hasScore, myDim, accent, a);
        }
        y += listH + 4;
        if (!footLines.isEmpty()) {
            g.fill(px + 6, y, px + panelW - 6, y + 1, Draw.alpha(accent, 0x30));
            y += 5;
            for (FormattedCharSequence l : footLines) {
                g.drawString(font, l, px + (panelW - font.width(l)) / 2, y, 0xFFC8D2DC, true);
                y += 10;
            }
        }
        g.pose().popPose();
    }

    private static void drawCard(GuiGraphics g, Font font, PlayerTabOverlay overlay, PlayerInfo info, int x, int y, int w,
                                 Scoreboard scoreboard, Objective objective, boolean hasScore, String myDim, int accent, float a) {
        Minecraft mc = Minecraft.getInstance();
        int alpha = (int) (0xFF * a);
        if (alpha < 8) return;
        boolean me = mc.player != null && info.getProfile().getId().equals(mc.player.getUUID());
        boolean spectator = info.getGameMode() == GameType.SPECTATOR;
        g.fill(x, y, x + w, y + CARD_H - 2, me ? Draw.alpha(accent, (int) (0x30 * a)) : Draw.alpha(0xFFFFFFFF, (int) (0x0C * a)));
        if (me) g.fill(x, y, x + 2, y + CARD_H - 2, Draw.alpha(accent, alpha));
        // голова скина
        PlayerFaceRenderer.draw(g, info.getSkinLocation(), x + 4, y + 2, 16);
        if (spectator) g.fill(x + 4, y + 2, x + 20, y + 18, 0x90000000);
        if (VoiceCompat.isTalking(info.getProfile().getId())) {
            Draw.outline(g, x + 3, y + 1, 18, 18, Draw.alpha(0xFF66FF99, alpha));
        }
        // имя
        Component name = overlay.getNameForDisplay(info);
        int right = x + w - 4;
        int pingW = 22;
        int scoreW = 0;
        String score = null;
        if (hasScore) {
            int v = scoreboard.getOrCreatePlayerScore(info.getProfile().getName(), objective).getScore();
            score = objective.getRenderType() == ObjectiveCriteria.RenderType.HEARTS ? "❤" + v : Integer.toString(v);
            scoreW = font.width(score) + 6;
        }
        int nameMax = w - 26 - pingW - scoreW - 4;
        String plain = name.getString();
        if (font.width(name) > nameMax) {
            g.drawString(font, font.plainSubstrByWidth(plain, nameMax - 6) + "…", x + 24, y + 3, Draw.alpha(spectator ? 0xFF8C9AAB : 0xFFFFFFFF, alpha), true);
        } else {
            g.drawString(font, name, x + 24, y + 3, Draw.alpha(spectator ? 0xFF8C9AAB : 0xFFFFFFFF, alpha), true);
        }
        // вторая строка: режим / измерение и расстояние
        String sub = Component.translatable("gameMode." + info.getGameMode().getName()).getString();
        for (ArcClientNet.RemotePlayer rp : ArcClientNet.players()) {
            if (!rp.id().equals(info.getProfile().getId())) continue;
            String dim = rp.dimension();
            sub = HudRenderer.pretty(dim.substring(dim.indexOf(':') + 1));
            if (dim.equals(myDim) && mc.player != null) {
                double d = Math.sqrt(Math.pow(rp.x() - mc.player.getX(), 2) + Math.pow(rp.z() - mc.player.getZ(), 2));
                sub += " · " + Math.round(d) + " m";
            }
            break;
        }
        if (me) sub = Component.translatable("archud.tab.you").getString();
        Draw.small(g, font, sub, x + 24, y + 13, Draw.alpha(Draw.TEXT_DIM, alpha), 0.6F);
        // очки
        if (score != null) {
            g.drawString(font, score, right - pingW - font.width(score) - 4, y + 7, Draw.alpha(0xFFFFD54F, alpha), true);
        }
        // пинг: полоски + мс
        int lat = info.getLatency();
        int bars = lat < 0 ? 0 : lat < 150 ? 5 : lat < 300 ? 4 : lat < 600 ? 3 : lat < 1000 ? 2 : 1;
        int col = bars >= 4 ? 0xFF9CFF5A : bars >= 2 ? 0xFFFFD54F : 0xFFFF6E6E;
        int bx = right - 14;
        for (int i = 0; i < 5; i++) {
            int bh = 2 + i * 2;
            g.fill(bx + i * 3, y + 11 - bh, bx + i * 3 + 2, y + 11, Draw.alpha(i < bars ? col : 0x40FFFFFF, alpha));
        }
        String ms = lat + "ms";
        Draw.small(g, font, ms, right - font.width(ms) * 0.5F, y + 13, Draw.alpha(Draw.TEXT_DIM, alpha), 0.5F);
    }
}
