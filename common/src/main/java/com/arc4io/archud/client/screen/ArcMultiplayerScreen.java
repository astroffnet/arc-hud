package com.arc4io.archud.client.screen;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Icons;
import com.arc4io.archud.client.hud.Theme;
import com.arc4io.archud.config.ArcConfig;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.server.LanServer;
import net.minecraft.client.server.LanServerDetection;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * «Сетевая игра» в стиле Arc: карточки серверов с иконками, MOTD, пингом и онлайном,
 * серверы в локальной сети, быстрый вход на последний сервер (запоминается), добавление,
 * изменение, удаление, прямое подключение, перестановка (Shift + ↑/↓).
 */
public class ArcMultiplayerScreen extends Screen {
    private static final int ROW = 44;
    private static final ExecutorService PING_POOL = Executors.newFixedThreadPool(5, r -> {
        Thread t = new Thread(r, "Arc HUD server ping");
        t.setDaemon(true);
        return t;
    });

    private final Screen parent;
    private ServerList servers;
    private final ServerStatusPinger pinger = new ServerStatusPinger();
    private LanServerDetection.LanServerList lanList;
    private LanServerDetection.LanServerDetector lanDetector;
    private List<LanServer> lan = new ArrayList<>();
    /** Выбранная строка: индекс в общем списке (сначала свои серверы, потом LAN). */
    private int selected = -1;
    private double scroll;
    private long lastClick;
    private final Map<String, ResourceLocation> icons = new HashMap<>();
    private final Map<String, Integer> iconHash = new HashMap<>();
    private ArcButton join, edit, delete, up, down;
    private boolean initialized;

    public ArcMultiplayerScreen(Screen parent) {
        super(Component.translatable("multiplayer.title"));
        this.parent = parent;
    }

    // ------------------------------------------------------------------ разметка
    private int listX() {
        return Math.max(12, width / 2 - 230);
    }

    private int listW() {
        return Math.min(width - 24, 460);
    }

    private int listTop() {
        return lastServer() != null ? 66 : 34;
    }

    private int listBottom() {
        return height - 64;
    }

    private int total() {
        return servers.size() + lan.size();
    }

    @Override
    protected void init() {
        if (!initialized) {
            initialized = true;
            servers = new ServerList(minecraft);
            servers.load();
            selectLast();
        } else {
            for (int i = 0; i < servers.size(); i++) if (servers.get(i).ping == -2L) servers.get(i).pinged = false;
        }
        if (lanDetector == null) {
            lanList = new LanServerDetection.LanServerList();
            try {
                lanDetector = new LanServerDetection.LanServerDetector(lanList);
                lanDetector.start();
            } catch (Exception e) {
                ArcHud.LOGGER.debug("Arc HUD: LAN detector failed", e);
            }
        }
        int lx = listX(), lw = listW();
        ServerData last = lastServer();
        if (last != null) {
            addRenderableWidget(new ArcButton(lx, 32, lw, 26,
                    Component.translatable("archud.mp.continue", last.name), b -> connect(last)).icon(Icons.JUMP));
        }
        int y1 = height - 56, y2 = height - 30;
        int third = (lw - 8) / 3;
        join = addRenderableWidget(new ArcButton(lx, y1, third, 20, Component.translatable("selectServer.select"), b -> joinSelected()).icon(Icons.JUMP));
        addRenderableWidget(new ArcButton(lx + third + 4, y1, third, 20, Component.translatable("selectServer.direct"),
                b -> minecraft.setScreen(new ArcServerEditScreen(this, null, true))).icon(Icons.SIGNAL));
        addRenderableWidget(new ArcButton(lx + (third + 4) * 2, y1, lw - (third + 4) * 2, 20, Component.translatable("selectServer.add"),
                b -> minecraft.setScreen(new ArcServerEditScreen(this, null, false))).icon(Icons.STAR));
        int q = (lw - 12 - 44) / 3;
        edit = addRenderableWidget(new ArcButton(lx, y2, q, 20, Component.translatable("selectServer.edit"), b -> editSelected()));
        delete = addRenderableWidget(new ArcButton(lx + q + 4, y2, q, 20, Component.translatable("selectServer.delete"), b -> deleteSelected()).danger());
        addRenderableWidget(new ArcButton(lx + (q + 4) * 2, y2, q, 20, Component.translatable("selectServer.refresh"), b -> refresh()));
        up = addRenderableWidget(new ArcButton(lx + (q + 4) * 3, y2, 20, 20, Component.literal("▲"), b -> move(-1)));
        down = addRenderableWidget(new ArcButton(lx + (q + 4) * 3 + 24, y2, 20, 20, Component.literal("▼"), b -> move(1)));
        addRenderableWidget(new ArcButton(width - 12 - 90, height - 30, 90, 20, CommonComponents.GUI_BACK, b -> onClose()));
        updateButtons();
    }

