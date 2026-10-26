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
import net.minecraft.client.gui.components.EditBox;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * «Сетевая игра» Arc HUD — переработанный экран из двух частей.
 *
 * Сверху: быстрое подключение (адрес → «Играть» / «Сохранить»), кнопки «Добавить», «Прямое подключение»,
 * «Обновить всё». Ниже — вкладки «Все», «Избранные», «Локальная сеть», «История» (все серверы, куда вы
 * заходили, даже не сохранённые), поиск по имени/адресу и сортировка (свой порядок, пинг, онлайн, имя).
 *
 * Слева — карточки серверов (иконка, MOTD, пинг, онлайн с полоской заполненности, звезда избранного).
 * Справа — подробности выбранного сервера: адрес (клик — скопировать), версия, график пинга, список игроков,
 * сколько вы на нём играли, когда заходили в последний раз и сколько раз, заметка к серверу и действия:
 * «Играть», «Изменить», «Скопировать адрес», «Пинг», «В избранное», «Ресурспак сервера: спрашивать/да/нет»,
 * «Выше/Ниже», «Удалить».
 */
public class ArcMultiplayerScreen extends Screen implements ScreenFit.Fits {
    private enum TabKind { ALL, FAVORITES, LAN, HISTORY }

    private record Entry(String key, ServerData data, LanServer lan, int ownIndex) {}

