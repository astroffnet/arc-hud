package com.arc4io.archud.client.map;

import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Theme;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;

/**
 * Метки, которыми поделились другие игроки, — сначала предложение: уведомление с кнопками «Принять» / «Отклонить»
 * (вверху экрана — нажимается, когда открыт чат, — и такие же кнопки в самом чате). Принятая метка сохраняется
 * в свои метки, отклонённая просто исчезает.
 */
public final class ShareInbox {
    public record Offer(int id, Waypoint wp, long at) {}

    private static final List<Offer> PENDING = new ArrayList<>();
    private static int nextId = 1;
    /** Кнопки последней отрисовки: {x, y, w, h} для «Принять» и «Отклонить». */
    private static int[] acceptBox, declineBox;

    private ShareInbox() {}

    public static List<Offer> pending() {
        return PENDING;
    }

    public static void clear() {
        PENDING.clear();
    }

    /** Пришла метка от другого игрока — спросить. */
    public static void offer(Waypoint w) {
        PENDING.removeIf(o -> o.wp().x == w.x && o.wp().y == w.y && o.wp().z == w.z && o.wp().dimension.equals(w.dimension));
        Offer o = new Offer(nextId++, w, System.currentTimeMillis());
        PENDING.add(o);
        while (PENDING.size() > 10) PENDING.remove(0);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.5F, 1.4F);
        MutableComponent msg = Component.translatable("archud.share.offer_chat", w.sharedBy == null ? "?" : w.sharedBy, w.name, w.x, w.y, w.z)
                .withStyle(ChatFormatting.AQUA);
        msg.append(" ").append(button("archud.share.accept", "/archud_share accept " + o.id(), ChatFormatting.GREEN));
        msg.append(" ").append(button("archud.share.decline", "/archud_share decline " + o.id(), ChatFormatting.RED));
        mc.gui.getChat().addMessage(msg);
    }

    private static Component button(String key, String cmd, ChatFormatting color) {
        return Component.literal("[").append(Component.translatable(key)).append("]").withStyle(s -> s.withColor(color).withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable(key))));
    }

    private static Offer find(int id) {
        for (Offer o : PENDING) if (o.id() == id) return o;
        return null;
    }

    public static void accept(int id) {
        Offer o = find(id);
        if (o == null) return;
        PENDING.remove(o);
        Waypoint w = o.wp();
        w.source = Waypoint.Source.SHARED;
        WaypointManager.add(w); // в свои метки (сохраняется)
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatable("archud.share.accepted", w.name).withStyle(ChatFormatting.GREEN), true);
        }
    }

    public static void decline(int id) {
        Offer o = find(id);
        if (o == null) return;
        PENDING.remove(o);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatable("archud.share.declined", o.wp().name).withStyle(ChatFormatting.GRAY), true);
        }
    }

    // ------------------------------------------------------------------ уведомление вверху экрана
    public static void render(GuiGraphics g) {
        acceptBox = declineBox = null;
        if (PENDING.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        Offer o = PENDING.get(0);
        Waypoint w = o.wp();
        int accent = Theme.accent();
        String head = Component.translatable("archud.share.offer_title", w.sharedBy == null ? "?" : w.sharedBy).getString();
        String body = "◆ " + w.name + "  (" + w.x + ", " + w.y + ", " + w.z + ")";
        String dimName = w.dimension.substring(w.dimension.indexOf(':') + 1);
        int pw = Math.max(200, Math.max(font.width(head), font.width(body)) + 16);
        int x = (g.guiWidth() - pw) / 2, y = 34, ph = 58;
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        Draw.panel(g, x, y, pw, ph, 0xE6101419, Draw.alpha(accent, 0xFF));
        g.drawString(font, head, x + 8, y + 6, Draw.alpha(accent, 0xFF), true);
        g.drawString(font, body, x + 8, y + 18, Draw.TEXT, true);
        Draw.small(g, font, dimName + (PENDING.size() > 1 ? "   ·   " + Component.translatable("archud.share.more", PENDING.size() - 1).getString() : ""),
                x + 8, y + 29, Draw.TEXT_DIM, 0.65F);
        int bw = (pw - 24) / 2, by = y + ph - 18;
        boolean chat = mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen;
        double mx = mc.mouseHandler.xpos() * g.guiWidth() / mc.getWindow().getScreenWidth();
        double my = mc.mouseHandler.ypos() * g.guiHeight() / mc.getWindow().getScreenHeight();
        acceptBox = new int[]{x + 8, by, bw, 14};
        declineBox = new int[]{x + 16 + bw, by, bw, 14};
        drawButton(g, font, acceptBox, Component.translatable("archud.share.accept").getString(), 0xFF2E7D32, chat && inside(acceptBox, mx, my));
        drawButton(g, font, declineBox, Component.translatable("archud.share.decline").getString(), 0xFF8E2B2B, chat && inside(declineBox, mx, my));
        if (!chat) {
            String hint = Component.translatable("archud.share.open_chat", mc.options.keyChat.getTranslatedKeyMessage()).getString();
            Draw.smallCentered(g, font, hint, x + pw / 2F, y + ph + 2, Draw.TEXT_DIM, 0.65F);
        }
        g.pose().popPose();
    }

    private static void drawButton(GuiGraphics g, Font font, int[] b, String label, int color, boolean hover) {
        g.fill(b[0], b[1], b[0] + b[2], b[1] + b[3], hover ? Draw.brighten(color, 0.25F) : color);
        Draw.outline(g, b[0], b[1], b[2], b[3], 0x60FFFFFF);
        g.drawCenteredString(font, label, b[0] + b[2] / 2, b[1] + 3, 0xFFFFFFFF);
    }

    private static boolean inside(int[] b, double mx, double my) {
        return b != null && mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3];
    }

    /** Клик (когда открыт чат): true — нажата кнопка уведомления. */
    public static boolean click(double mx, double my) {
        if (PENDING.isEmpty()) return false;
        int id = PENDING.get(0).id();
        if (inside(acceptBox, mx, my)) {
            accept(id);
            return true;
        }
        if (inside(declineBox, mx, my)) {
            decline(id);
            return true;
        }
        return false;
    }
}