    // ------------------------------------------------------------------ последний сервер
    private ServerData lastServer() {
        String ip = ArcConfig.get().lastServerIp;
        if (ip == null || ip.isEmpty() || servers == null) return null;
        for (int i = 0; i < servers.size(); i++) if (servers.get(i).ip.equalsIgnoreCase(ip)) return servers.get(i);
        return new ServerData(ArcConfig.get().lastServerName == null || ArcConfig.get().lastServerName.isEmpty() ? ip
                : ArcConfig.get().lastServerName, ip, false);
    }

    private void selectLast() {
        String ip = ArcConfig.get().lastServerIp;
        if (ip == null) return;
        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i).ip.equalsIgnoreCase(ip)) {
                selected = i;
                return;
            }
        }
    }

    private static boolean isLast(ServerData d) {
        String ip = ArcConfig.get().lastServerIp;
        return ip != null && !d.isLan() && d.ip.equalsIgnoreCase(ip);
    }

    // ------------------------------------------------------------------ действия
    void connect(ServerData data) {
        ArcConfig cfg = ArcConfig.get();
        if (!data.isLan()) {
            cfg.lastServerIp = data.ip;
            cfg.lastServerName = data.name;
            ArcConfig.save();
        }
        ConnectScreen.startConnecting(this, minecraft, ServerAddress.parseString(data.ip), data, false);
    }

    private void joinSelected() {
        if (selected < 0) return;
        if (selected < servers.size()) connect(servers.get(selected));
        else {
            LanServer l = lan.get(selected - servers.size());
            connect(new ServerData(l.getMotd(), l.getAddress(), true));
        }
    }

    private void editSelected() {
        if (selected < 0 || selected >= servers.size()) return;
        minecraft.setScreen(new ArcServerEditScreen(this, servers.get(selected), false));
    }

    private void deleteSelected() {
        if (selected < 0 || selected >= servers.size()) return;
        ServerData d = servers.get(selected);
        minecraft.setScreen(new ConfirmScreen(ok -> {
            if (ok) {
                servers.remove(d);
                servers.save();
                selected = -1;
            }
            minecraft.setScreen(this);
        }, Component.translatable("selectServer.deleteQuestion"), Component.translatable("selectServer.deleteWarning", d.name),
                Component.translatable("selectServer.deleteButton"), CommonComponents.GUI_CANCEL));
    }

    /** Вызывается экраном добавления / изменения. */
    void saveServer(ServerData data, boolean isNew) {
        if (isNew) {
            if (servers.unhide(data.ip) == null) {
                servers.add(data, false);
            }
            selected = servers.size() - 1;
        }
        servers.save();
    }

    private void refresh() {
        pinger.removeAll();
        for (int i = 0; i < servers.size(); i++) servers.get(i).pinged = false;
    }

    private void move(int dir) {
        if (selected < 0 || selected >= servers.size()) return;
        int to = selected + dir;
        if (to < 0 || to >= servers.size()) return;
        servers.swap(selected, to);
        selected = to;
        servers.save();
        updateButtons();
    }

    private void updateButtons() {
        if (join == null) return;
        boolean own = selected >= 0 && selected < (servers == null ? 0 : servers.size());
        join.active = selected >= 0 && selected < total();
        edit.active = own;
        delete.active = own;
        up.active = own && selected > 0;
        down.active = own && selected < servers.size() - 1;
    }

    // ------------------------------------------------------------------ пинг
    private void ensurePinged(ServerData d) {
        if (d.pinged) return;
        d.pinged = true;
        d.ping = -2L;
        d.motd = CommonComponents.EMPTY;
        d.status = CommonComponents.EMPTY;
        PING_POOL.submit(() -> {
            try {
                pinger.pingServer(d, () -> minecraft.execute(() -> servers.saveSingleServer(d)));
            } catch (UnknownHostException e) {
                d.ping = -1L;
                d.motd = Component.translatable("multiplayer.status.cannot_resolve").withStyle(s -> s.withColor(0xFF5555));
            } catch (Exception e) {
                d.ping = -1L;
                d.motd = Component.translatable("multiplayer.status.cannot_connect").withStyle(s -> s.withColor(0xFF5555));
            }
        });
    }

    private ResourceLocation icon(ServerData d) {
        byte[] bytes = d.getIconBytes();
        String key = d.ip;
        if (bytes == null) {
            ResourceLocation old = icons.remove(key);
            if (old != null) minecraft.getTextureManager().release(old);
            iconHash.remove(key);
            return null;
        }
        int h = Arrays.hashCode(bytes);
        Integer prev = iconHash.get(key);
        if (prev != null && prev == h) return icons.get(key);
        iconHash.put(key, h);
        try {
            NativeImage img = NativeImage.read(bytes);
            ResourceLocation loc = ArcHud.id("server_icon/" + Integer.toHexString(key.hashCode()) + "_" + Integer.toHexString(h));
            minecraft.getTextureManager().register(loc, new DynamicTexture(img));
            ResourceLocation old = icons.put(key, loc);
            if (old != null && !old.equals(loc)) minecraft.getTextureManager().release(old);
            return loc;
        } catch (Exception e) {
            icons.remove(key);
            return null;
        }
    }

    @Override
    public void tick() {
        pinger.tick();
        if (lanList != null) {
            List<LanServer> dirty = lanList.takeDirtyServers();
            if (dirty != null) lan = new ArrayList<>(dirty);
        }
        if (selected >= total()) selected = -1;
        updateButtons();
    }

    // ------------------------------------------------------------------ ввод
    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        int idx = indexAt(mx, my);
        if (idx >= 0) {
            boolean dbl = idx == selected && Util.getMillis() - lastClick < 300;
            selected = idx;
            lastClick = Util.getMillis();
            updateButtons();
            if (dbl) joinSelected();
            return true;
        }
        return false;
    }

    /** Строка под курсором; учитывает заголовок раздела LAN. */
    private int indexAt(double mx, double my) {
        if (mx < listX() || mx >= listX() + listW() || my < listTop() || my >= listBottom()) return -1;
        double y = my - listTop() + scroll;
        int own = servers.size();
        if (y < own * ROW) return (int) (y / ROW);
        y -= own * ROW + 16;
        if (y < 0) return -1;
        int i = (int) (y / ROW);
        return i < lan.size() ? own + i : -1;
    }

    private double contentHeight() {
        return servers.size() * ROW + 16 + Math.max(1, lan.size()) * ROW;
    }

    private double maxScroll() {
        return Math.max(0, contentHeight() - (listBottom() - listTop()));
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scroll = Mth.clamp(scroll - delta * ROW, 0, maxScroll());
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
        if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
            int dir = key == GLFW.GLFW_KEY_UP ? -1 : 1;
            if (shift) move(dir);
            else if (total() > 0) {
                selected = Mth.clamp(selected + dir, 0, total() - 1);
                updateButtons();
            }
            return true;
        }
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && selected >= 0) {
            joinSelected();
            return true;
        }
        if (key == GLFW.GLFW_KEY_F5) {
            refresh();
            return true;
        }
        if (key == GLFW.GLFW_KEY_DELETE && selected >= 0 && selected < servers.size()) {
            deleteSelected();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    // ------------------------------------------------------------------ отрисовка
    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        ArcWidgets.drawBackground(g, width, height);
        int accent = Theme.accent();
        int lx = listX(), lw = listW(), top = listTop(), bottom = listBottom();
        g.drawString(font, title, lx, 12, Draw.TEXT, true);
        g.fill(lx, 23, lx + 30, 24, Draw.alpha(accent, 0xFF));
        String count = Component.translatable("archud.mp.count", servers.size(), lan.size()).getString();
        Draw.small(g, font, count, lx + lw - font.width(count) * 0.7F, 14, Draw.TEXT_DIM, 0.7F);

        Draw.panel(g, lx - 2, top - 2, lw + 4, bottom - top + 4, 0x90080B10, 0x30FFFFFF);
        scroll = Mth.clamp(scroll, 0, maxScroll());
        g.enableScissor(lx, top, lx + lw, bottom);
        int y = top - (int) scroll;
        for (int i = 0; i < servers.size(); i++) {
            ServerData d = servers.get(i);
            if (y + ROW >= top && y <= bottom) {
                ensurePinged(d);
                renderServer(g, d, i, lx, y, lw, mouseX, mouseY, accent, top, bottom);
            }
            y += ROW;
        }
        if (servers.size() == 0 && y + ROW >= top) {
            g.drawCenteredString(font, Component.translatable("archud.mp.empty"), lx + lw / 2, y + 16, Draw.TEXT_DIM);
            y += ROW;
        }
        // LAN
        g.fill(lx + 4, y + 7, lx + lw - 4, y + 8, 0x20FFFFFF);
        String lanTitle = Component.translatable("lanServer.title").getString();
        g.fill(lx + 8, y + 2, lx + 16 + font.width(lanTitle), y + 13, 0xFF0A0E13);
        g.drawString(font, lanTitle, lx + 12, y + 3, Draw.alpha(accent, 0xFF), true);
        y += 16;
        if (lan.isEmpty()) {
            String dots = ".".repeat((int) (Util.getMillis() / 400 % 4));
            g.drawCenteredString(font, Component.translatable("lanServer.scanning").getString() + dots, lx + lw / 2, y + 16, Draw.TEXT_DIM);
        }
        for (int i = 0; i < lan.size(); i++) {
            LanServer l = lan.get(i);
            int idx = servers.size() + i;
            if (y + ROW >= top && y <= bottom) renderLan(g, l, idx, lx, y, lw, mouseX, mouseY, accent, top, bottom);
            y += ROW;
        }
        g.disableScissor();
        if (maxScroll() > 0) {
            int track = bottom - top;
            int thumb = Math.max(20, (int) (track * track / contentHeight()));
            int ty = top + (int) ((track - thumb) * (scroll / maxScroll()));
            g.fill(lx + lw - 2, ty, lx + lw, ty + thumb, Draw.alpha(accent, 0xA0));
        }
        super.render(g, mouseX, mouseY, partialTick);
        // подсказка с игроками
        int hover = indexAt(mouseX, mouseY);
        if (hover >= 0 && hover < servers.size()) {
            ServerData d = servers.get(hover);
            int rx = lx + lw - 70;
            if (mouseX >= rx && d.playerList != null && !d.playerList.isEmpty()) g.renderComponentTooltip(font, d.playerList, mouseX, mouseY);
        }
    }

    private void rowFrame(GuiGraphics g, int idx, int x, int y, int w, int mouseX, int mouseY, int accent, int top, int bottom) {
        boolean sel = idx == selected;
        boolean hov = mouseX >= x && mouseX < x + w && mouseY >= Math.max(y, top) && mouseY < Math.min(y + ROW - 2, bottom);
        g.fill(x, y, x + w, y + ROW - 2, sel ? Draw.alpha(accent, 0x38) : hov ? 0x1AFFFFFF : 0x0CFFFFFF);
        if (sel) g.fill(x, y, x + 2, y + ROW - 2, Draw.alpha(accent, 0xFF));
    }

    private void renderServer(GuiGraphics g, ServerData d, int idx, int x, int y, int w, int mouseX, int mouseY, int accent, int top, int bottom) {
        rowFrame(g, idx, x, y, w, mouseX, mouseY, accent, top, bottom);
        int ix = x + 5, iy = y + 4;
        ResourceLocation ic = icon(d);
        if (ic != null) g.blit(ic, ix, iy, 0, 0, 34, 34, 34, 34);
        else {
            Draw.panel(g, ix, iy, 34, 34, 0xFF141A22, Draw.alpha(accent, 0x60));
            Draw.icon(g, Icons.GLOBE, ix + 13, iy + 13);
        }
        boolean hovIcon = mouseX >= ix && mouseX < ix + 34 && mouseY >= iy && mouseY < iy + 34 && mouseY >= top && mouseY < bottom;
        if (hovIcon) {
            g.fill(ix, iy, ix + 34, iy + 34, 0x80000000);
            g.drawCenteredString(font, "▶", ix + 17, iy + 13, 0xFFFFFFFF);
        }
        int tx = ix + 42;
        int right = x + w - 8;
        // пинг и онлайн
        int bars = pingBars(d.ping);
        int bx = right - 14;
        for (int i = 0; i < 5; i++) {
            int bh = 2 + i * 2;
            int color;
            if (d.ping == -2L) color = (Util.getMillis() / 120 % 5) == i ? Draw.alpha(accent, 0xFF) : 0x40FFFFFF;
            else if (d.ping < 0) color = i == 0 ? 0xFFFF5252 : 0x30FFFFFF;
            else color = i < bars ? (bars >= 4 ? 0xFF9CFF5A : bars >= 2 ? 0xFFFFD54F : 0xFFFF8A65) : 0x30FFFFFF;
            g.fill(bx + i * 3, y + 15 - bh, bx + i * 3 + 2, y + 15, color);
        }
        String pingText = d.ping >= 0 ? d.ping + " ms" : "";
        if (!pingText.isEmpty()) Draw.small(g, font, pingText, bx - 4 - font.width(pingText) * 0.6F, y + 9, Draw.TEXT_DIM, 0.6F);
        boolean incompatible = d.pinged && d.ping >= 0 && d.protocol != SharedConstants.getCurrentVersion().getProtocolVersion();
        Component status = incompatible ? d.version.copy().withStyle(s -> s.withColor(0xFF5555)) : d.status;
        int sw = status == null ? 0 : font.width(status);
        if (status != null) g.drawString(font, status, right - sw, y + 20, Draw.TEXT_DIM, true);
        // имя
        int nameMax = right - sw - 10 - tx;
        String name = d.name;
        if (isLast(d)) name = "★ " + name;
        if (font.width(name) > Math.max(40, nameMax - 50)) name = font.plainSubstrByWidth(name, Math.max(40, nameMax - 56)) + "…";
        g.drawString(font, name, tx, y + 5, isLast(d) ? 0xFFFFD54F : Draw.TEXT, true);
        // MOTD (2 строки)
        if (d.motd != null) {
            List<FormattedCharSequence> lines = font.split(d.motd, Math.max(40, nameMax));
            for (int i = 0; i < Math.min(2, lines.size()); i++) {
                g.drawString(font, lines.get(i), tx, y + 17 + i * 10, Draw.TEXT_DIM, false);
            }
        }
    }

    private void renderLan(GuiGraphics g, LanServer l, int idx, int x, int y, int w, int mouseX, int mouseY, int accent, int top, int bottom) {
        rowFrame(g, idx, x, y, w, mouseX, mouseY, accent, top, bottom);
        int ix = x + 5, iy = y + 4;
        Draw.panel(g, ix, iy, 34, 34, 0xFF141A22, Draw.alpha(accent, 0x60));
        Draw.icon(g, Icons.SIGNAL, ix + 13, iy + 13);
        g.drawString(font, Component.translatable("lanServer.title"), ix + 42, y + 5, Draw.TEXT, true);
        g.drawString(font, l.getMotd(), ix + 42, y + 17, Draw.TEXT_DIM, false);
        Draw.small(g, font, l.getAddress(), ix + 42, y + 29, 0xFF6F7C8B, 0.65F);
    }

    private static int pingBars(long ping) {
        if (ping < 0) return 0;
        if (ping < 150) return 5;
        if (ping < 300) return 4;
        if (ping < 600) return 3;
        if (ping < 1000) return 2;
        return 1;
    }

    @Override
    public void removed() {
        pinger.removeAll();
        if (lanDetector != null) {
            lanDetector.interrupt();
            lanDetector = null;
        }
        for (ResourceLocation r : icons.values()) minecraft.getTextureManager().release(r);
        icons.clear();
        iconHash.clear();
    }

    @Override
    public void onClose() {
        if (lanDetector != null) {
            lanDetector.interrupt();
            lanDetector = null;
        }
        minecraft.setScreen(parent);
    }

    ServerList servers() {
        return servers;
    }
}