    private record Hit(int x, int y, int w, int h, Runnable action, Component tip) {
        boolean in(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private static final int ROW = 46;
    private static final ExecutorService PING_POOL = Executors.newFixedThreadPool(5, r -> {
        Thread t = new Thread(r, "Arc HUD server ping");
        t.setDaemon(true);
        return t;
    });
    /** История пинга по адресам (для графика). */
    private static final Map<String, Deque<Long>> PING_HISTORY = new HashMap<>();
    private static TabKind tab = TabKind.ALL;
    private static int sort;
    private static String selectedKey;

    private final Screen parent;
    private ServerList servers;
    private final ServerStatusPinger pinger = new ServerStatusPinger();
    private LanServerDetection.LanServerList lanList;
    private LanServerDetection.LanServerDetector lanDetector;
    private List<LanServer> lan = new ArrayList<>();
    private final Map<String, ServerData> historyData = new HashMap<>();
    private final Map<String, Long> pingStarted = new HashMap<>();
    private final Map<String, ResourceLocation> icons = new HashMap<>();
    private final Map<String, Integer> iconHash = new HashMap<>();
    private final List<Hit> hits = new ArrayList<>();
    private List<Entry> rows = new ArrayList<>();
    private EditBox quick, search, note;
    private double scroll;
    private long lastClick;
    private boolean initialized;
    private String builtFor = "";

    // геометрия
    private int listX, listW, detX, detW, listTop, listBottom;

    public ArcMultiplayerScreen(Screen parent) {
        super(Component.translatable("multiplayer.title"));
        this.parent = parent;
    }

    // ================================================================== разметка и виджеты

    @Override
    protected void init() {
        ScreenFit.ensure(this, 500, 300);
        if (!initialized) {
            initialized = true;
            servers = new ServerList(minecraft);
            servers.load();
            if (selectedKey == null && ArcConfig.get().lastServerIp != null && !ArcConfig.get().lastServerIp.isEmpty()) {
                selectedKey = "own:" + ArcConfig.get().lastServerIp.toLowerCase(Locale.ROOT);
            }
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
        int pad = 10;
        listX = pad;
        listW = (int) ((width - pad * 2) * 0.58F);
        detX = listX + listW + 8;
        detW = width - pad - detX;
        listTop = 80;
        listBottom = height - 10;

        // шапка справа
        int bx = width - pad;
        bx -= 76;
        addRenderableWidget(new ArcButton(bx, 8, 76, 18, Component.translatable("selectServer.add"),
                b -> minecraft.setScreen(new ArcServerEditScreen(this, null, false))).icon(Icons.STAR));
        bx -= 106;
        addRenderableWidget(new ArcButton(bx, 8, 102, 18, Component.translatable("selectServer.direct"),
                b -> minecraft.setScreen(new ArcServerEditScreen(this, null, true))).icon(Icons.SIGNAL));
        bx -= 86;
        addRenderableWidget(new ArcButton(bx, 8, 82, 18, Component.translatable("archud.mp.refresh_all"), b -> refreshAll()));
        bx -= 64;
        addRenderableWidget(new ArcButton(bx, 8, 60, 18, CommonComponents.GUI_BACK, b -> onClose()));

        // быстрое подключение
        String q = quick != null ? quick.getValue() : "";
        quick = new EditBox(font, listX + 1, 35, listW - 170, 16, Component.translatable("archud.mp.quick"));
        quick.setHint(Component.translatable("archud.mp.quick").withStyle(s -> s.withColor(0x6A7380)));
        quick.setMaxLength(128);
        quick.setValue(q);
        addRenderableWidget(quick);
        addRenderableWidget(new ArcButton(listX + listW - 164, 34, 80, 18, Component.translatable("archud.mp.play"), b -> quickConnect()).icon(Icons.JUMP));
        addRenderableWidget(new ArcButton(listX + listW - 80, 34, 80, 18, Component.translatable("archud.mp.save"), b -> quickSave()));

        // поиск — в шапке между заголовком и кнопками, сортировка — справа от вкладок
        String s = search != null ? search.getValue() : "";
        int sx = listX + 110;
        search = new EditBox(font, sx, 9, Math.max(60, bx - 8 - sx), 16, Component.translatable("archud.mp.search"));
        search.setHint(Component.translatable("archud.mp.search").withStyle(st -> st.withColor(0x6A7380)));
        search.setValue(s);
        search.setResponder(v -> scroll = 0);
        addRenderableWidget(search);
        addRenderableWidget(new ArcButton(listX + listW - 78, 57, 78, 16, Component.translatable("archud.mp.sort." + sort), b -> {
            sort = (sort + 1) % 4;
            b.setMessage(Component.translatable("archud.mp.sort." + sort));
        }));

        buildDetailWidgets();
    }

    /** Кнопки правой части зависят от выбранного сервера — пересоздаются при смене выбора. */
    private void buildDetailWidgets() {
        builtFor = selectedKey == null ? "" : selectedKey;
        note = null;
        Entry e = selectedEntry();
        if (e == null) return;
        int x = detX + 8, w = detW - 16;
        int y = listBottom - 8;
        boolean own = e.ownIndex() >= 0;
        boolean saved = own || e.lan() != null;
        // снизу вверх
        if (own) {
            y -= 18;
            int third = (w - 8) / 3;
            addRenderableWidget(new ArcButton(x, y, third, 16, Component.literal("▲"), b -> move(-1))).active = sort == 0 && e.ownIndex() > 0;
            addRenderableWidget(new ArcButton(x + third + 4, y, third, 16, Component.literal("▼"), b -> move(1))).active = sort == 0 && e.ownIndex() < servers.size() - 1;
            addRenderableWidget(new ArcButton(x + (third + 4) * 2, y, w - (third + 4) * 2, 16, Component.translatable("selectServer.delete"), b -> delete(e)).danger());
            y -= 18;
            ServerData d = e.data();
            addRenderableWidget(new ArcButton(x, y, w, 16, packLabel(d), b -> {
                ServerData.ServerPackStatus[] v = ServerData.ServerPackStatus.values();
                d.setResourcePackStatus(v[(d.getResourcePackStatus().ordinal() + 1) % v.length]);
                servers.save();
                b.setMessage(packLabel(d));
            }));
        }
        y -= 18;
        int half = (w - 4) / 2;
        String ip = address(e);
        addRenderableWidget(new ArcButton(x, y, half, 16, Component.translatable("archud.mp.copy"), b -> {
            minecraft.keyboardHandler.setClipboard(ip);
            toast("archud.mp.copied", ip);
        }));
        addRenderableWidget(new ArcButton(x + half + 4, y, w - half - 4, 16, Component.translatable("archud.mp.ping"), b -> {
            if (e.data() != null) e.data().pinged = false;
        }));
        y -= 18;
        if (own) {
            addRenderableWidget(new ArcButton(x, y, half, 16, Component.translatable("selectServer.edit"),
                    b -> minecraft.setScreen(new ArcServerEditScreen(this, e.data(), false))));
        } else if (!saved) {
            addRenderableWidget(new ArcButton(x, y, half, 16, Component.translatable("archud.mp.add_list"), b -> {
                ServerData d = new ServerData(e.data().name, e.data().ip, false);
                saveServer(d, true);
                selectedKey = "own:" + d.ip.toLowerCase(Locale.ROOT);
                rebuildWidgets();
            }));
        }
        boolean fav = ArcConfig.get().favoriteServers.contains(ip.toLowerCase(Locale.ROOT));
        if (e.lan() == null) {
            addRenderableWidget(new ArcButton(x + half + 4, y, w - half - 4, 16,
                    Component.translatable(fav ? "archud.mp.unfav" : "archud.mp.fav"), b -> toggleFav(ip))).icon(Icons.STAR);
        }
        y -= 24;
        addRenderableWidget(new ArcButton(x, y, w, 20, Component.translatable("archud.mp.play"), b -> join(e)).icon(Icons.JUMP));
        // заметка
        if (e.lan() == null) {
            y -= 20;
            note = new EditBox(font, x, y, w, 14, Component.translatable("archud.mp.note"));
            note.setHint(Component.translatable("archud.mp.note").withStyle(st -> st.withColor(0x6A7380)));
            note.setMaxLength(120);
            note.setValue(ArcConfig.get().serverNotes.getOrDefault(ip.toLowerCase(Locale.ROOT), ""));
            note.setResponder(v -> {
                String k = ip.toLowerCase(Locale.ROOT);
                if (v.isBlank()) ArcConfig.get().serverNotes.remove(k);
                else ArcConfig.get().serverNotes.put(k, v);
                ArcConfig.save();
            });
            addRenderableWidget(note);
        }
        detailsActionsTop = y - 4;
    }

    private int detailsActionsTop;

    private static Component packLabel(ServerData d) {
        return Component.translatable("archud.mp.pack", d.getResourcePackStatus().getName());
    }

    // ================================================================== данные

    private static String address(Entry e) {
        return e.lan() != null ? e.lan().getAddress() : e.data().ip;
    }

    private Entry selectedEntry() {
        if (selectedKey == null) return null;
        for (Entry e : rows) if (e.key().equals(selectedKey)) return e;
        // строки ещё не построены — ищем напрямую
        for (Entry e : buildRows()) if (e.key().equals(selectedKey)) return e;
        return null;
    }

    private List<Entry> buildRows() {
        List<Entry> out = new ArrayList<>();
        if (servers == null) return out;
        ArcConfig cfg = ArcConfig.get();
        String q = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        if (tab == TabKind.ALL || tab == TabKind.FAVORITES) {
            for (int i = 0; i < servers.size(); i++) {
                ServerData d = servers.get(i);
                String k = d.ip.toLowerCase(Locale.ROOT);
                if (tab == TabKind.FAVORITES && !cfg.favoriteServers.contains(k)) continue;
                if (!q.isEmpty() && !d.name.toLowerCase(Locale.ROOT).contains(q) && !k.contains(q)) continue;
                out.add(new Entry("own:" + k, d, null, i));
            }
            if (sort == 1) out.sort(Comparator.comparingLong(e -> e.data().ping < 0 ? Long.MAX_VALUE : e.data().ping));
            else if (sort == 2) out.sort(Comparator.comparingInt((Entry e) -> e.data().players == null ? -1 : e.data().players.online()).reversed());
            else if (sort == 3) out.sort(Comparator.comparing(e -> e.data().name.toLowerCase(Locale.ROOT)));
        }
        if (tab == TabKind.ALL || tab == TabKind.LAN) {
            for (LanServer l : lan) {
                if (!q.isEmpty() && !l.getMotd().toLowerCase(Locale.ROOT).contains(q)) continue;
                out.add(new Entry("lan:" + l.getAddress(), new ServerData(l.getMotd(), l.getAddress(), true), l, -1));
            }
        }
        if (tab == TabKind.HISTORY) {
            List<Map.Entry<String, ArcConfig.ServerStat>> hist = new ArrayList<>(cfg.serverStats.entrySet());
            hist.sort(Comparator.comparingLong((Map.Entry<String, ArcConfig.ServerStat> e) -> e.getValue().lastJoin).reversed());
            for (Map.Entry<String, ArcConfig.ServerStat> h : hist) {
                String k = h.getKey();
                ServerData own = ownByIp(k);
                if (own != null) {
                    out.add(new Entry("own:" + k, own, null, indexOf(own)));
                } else {
                    ServerData d = historyData.computeIfAbsent(k, ip -> new ServerData(h.getValue().name.isEmpty() ? ip : h.getValue().name, ip, false));
                    if (!q.isEmpty() && !d.name.toLowerCase(Locale.ROOT).contains(q) && !k.contains(q)) continue;
                    out.add(new Entry("hist:" + k, d, null, -1));
                }
            }
        }
        return out;
    }

    private ServerData ownByIp(String ip) {
        for (int i = 0; i < servers.size(); i++) if (servers.get(i).ip.equalsIgnoreCase(ip)) return servers.get(i);
        return null;
    }

    private int indexOf(ServerData d) {
        for (int i = 0; i < servers.size(); i++) if (servers.get(i) == d) return i;
        return -1;
    }

    // ================================================================== действия

    /** Подключение; используется и экраном изменения сервера. */
    void connect(ServerData data) {
        ArcConfig cfg = ArcConfig.get();
        if (!data.isLan()) {
            cfg.lastServerIp = data.ip;
            cfg.lastServerName = data.name;
            ArcConfig.ServerStat st = cfg.serverStats.computeIfAbsent(data.ip.toLowerCase(Locale.ROOT), k -> new ArcConfig.ServerStat());
            st.name = data.name;
            st.joins++;
            st.lastJoin = System.currentTimeMillis();
            ArcConfig.save();
        }
        ConnectScreen.startConnecting(this, minecraft, ServerAddress.parseString(data.ip), data, false);
    }

    private void join(Entry e) {
        connect(e.lan() != null ? new ServerData(e.lan().getMotd(), e.lan().getAddress(), true) : e.data());
    }

    private void quickConnect() {
        String ip = quick.getValue().trim();
        if (ip.isEmpty() || !ServerAddress.isValidAddress(ip)) {
            toast("archud.mp.bad_address", ip);
            return;
        }
        ServerData own = ownByIp(ip);
        connect(own != null ? own : new ServerData(ip, ip, false));
    }

    private void quickSave() {
        String ip = quick.getValue().trim();
        if (ip.isEmpty() || !ServerAddress.isValidAddress(ip)) {
            toast("archud.mp.bad_address", ip);
            return;
        }
        if (ownByIp(ip) == null) saveServer(new ServerData(ip, ip, false), true);
        selectedKey = "own:" + ip.toLowerCase(Locale.ROOT);
        quick.setValue("");
        rebuildWidgets();
    }

    private void delete(Entry e) {
        ServerData d = e.data();
        minecraft.setScreen(new ConfirmScreen(ok -> {
            if (ok) {
                servers.remove(d);
                servers.save();
                selectedKey = null;
            }
            minecraft.setScreen(this);
        }, Component.translatable("selectServer.deleteQuestion"), Component.translatable("selectServer.deleteWarning", d.name),
                Component.translatable("selectServer.deleteButton"), CommonComponents.GUI_CANCEL));
    }

    /** Вызывается экраном добавления / изменения. */
    void saveServer(ServerData data, boolean isNew) {
        if (isNew) {
            if (servers.unhide(data.ip) == null) servers.add(data, false);
            selectedKey = "own:" + data.ip.toLowerCase(Locale.ROOT);
        }
        servers.save();
    }

    private void refreshAll() {
        pinger.removeAll();
        for (int i = 0; i < servers.size(); i++) servers.get(i).pinged = false;
        for (ServerData d : historyData.values()) d.pinged = false;
    }

    private void move(int dir) {
        Entry e = selectedEntry();
        if (e == null || e.ownIndex() < 0) return;
        int to = e.ownIndex() + dir;
        if (to < 0 || to >= servers.size()) return;
        servers.swap(e.ownIndex(), to);
        servers.save();
        rebuildWidgets();
    }

    private void toggleFav(String ip) {
        List<String> fav = ArcConfig.get().favoriteServers;
        String k = ip.toLowerCase(Locale.ROOT);
        if (!fav.remove(k)) fav.add(k);
        ArcConfig.save();
        rebuildWidgets();
    }

    private void toast(String key, String arg) {
        net.minecraft.client.gui.components.toasts.SystemToast.addOrUpdate(minecraft.getToasts(),
                net.minecraft.client.gui.components.toasts.SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
                Component.translatable("multiplayer.title"), Component.translatable(key, arg));
    }

    private void select(Entry e) {
        if (e.key().equals(selectedKey)) return;
        selectedKey = e.key();
        rebuildWidgets();
    }

    // ================================================================== пинг

    private void ensurePinged(ServerData d) {
        if (d.pinged) return;
        // перед новым пингом запоминаем прошлый результат для графика
        if (d.ping >= 0) {
            Deque<Long> h = PING_HISTORY.computeIfAbsent(d.ip.toLowerCase(Locale.ROOT), k -> new ArrayDeque<>());
            h.addLast(d.ping);
            while (h.size() > 24) h.removeFirst();
        }
        d.pinged = true;
        d.ping = -2L;
        d.motd = CommonComponents.EMPTY;
        d.status = CommonComponents.EMPTY;
        pingStarted.put(d.ip, Util.getMillis());
        PING_POOL.submit(() -> {
            try {
                pinger.pingServer(d, () -> minecraft.execute(() -> {
                    if (servers.get(d.ip) != null) servers.saveSingleServer(d);
                }));
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
        for (EditBox b : new EditBox[]{quick, search, note}) if (b != null) b.tick();
        // выбранный сервер пингуется заново каждые 10 секунд (для графика)
        Entry e = selectedEntry();
        if (e != null && e.data() != null && e.lan() == null && e.data().ping >= 0) {
            Long started = pingStarted.get(e.data().ip);
            if (started == null || Util.getMillis() - started > 10_000L) e.data().pinged = false;
        }
        String now = selectedKey == null ? "" : selectedKey;
        if (!now.equals(builtFor)) rebuildWidgets();
    }

    // ================================================================== отрисовка

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        ArcWidgets.drawBackground(g, width, height);
        int accent = Theme.accent();
        hits.clear();
        rows = buildRows();

        // шапка
        g.drawString(font, title, listX, 9, Draw.TEXT, true);
        g.fill(listX, 20, listX + 34, 21, accent);
        Draw.small(g, font, Component.translatable("archud.mp.count", servers.size(), lan.size()).getString(), listX, 24, Draw.TEXT_DIM, 0.6F);

        // вкладки
        TabKind[] kinds = TabKind.values();
        for (int i = 0; i < kinds.length; i++) {
            TabKind k = kinds[i];
            int tabW = Math.min(70, (listW - 82) / 4 - 3);
            int tx = listX + i * (tabW + 3), ty = 57;
            boolean sel = k == tab;
            boolean hov = mx >= tx && mx < tx + tabW && my >= ty && my < ty + 16;
            Draw.panel(g, tx, ty, tabW, 16, sel ? Draw.alpha(accent, 0x55) : hov ? 0x30FFFFFF : 0x18FFFFFF, sel ? accent : 0x30FFFFFF);
            int count = switch (k) {
                case ALL -> servers.size() + lan.size();
                case FAVORITES -> ArcConfig.get().favoriteServers.size();
                case LAN -> lan.size();
                case HISTORY -> ArcConfig.get().serverStats.size();
            };
            String label = Component.translatable("archud.mp.tab." + k.name().toLowerCase(Locale.ROOT)).getString() + " " + count;
            Draw.smallCentered(g, font, label, tx + tabW / 2F, ty + 5, sel ? 0xFFFFFFFF : Draw.TEXT, 0.6F);
            hits.add(new Hit(tx, ty, tabW, 16, () -> {
                if (tab != k) {
                    tab = k;
                    scroll = 0;
                }
            }, null));
        }

        renderList(g, mx, my, accent);
        renderDetails(g, mx, my, accent);
        super.render(g, mx, my, pt);

        // подсказки
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (h.tip() != null && h.in(mx, my)) {
                g.renderTooltip(font, h.tip(), mx, my);
                break;
            }
        }
        Entry hov = rowAt(mx, my);
        if (hov != null && hov.data() != null && hov.data().playerList != null && !hov.data().playerList.isEmpty() && mx > listX + listW - 90) {
            g.renderComponentTooltip(font, hov.data().playerList, mx, my);
        }
    }

    private double maxScroll() {
        return Math.max(0, rows.size() * ROW - (listBottom - listTop));
    }

    private Entry rowAt(double mx, double my) {
        if (mx < listX || mx >= listX + listW || my < listTop || my >= listBottom) return null;
        int i = (int) ((my - listTop + scroll) / ROW);
        return i >= 0 && i < rows.size() ? rows.get(i) : null;
    }

    private void renderList(GuiGraphics g, int mx, int my, int accent) {
        Draw.panel(g, listX - 2, listTop - 2, listW + 4, listBottom - listTop + 4, 0x90080B10, 0x30FFFFFF);
        scroll = Mth.clamp(scroll, 0, maxScroll());
        if (rows.isEmpty()) {
            String key = switch (tab) {
                case FAVORITES -> "archud.mp.empty_fav";
                case LAN -> "lanServer.scanning";
                case HISTORY -> "archud.mp.empty_history";
                default -> "archud.mp.empty";
            };
            g.drawCenteredString(font, Component.translatable(key), listX + listW / 2, listTop + 30, Draw.TEXT_DIM);
            return;
        }
        g.enableScissor(listX, listTop, listX + listW, listBottom);
        int y = listTop - (int) scroll;
        for (Entry e : rows) {
            if (y + ROW >= listTop && y <= listBottom) renderRow(g, e, listX, y, listW, mx, my, accent);
            y += ROW;
        }
        g.disableScissor();
        if (maxScroll() > 0) {
            int track = listBottom - listTop;
            int thumb = Math.max(20, (int) (track * (double) track / (rows.size() * ROW)));
            int ty = listTop + (int) ((track - thumb) * (scroll / maxScroll()));
            g.fill(listX + listW - 2, ty, listX + listW, ty + thumb, Draw.alpha(accent, 0xA0));
        }
    }

    private void renderRow(GuiGraphics g, Entry e, int x, int y, int w, int mx, int my, int accent) {
        boolean sel = e.key().equals(selectedKey);
        boolean hov = mx >= x && mx < x + w && my >= Math.max(y, listTop) && my < Math.min(y + ROW - 2, listBottom);
        g.fill(x, y, x + w, y + ROW - 2, sel ? Draw.alpha(accent, 0x38) : hov ? 0x1AFFFFFF : 0x0CFFFFFF);
        if (sel) g.fill(x, y, x + 2, y + ROW - 2, accent);
        int ix = x + 6, iy = y + 5;
        ServerData d = e.data();
        if (e.lan() != null) {
            Draw.panel(g, ix, iy, 34, 34, 0xFF141A22, Draw.alpha(accent, 0x60));
            Draw.icon(g, Icons.SIGNAL, ix + 13, iy + 13);
            g.drawString(font, e.lan().getMotd(), ix + 42, y + 6, Draw.TEXT, true);
            Draw.small(g, font, Component.translatable("lanServer.title").getString() + " · " + e.lan().getAddress(), ix + 42, y + 18, Draw.TEXT_DIM, 0.65F);
            return;
        }
        ensurePinged(d);
        ResourceLocation ic = icon(d);
        if (ic != null) g.blit(ic, ix, iy, 0, 0, 34, 34, 34, 34);
        else {
            Draw.panel(g, ix, iy, 34, 34, 0xFF141A22, Draw.alpha(accent, 0x60));
            Draw.icon(g, Icons.GLOBE, ix + 13, iy + 13);
        }
        int right = x + w - 8;
        // пинг
        int bars = pingBars(d.ping);
        int bx = right - 14;
        for (int i = 0; i < 5; i++) {
            int bh = 2 + i * 2;
            int color;
            if (d.ping == -2L) color = (Util.getMillis() / 120 % 5) == i ? accent : 0x40FFFFFF;
            else if (d.ping < 0) color = i == 0 ? 0xFFFF5252 : 0x30FFFFFF;
            else color = i < bars ? (bars >= 4 ? 0xFF9CFF5A : bars >= 2 ? 0xFFFFD54F : 0xFFFF8A65) : 0x30FFFFFF;
            g.fill(bx + i * 3, y + 15 - bh, bx + i * 3 + 2, y + 15, color);
        }
        if (d.ping >= 0) Draw.small(g, font, d.ping + " ms", bx - 4 - font.width(d.ping + " ms") * 0.6F, y + 9, Draw.TEXT_DIM, 0.6F);
        // онлайн + полоска
        if (d.players != null && d.players.max() > 0) {
            String on = d.players.online() + " / " + d.players.max();
            Draw.small(g, font, on, right - font.width(on) * 0.7F, y + 22, Draw.TEXT, 0.7F);
            float f = Mth.clamp(d.players.online() / (float) d.players.max(), 0, 1);
            g.fill(right - 50, y + 31, right, y + 33, 0x30FFFFFF);
            g.fill(right - 50, y + 31, right - 50 + Math.round(50 * f), y + 33, f > 0.9F ? 0xFFFF8A65 : 0xFF9CFF5A);
        }
        boolean incompatible = d.pinged && d.ping >= 0 && d.protocol != SharedConstants.getCurrentVersion().getProtocolVersion();
        if (incompatible) {
            String v = d.version.getString();
            Draw.small(g, font, v, right - font.width(v) * 0.6F, y + 36, 0xFFFF5555, 0.6F);
        }
        // имя, звезда, MOTD
        int tx = ix + 42;
        int nameMax = right - 70 - tx;
        boolean fav = ArcConfig.get().favoriteServers.contains(d.ip.toLowerCase(Locale.ROOT));
        String name = d.name;
        if (font.width(name) > nameMax - 12) name = font.plainSubstrByWidth(name, Math.max(20, nameMax - 18)) + "…";
        g.drawString(font, name, tx, y + 5, Draw.TEXT, true);
        int sx = tx + font.width(name) + 4;
        Draw.icon(g, Icons.STAR, sx, y + 4);
        if (!fav) g.fill(sx, y + 4, sx + 9, y + 13, 0xB0101419);
        String ip = d.ip;
        hits.add(new Hit(sx - 1, y + 3, 11, 11, () -> toggleFav(ip), Component.translatable(fav ? "archud.mp.unfav" : "archud.mp.fav")));
        if (d.motd != null) {
            List<FormattedCharSequence> lines = font.split(d.motd, Math.max(40, nameMax));
            for (int i = 0; i < Math.min(2, lines.size()); i++) g.drawString(font, lines.get(i), tx, y + 17 + i * 10, Draw.TEXT_DIM, false);
        }
        String noteText = ArcConfig.get().serverNotes.get(d.ip.toLowerCase(Locale.ROOT));
        if (noteText != null && d.motd == null) Draw.small(g, font, noteText, tx, y + 36, 0xFFFFE082, 0.55F);
    }

    private void renderDetails(GuiGraphics g, int mx, int my, int accent) {
        Draw.panel(g, detX, listTop - 2, detW, listBottom - listTop + 4, 0xC00B0F14, Draw.alpha(accent, 0x60));
        Entry e = selectedEntry();
        if (e == null) {
            Draw.smallCentered(g, font, Component.translatable("archud.mp.select_hint").getString(), detX + detW / 2F, listTop + 40, Draw.TEXT_DIM, 0.7F);
            ArcConfig cfg = ArcConfig.get();
            long total = 0;
            for (ArcConfig.ServerStat st : cfg.serverStats.values()) total += st.playSeconds;
            Draw.smallCentered(g, font, Component.translatable("archud.mp.total_time", duration(total)).getString(), detX + detW / 2F, listTop + 56, 0xFF6A7682, 0.6F);
            return;
        }
        int x = detX + 8, y = listTop + 6, w = detW - 16;
        ServerData d = e.data();
        // иконка + название
        ResourceLocation ic = e.lan() == null ? icon(d) : null;
        Draw.hGradient(g, detX + 1, listTop - 1, detX + detW - 1, listTop + 56, Draw.alpha(accent, 0x30), 0);
        if (ic != null) g.blit(ic, x, y, 0, 0, 48, 48, 48, 48);
        else {
            Draw.panel(g, x, y, 48, 48, 0xFF141A22, Draw.alpha(accent, 0x60));
            Draw.icon(g, e.lan() != null ? Icons.SIGNAL : Icons.GLOBE, x + 20, y + 20);
        }
        int tx = x + 56;
        String name = e.lan() != null ? e.lan().getMotd() : d.name;
        int nmax = w - 56;
        if (font.width(name) > nmax) name = font.plainSubstrByWidth(name, nmax - font.width("…")) + "…";
        g.drawString(font, name, tx, y + 2, 0xFFFFFFFF, true);
        String ip = address(e);
        boolean hovIp = mx >= tx && mx < tx + font.width(ip) * 0.7F && my >= y + 13 && my < y + 21;
        Draw.small(g, font, ip, tx, y + 14, hovIp ? accent : Draw.TEXT_DIM, 0.7F);
        hits.add(new Hit(tx, y + 13, (int) (font.width(ip) * 0.7F) + 2, 8, () -> {
            minecraft.keyboardHandler.setClipboard(ip);
            toast("archud.mp.copied", ip);
        }, Component.translatable("archud.mp.click_copy")));
        if (e.lan() == null && d.ping >= 0) {
            Draw.small(g, font, d.version.getString(), tx, y + 23, 0xFF6A7682, 0.6F);
        }
        y += 54;
        if (e.lan() == null) {
            // онлайн и пинг
            String on = d.players != null ? d.players.online() + " / " + d.players.max() : "—";
            stat(g, x, y, w, "archud.mp.online", on);
            stat(g, x, y + 9, w, "archud.mp.ping_now", d.ping >= 0 ? d.ping + " ms" : d.ping == -2 ? "…" : "—");
            y += 20;
            // график пинга
            Deque<Long> hist = PING_HISTORY.get(d.ip.toLowerCase(Locale.ROOT));
            int gh = 22;
            Draw.panel(g, x, y, w, gh, 0x40000000, 0x20FFFFFF);
            if (hist != null && hist.size() > 1) {
                long max = 1;
                for (long v : hist) max = Math.max(max, v);
                int n = hist.size(), i = 0;
                int px = -1, py = -1;
                for (long v : hist) {
                    int cx = x + 2 + (w - 4) * i / (n - 1);
                    int cy = y + gh - 3 - (int) ((gh - 6) * v / (double) max);
                    if (px >= 0) line(g, px, py, cx, cy, accent);
                    px = cx;
                    py = cy;
                    i++;
                }
                Draw.small(g, font, max + " ms", x + w - 3 - font.width(max + " ms") * 0.5F, y + 2, Draw.TEXT_DIM, 0.5F);
            } else {
                Draw.smallCentered(g, font, Component.translatable("archud.mp.graph_wait").getString(), x + w / 2F, y + 8, 0xFF6A7682, 0.55F);
            }
            y += gh + 4;
            // статистика
            ArcConfig.ServerStat st = ArcConfig.get().serverStats.get(d.ip.toLowerCase(Locale.ROOT));
            if (st != null) {
                stat(g, x, y, w, "archud.mp.playtime", duration(st.playSeconds));
                stat(g, x, y + 9, w, "archud.mp.last_join", ago(st.lastJoin));
                stat(g, x, y + 18, w, "archud.mp.joins", Integer.toString(st.joins));
                y += 28;
            } else {
                Draw.small(g, font, Component.translatable("archud.mp.never").getString(), x, y, 0xFF6A7682, 0.6F);
                y += 10;
            }
            // игроки
            if (d.playerList != null && !d.playerList.isEmpty()) {
                Draw.small(g, font, Component.translatable("archud.mp.players").getString(), x, y + 1, Draw.alpha(accent, 0xFF), 0.6F);
                y += 9;
                for (Component c : d.playerList) {
                    if (y + 8 > detailsActionsTop) break;
                    Draw.small(g, font, c.getString(), x + 4, y, Draw.TEXT, 0.6F);
                    y += 7;
                }
            }
        }
    }

    private void stat(GuiGraphics g, int x, int y, int w, String key, String value) {
        Draw.small(g, font, Component.translatable(key).getString(), x, y, Draw.TEXT_DIM, 0.6F);
        Draw.small(g, font, value, x + w - font.width(value) * 0.6F, y, Draw.TEXT, 0.6F);
    }

    private static void line(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
        int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        for (int i = 0; i <= steps; i++) {
            int x = x0 + (x1 - x0) * i / Math.max(1, steps), y = y0 + (y1 - y0) * i / Math.max(1, steps);
            g.fill(x, y, x + 1, y + 1, color);
        }
    }

    static String duration(long sec) {
        long h = sec / 3600, m = (sec % 3600) / 60;
        if (h > 0) return Component.translatable("archud.mp.hm", h, m).getString();
        return Component.translatable("archud.mp.m", m).getString();
    }

    private static String ago(long time) {
        if (time <= 0) return "—";
        long s = (System.currentTimeMillis() - time) / 1000;
        if (s < 60) return Component.translatable("archud.mp.ago_now").getString();
        if (s < 3600) return Component.translatable("archud.mp.ago_m", s / 60).getString();
        if (s < 86400) return Component.translatable("archud.mp.ago_h", s / 3600).getString();
        return Component.translatable("archud.mp.ago_d", s / 86400).getString();
    }

    private static int pingBars(long ping) {
        if (ping < 0) return 0;
        if (ping < 150) return 5;
        if (ping < 300) return 4;
        if (ping < 600) return 3;
        if (ping < 1000) return 2;
        return 1;
    }

    // ================================================================== ввод

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        for (EditBox b : new EditBox[]{quick, search, note}) if (b != null && b.isFocused() && !b.isMouseOver(mx, my)) b.setFocused(false);
        for (int i = hits.size() - 1; i >= 0; i--) {
            if (hits.get(i).in(mx, my)) {
                hits.get(i).action().run();
                return true;
            }
        }
        Entry e = rowAt(mx, my);
        if (e != null) {
            boolean dbl = e.key().equals(selectedKey) && Util.getMillis() - lastClick < 300;
            lastClick = Util.getMillis();
            select(e);
            if (dbl) join(e);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scroll = Mth.clamp(scroll - delta * ROW, 0, maxScroll());
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        boolean typing = (quick != null && quick.isFocused()) || (search != null && search.isFocused()) || (note != null && note.isFocused());
        if (quick != null && quick.isFocused() && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
            quickConnect();
            return true;
        }
        if (!typing) {
            if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
                int dir = key == GLFW.GLFW_KEY_UP ? -1 : 1;
                if ((mods & GLFW.GLFW_MOD_SHIFT) != 0) move(dir);
                else if (!rows.isEmpty()) {
                    int cur = -1;
                    for (int i = 0; i < rows.size(); i++) if (rows.get(i).key().equals(selectedKey)) cur = i;
                    select(rows.get(Mth.clamp(cur + dir, 0, rows.size() - 1)));
                }
                return true;
            }
            Entry e = selectedEntry();
            if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && e != null) {
                join(e);
                return true;
            }
            if (key == GLFW.GLFW_KEY_F5) {
                refreshAll();
                return true;
            }
            if (key == GLFW.GLFW_KEY_DELETE && e != null && e.ownIndex() >= 0) {
                delete(e);
                return true;
            }
            if (key == GLFW.GLFW_KEY_C && hasControlDown() && e != null) {
                minecraft.keyboardHandler.setClipboard(address(e));
                toast("archud.mp.copied", address(e));
                return true;
            }
            if (key == GLFW.GLFW_KEY_V && hasControlDown()) { // вставить адрес из буфера в быстрое подключение
                quick.setValue(minecraft.keyboardHandler.getClipboard().trim());
                setFocused(quick);
                quick.setFocused(true);
                return true;
            }
        }
        return super.keyPressed(key, scan, mods);
    }

    // ================================================================== закрытие

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

    /** Для автоматических скриншотов: выбрать первый сервер. */
    public void showcaseSelectFirst() {
        List<Entry> r = buildRows();
        if (!r.isEmpty()) {
            selectedKey = r.get(0).key();
            rebuildWidgets();
        }
    }
}
