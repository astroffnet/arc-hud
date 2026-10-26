package com.arc4io.archud.client.screen;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.client.ArcClient;
import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Icons;
import com.arc4io.archud.client.hud.Theme;
import com.arc4io.archud.client.map.WorldMapScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.achievement.StatsScreen;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.ShareToLanScreen;
import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.network.chat.Component;

/**
 * Новое меню паузы: без «Оставить отзыв» и «Нашли ошибку?», с картой, модами, скином и Arc HUD.
 */
public class ArcPauseScreen extends Screen implements com.arc4io.archud.client.screen.ScreenFit.Fits {
    private int px, py, pw, ph;
    /** Панель «Сейчас играют» справа от меню (как в Bedrock); 0 — экран слишком узкий. */
    private int lx, lw;
    private double listScroll;
    private static final int ROW = 24;

    public ArcPauseScreen() {
        super(Component.translatable("menu.game"));
    }

    private int modsStamp;

    @Override
    public void tick() {
        super.tick();
        if (com.arc4io.archud.client.mods.ModWatcher.stamp() != modsStamp) rebuildWidgets();
    }

    @Override
    protected void init() {
        com.arc4io.archud.client.screen.ScreenFit.ensure(this, 240, 270);
        modsStamp = com.arc4io.archud.client.mods.ModWatcher.stamp();
        pw = 220;
        int bw = pw - 20;
        int half = (bw - 6) / 2;
        int rows = 8;
        ph = 34 + rows * 24 + 6;
        lw = width >= pw + 8 + 170 + 16 ? Math.min(200, width - pw - 8 - 16) : 0;
        int total = pw + (lw > 0 ? 8 + lw : 0);
        px = (width - total) / 2;
        lx = px + pw + 8;
        py = Math.max(8, (height - ph) / 2);
        int x = px + 10, y = py + 30;

        addRenderableWidget(new ArcButton(x, y, bw, 20, Component.translatable("menu.returnToGame"), b -> {
            minecraft.setScreen(null);
            minecraft.mouseHandler.grabMouse();
        }).icon(Icons.JUMP));
        y += 24;
        addRenderableWidget(new ArcButton(x, y, half, 20, Component.translatable("gui.advancements"),
                b -> minecraft.setScreen(new AdvancementsScreen(minecraft.player.connection.getAdvancements()))).icon(Icons.STAR));
        addRenderableWidget(new ArcButton(x + half + 6, y, half, 20, Component.translatable("gui.stats"),
                b -> minecraft.setScreen(new StatsScreen(this, minecraft.player.getStats()))).icon(Icons.CALENDAR));
        y += 24;
        addRenderableWidget(new ArcButton(x, y, half, 20, Component.translatable("archud.map.title"),
                b -> minecraft.setScreen(new WorldMapScreen(this))).icon(Icons.PIN));
        Screen mods = ArcClient.platform().createModListScreen(this);
        ArcButton modsBtn = new ArcButton(x + half + 6, y, half, 20, Component.translatable("archud.menu.mods"), b -> {
            Screen s = ArcClient.platform().createModListScreen(this);
            if (s != null) minecraft.setScreen(s);
        }).icon(Icons.CHIP);
        modsBtn.active = mods != null;
        addRenderableWidget(modsBtn);
        y += 24;
        addRenderableWidget(new ArcButton(x, y, half, 20, Component.translatable("archud.wpl.title"),
                b -> minecraft.setScreen(new com.arc4io.archud.client.map.WaypointListScreen(this))).icon(Icons.PIN));
        ArcButton hide = new ArcButton(x + half + 6, y, half, 20, hideLabel(), b -> {
            com.arc4io.archud.client.ArcClientNet.setHidden(!com.arc4io.archud.config.ArcConfig.get().hideFromMap);
            b.setMessage(hideLabel());
        }).icon(Icons.GLOBE);
        hide.active = !minecraft.isLocalServer() || (minecraft.getSingleplayerServer() != null && minecraft.getSingleplayerServer().isPublished());
        hide.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("archud.hide.tooltip")));
        addRenderableWidget(hide);
        y += 24;
        addRenderableWidget(new ArcButton(x, y, half, 20, Component.translatable("menu.options"),
                b -> minecraft.setScreen(new OptionsScreen(this, minecraft.options))).icon(Icons.COMPASS));
        boolean published = minecraft.hasSingleplayerServer() && minecraft.getSingleplayerServer().isPublished();
        ArcButton lan = new ArcButton(x + half + 6, y, half, 20, Component.translatable(published ? "archud.admin.title" : "menu.shareToLan"),
                b -> minecraft.setScreen(published ? new HostPanelScreen(this) : new ShareToLanScreen(this))).icon(Icons.SIGNAL);
        lan.active = minecraft.hasSingleplayerServer();
        addRenderableWidget(lan);
        y += 24;
        // «Скин» — только если есть SkinsRestorer (мод на клиенте или /skin на сервере); иначе настройки Arc HUD на всю ширину
        boolean skins = SkinRestorerScreen.available();
        addRenderableWidget(new ArcButton(x, y, skins ? half : bw, 20, Component.translatable("archud.menu.arc_settings"),
                b -> minecraft.setScreen(new ArcConfigScreen(this))).icon(Icons.STAR));
        if (skins) {
            addRenderableWidget(new ArcButton(x + half + 6, y, half, 20, Component.translatable("archud.skin.short"),
                    b -> minecraft.setScreen(new SkinRestorerScreen(this))).icon(Icons.HEART));
        }
        y += 30;
        Component quit = minecraft.isLocalServer() ? Component.translatable("menu.returnToMenu") : Component.translatable("menu.disconnect");
        addRenderableWidget(new ArcButton(x, y, bw, 20, quit, b -> {
            b.active = false;
            disconnect();
        }).icon(Icons.WARNING).danger());
        // моды в папке поменялись — «Перезапустить» под панелью (или над ней, если снизу нет места)
        int ry = py + ph + 12 + 18 <= height - 2 ? py + ph + 12 : Math.max(2, py - 22);
        com.arc4io.archud.client.mods.ModWatcher.addButton(this::addRenderableWidget, px, ry, pw);
        // админ-панель сервера — операторам, вошедшим в аккаунт (сервер с Arc HUD или плагином)
        if (com.arc4io.archud.client.ArcClientNet.adminAllowed()) {
            int ax = lw > 0 ? lx + 8 : px + 10, aw = lw > 0 ? lw - 16 : pw - 20, ay = lw > 0 ? py + ph - 26 : py + ph + 34;
            addRenderableWidget(new ArcButton(ax, ay, aw, 18, Component.translatable("archud.adminp.open"),
                    b -> minecraft.setScreen(new AdminPanelScreen(this))).icon(Icons.STAR));
        }
    }

    // ------------------------------------------------------------------ «Сейчас играют»
    private java.util.List<net.minecraft.client.multiplayer.PlayerInfo> online() {
        java.util.List<net.minecraft.client.multiplayer.PlayerInfo> out = new java.util.ArrayList<>();
        if (minecraft.getConnection() != null) out.addAll(minecraft.getConnection().getOnlinePlayers());
        java.util.UUID me = minecraft.player == null ? null : minecraft.player.getUUID();
        out.sort(java.util.Comparator.comparingInt((net.minecraft.client.multiplayer.PlayerInfo i) -> i.getProfile().getId().equals(me) ? 0 : 1)
                .thenComparing(i -> i.getProfile().getName(), String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private int listTop() {
        return py + 32;
    }

    private int listBottom() {
        return py + ph - 8 - (com.arc4io.archud.client.ArcClientNet.adminAllowed() ? 22 : 0);
    }

    private void renderPlayers(GuiGraphics g, int mouseX, int mouseY, int accent) {
        java.util.List<net.minecraft.client.multiplayer.PlayerInfo> list = online();
        Draw.accentPanel(g, lx, py, lw, ph, accent);
        g.drawString(font, Component.translatable("archud.pause.players"), lx + 8, py + 8, Draw.TEXT, true);
        String count = String.valueOf(list.size());
        Draw.panel(g, lx + lw - 10 - font.width(count) - 8, py + 6, font.width(count) + 8, 12, Draw.alpha(accent, 0x50), Draw.alpha(accent, 0xFF));
        g.drawString(font, count, lx + lw - 10 - font.width(count) - 4, py + 8, 0xFFFFFFFF, true);
        String sub = minecraft.getCurrentServer() != null && !minecraft.isLocalServer() ? minecraft.getCurrentServer().ip
                : minecraft.getSingleplayerServer() != null && minecraft.getSingleplayerServer().isPublished()
                ? Component.translatable("archud.pause.lan_open").getString() : Component.translatable("archud.pause.solo").getString();
        Draw.small(g, font, sub, lx + 8, py + 19, Draw.TEXT_DIM, 0.62F);
        int top = listTop(), bottom = listBottom();
        if (minecraft.getSingleplayerServer() != null && !minecraft.getSingleplayerServer().isPublished() && !com.arc4io.archud.client.ArcClientNet.adminAllowed()) bottom -= 26;
        listScroll = net.minecraft.util.Mth.clamp(listScroll, 0, Math.max(0, list.size() * ROW - (bottom - top)));
        g.enableScissor(lx + 2, top, lx + lw - 2, bottom);
        java.util.UUID me = minecraft.player == null ? null : minecraft.player.getUUID();
        java.util.UUID host = minecraft.getSingleplayerServer() != null && minecraft.getSingleplayerServer().getSingleplayerProfile() != null
                ? minecraft.getSingleplayerServer().getSingleplayerProfile().getId() : null;
        int y = top - (int) listScroll;
        for (net.minecraft.client.multiplayer.PlayerInfo info : list) {
            java.util.UUID id = info.getProfile().getId();
            boolean self = id.equals(me);
            boolean hov = mouseX >= lx && mouseX < lx + lw && mouseY >= y && mouseY < y + ROW - 2 && mouseY >= top && mouseY < bottom;
            g.fill(lx + 5, y, lx + lw - 5, y + ROW - 2, self ? Draw.alpha(accent, 0x30) : hov ? 0x26FFFFFF : 0x0CFFFFFF);
            if (self) g.fill(lx + 5, y, lx + 7, y + ROW - 2, Draw.alpha(accent, 0xFF));
            net.minecraft.client.gui.components.PlayerFaceRenderer.draw(g, info.getSkinLocation(), lx + 11, y + 3, 16);
            boolean spectator = info.getGameMode() == net.minecraft.world.level.GameType.SPECTATOR;
            if (spectator) g.fill(lx + 11, y + 3, lx + 27, y + 19, 0x80000000);
            if (com.arc4io.archud.compat.VoiceCompat.isTalking(id)) com.arc4io.archud.client.map.MapView.talkRing(g, lx + 19, y + 11);
            String name = info.getProfile().getName();
            int room = lw - 40 - 22 - (hov && !self ? 20 : 0);
            if (font.width(name) > room) name = font.plainSubstrByWidth(name, room - font.width("…")) + "…";
            g.drawString(font, name, lx + 32, y + 3, self ? Draw.alpha(accent, 0xFF) : spectator ? Draw.TEXT_DIM : Draw.TEXT, true);
            String tag = id.equals(host) ? "★ " + Component.translatable("archud.pause.host").getString()
                    : self ? Component.translatable("archud.map.players.you").getString()
                    : Component.translatable("gameMode." + info.getGameMode().getName()).getString();
            Draw.small(g, font, tag, lx + 32, y + 13, id.equals(host) ? 0xFFFFD54F : Draw.TEXT_DIM, 0.6F);
            pingBars(g, lx + lw - 22, y + 6, info.getLatency());
            if (hov && !self) {
                int bx = lx + lw - 42;
                boolean bh = mouseX >= bx && mouseX < bx + 16 && mouseY >= y + 4 && mouseY < y + 18;
                Draw.panel(g, bx, y + 4, 16, 14, bh ? Draw.alpha(accent, 0x70) : Draw.PANEL, Draw.alpha(accent, bh ? 0xFF : 0x70));
                g.drawCenteredString(font, "◎", bx + 8, y + 7, 0xFFFFFFFF);
            }
            y += ROW;
        }
        g.disableScissor();
        if (minecraft.getSingleplayerServer() != null && !minecraft.getSingleplayerServer().isPublished() && !com.arc4io.archud.client.ArcClientNet.adminAllowed()) {
            Draw.wrapped(g, font, Component.translatable("archud.pause.invite_hint").getString(), lx + 8, bottom + 4, lw - 16, Draw.TEXT_DIM, 0.6F);
        }
    }

    /** Пинг полосками: зелёный — хороший, жёлтый — средний, красный — плохой. */
    private static void pingBars(GuiGraphics g, int x, int y, int ms) {
        int bars = ms < 0 ? 0 : ms < 150 ? 5 : ms < 300 ? 4 : ms < 600 ? 3 : ms < 1000 ? 2 : 1;
        int color = bars >= 4 ? 0xFF9CFF5A : bars >= 3 ? 0xFFFFD54F : 0xFFFF6E6E;
        for (int i = 0; i < 5; i++) {
            int h = 2 + i * 2;
            g.fill(x + i * 3, y + 10 - h, x + i * 3 + 2, y + 10, i < bars ? color : 0x40FFFFFF);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (lw > 0 && mx >= lx && mx < lx + lw && my >= listTop() && my < listBottom()) {
            java.util.List<net.minecraft.client.multiplayer.PlayerInfo> list = online();
            int idx = (int) ((my - listTop() + listScroll) / ROW);
            if (idx < 0 || idx >= list.size()) return true;
            net.minecraft.client.multiplayer.PlayerInfo info = list.get(idx);
            if (minecraft.player != null && info.getProfile().getId().equals(minecraft.player.getUUID())) return true;
            // клик по строке — показать игрока на карте
            minecraft.setScreen(new WorldMapScreen(this).focusOn(info.getProfile().getId()));
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (lw > 0 && mx >= lx && mx < lx + lw) {
            listScroll -= delta * ROW;
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    private static Component hideLabel() {
        return Component.translatable(com.arc4io.archud.config.ArcConfig.get().hideFromMap ? "archud.hide.on" : "archud.hide.off");
    }

    private void disconnect() {
        boolean local = minecraft.isLocalServer();
        minecraft.level.disconnect();
        if (local) minecraft.clearLevel(new GenericDirtMessageScreen(Component.translatable("menu.savingLevel")));
        else minecraft.clearLevel();
        TitleScreen title = new TitleScreen();
        if (local) minecraft.setScreen(title);
        else minecraft.setScreen(new JoinMultiplayerScreen(title));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        int accent = Theme.accent();
        Draw.accentPanel(g, px, py, pw, ph, accent);
        g.drawCenteredString(font, title, px + pw / 2, py + 8, Draw.TEXT);
        String world = minecraft.isLocalServer() && minecraft.getSingleplayerServer() != null
                ? minecraft.getSingleplayerServer().getWorldData().getLevelName()
                : minecraft.getCurrentServer() != null ? minecraft.getCurrentServer().name : "";
        Draw.smallCentered(g, font, world, px + pw / 2F, py + 19, Draw.TEXT_DIM, 0.75F);
        Draw.small(g, font, ArcHud.NAME, px + 6, py + ph + 3, Draw.alpha(accent, 0xA0), 0.6F);
        if (lw > 0) renderPlayers(g, mouseX, mouseY, accent);
        super.render(g, mouseX, mouseY, partialTick);
    }
}
