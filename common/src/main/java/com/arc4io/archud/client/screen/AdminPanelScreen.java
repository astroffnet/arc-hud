package com.arc4io.archud.client.screen;

import com.arc4io.archud.client.ArcClientNet;
import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Theme;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Админ-панель сервера (для операторов, после /login). Вкладки:
 *  Игроки — список и ~40 действий над выбранным; Мир — время, погода, сложность, PvP, правила игры;
 *  Сервер — нагрузка, рассылка, сохранение, дистанции, белый список, AntiESP, остановка;
 *  Списки — баны, IP-баны, белый список, операторы, муты (снять одним кликом); Журнал — действия админов.
 * Состояние присылает сервер (раз в 2 секунды, пока панель открыта); всё выполняется на сервере с проверкой прав.
 */
public class AdminPanelScreen extends ArcScreen {
    private enum Tab {PLAYERS, WORLD, SERVER, LISTS, LOG}

    private static Tab tab = Tab.PLAYERS;
    private static String selected = "";
    private EditBox argBox;
    private double playerScroll, listScroll;
    private int refresh;
    private long lastStateAt;
    private boolean confirmStop;

    /** Действия над игроком: {id, ключ подписи, 1 — опасное}. */
    private static final String[][] PLAYER_ACTIONS = {
            {"p_tpto", "tpto"}, {"p_tphere", "tphere"}, {"p_spectate", "spectate"}, {"p_tpspawn", "tpspawn"},
            {"p_heal", "heal"}, {"p_feed", "feed"}, {"p_extinguish", "extinguish"}, {"p_cleareffects", "cleareffects"},
            {"p_survival", "survival"}, {"p_creative", "creative"}, {"p_adventure", "adventure"}, {"p_spectator", "spectator"},
            {"p_fly", "fly"}, {"p_god", "god"}, {"p_freeze", "freeze"}, {"p_mute", "mute"},
            {"p_speed", "speed"}, {"p_nightvision", "nightvision"}, {"p_invis", "invis"}, {"p_fireres", "fireres"},
            {"p_regen", "regen"}, {"p_xp", "xp"}, {"p_xpreset", "xpreset"}, {"p_spawnpoint", "spawnpoint"},
            {"p_msg", "msg"}, {"p_launch", "launch"}, {"p_smite", "smite"}, {"p_clearinv", "clearinv", "1"},
            {"p_op", "op"}, {"p_deop", "deop"}, {"p_wladd", "wladd"}, {"p_wlremove", "wlremove"},
            {"p_kick", "kick", "1"}, {"p_ban", "ban", "1"}, {"p_banip", "banip", "1"}, {"p_kill", "kill", "1"}};

    private static final String[][] WORLD_ACTIONS = {
            {"w_day", "day"}, {"w_noon", "noon"}, {"w_night", "night"}, {"w_midnight", "midnight"},
            {"w_clear", "clear"}, {"w_rain", "rain"}, {"w_thunder", "thunder"}, {"w_pvp", "pvp"},
            {"w_peaceful", "peaceful"}, {"w_easy", "easy"}, {"w_normal", "normal"}, {"w_hard", "hard"},
            {"w_killmobs", "killmobs", "1"}, {"w_killitems", "killitems"}, {"w_setspawn", "setspawn"}};

    /** Для автоматических скриншотов: вкладка и выбранный игрок. */
    public static void showcase(String tabName, String player) {
        tab = Tab.valueOf(tabName);
        selected = player;
    }

    public AdminPanelScreen(Screen parent) {
        super(Component.translatable("archud.adminp.title"), parent);
    }

    @Override
    protected int minFitWidth() {
        return 560;
    }

    @Override
    protected int minFitHeight() {
        return 320;
    }

    private JsonObject state() {
        return ArcClientNet.adminState();
    }

    @Override
    protected void init() {
        layout(640, 360);
        if (state() == null || refresh == 0) ArcClientNet.adminRequest();
        String arg = argBox != null ? argBox.getValue() : "";
        int tx = px + 10;
        for (Tab t : Tab.values()) {
            Component label = Component.translatable("archud.adminp.tab." + t.name().toLowerCase());
            int w = font.width(label) + 16;
            addRenderableWidget(new ArcButton(tx, py + 26, w, 16, label, b -> {
                tab = t;
                rebuildWidgets();
            }).toggle(() -> tab == t));
            tx += w + 4;
        }
        addDone();
        JsonObject st = state();
        int cx = px + 10, cy = py + 48, cw = pw - 20;
        switch (tab) {
            case PLAYERS -> {
                int rx = px + 170, rw = px + pw - 10 - rx;
                argBox = addRenderableWidget(new EditBox(font, rx, cy + 38, rw, 14, Component.translatable("archud.adminp.arg")));
                argBox.setHint(Component.translatable("archud.adminp.arg_hint"));
                argBox.setMaxLength(200);
                argBox.setValue(arg);
                grid(PLAYER_ACTIONS, rx, cy + 56, rw, 4, "archud.adminp.p.", true);
            }
            case WORLD -> {
                int gw = cw;
                grid(WORLD_ACTIONS, cx, cy, gw, 4, "archud.adminp.w.", false);
                int ry = cy + 4 * 20 + 18;
                if (st != null && st.has("world")) {
                    JsonObject rules = st.getAsJsonObject("world").getAsJsonObject("rules");
                    List<String> names = new ArrayList<>(rules.keySet());
                    int cols = 4, bw = (gw - (cols - 1) * 4) / cols, i = 0;
                    for (String r : names) {
                        int x = cx + (i % cols) * (bw + 4), y = ry + (i / cols) * 18;
                        if (y + 16 > py + ph - 30) break;
                        addRenderableWidget(new ArcButton(x, y, bw, 16, Component.literal(r), b -> act("w_rule", "", r))
                                .toggle(() -> ruleOn(r)));
                        i++;
                    }
                }
            }
            case SERVER -> {
                argBox = addRenderableWidget(new EditBox(font, cx, cy + 74, cw - 104, 14, Component.translatable("archud.adminp.broadcast")));
                argBox.setHint(Component.translatable("archud.adminp.broadcast_hint"));
                argBox.setMaxLength(200);
                argBox.setValue(arg);
                addRenderableWidget(new ArcButton(cx + cw - 100, cy + 72, 100, 18, Component.translatable("archud.adminp.s.broadcast"),
                        b -> act("s_broadcast", "", argBox.getValue())));
                String[][] acts = {{"s_save", "save"}, {"s_whitelist", "whitelist"}, {"s_reload", "reload"}, {"s_healall", "healall"},
                        {"s_view_down", "view_down"}, {"s_view_up", "view_up"}, {"s_sim_down", "sim_down"}, {"s_sim_up", "sim_up"},
                        {"s_antiesp", "antiesp"}, {"s_kicknonops", "kicknonops", "1"}};
                grid(acts, cx, cy + 96, cw, 4, "archud.adminp.s.", false);
                ArcButton stop = new ArcButton(cx, cy + 96 + 3 * 20 + 6, 160, 18,
                        Component.translatable(confirmStop ? "archud.adminp.s.stop_confirm" : "archud.adminp.s.stop"), b -> {
                    if (confirmStop) act("s_stop", "", "");
                    confirmStop = !confirmStop;
                    rebuildWidgets();
                }).danger();
                addRenderableWidget(stop);
            }
            default -> {
            }
        }
    }

    private boolean ruleOn(String r) {
        JsonObject st = state();
        if (st == null) return false;
        JsonObject rules = st.getAsJsonObject("world").getAsJsonObject("rules");
        return rules.has(r) && rules.get(r).getAsBoolean();
    }

    /** Сетка кнопок действий. */
    private void grid(String[][] acts, int x, int y, int w, int cols, String keyPrefix, boolean needPlayer) {
        int bw = (w - (cols - 1) * 4) / cols;
        for (int i = 0; i < acts.length; i++) {
            String[] a = acts[i];
            int bx = x + (i % cols) * (bw + 4), by = y + (i / cols) * 20;
            if (by + 18 > py + ph - 28) break;
            ArcButton b = new ArcButton(bx, by, bw, 18, Component.translatable(keyPrefix + a[1]),
                    btn -> act(a[0], needPlayer ? selected : "", argBox != null ? argBox.getValue() : ""));
            if (a.length > 2) b.danger();
            if (needPlayer) b.active = !selected.isEmpty();
            String toggle = switch (a[0]) {
                case "p_fly" -> "fly";
                case "p_god" -> "god";
                case "p_freeze" -> "frozen";
                case "p_mute" -> "muted";
                default -> null;
            };
            if (toggle != null) b.toggle(() -> playerFlag(selected, toggle));
            if (a[0].equals("w_pvp")) b.toggle(() -> serverFlag("pvp"));
            if (a[0].equals("s_whitelist")) b.toggle(() -> serverFlag("whitelist"));
            if (a[0].equals("s_antiesp")) b.toggle(() -> serverFlag("antiEsp"));
            addRenderableWidget(b);
        }
    }

    private void act(String action, String target, String arg) {
        ArcClientNet.adminAction(action, target, arg);
    }

    private boolean serverFlag(String key) {
        JsonObject st = state();
        return st != null && st.getAsJsonObject("server").has(key) && st.getAsJsonObject("server").get(key).getAsBoolean();
    }

    private JsonObject player(String name) {
        JsonObject st = state();
        if (st == null || name.isEmpty()) return null;
        for (JsonElement e : st.getAsJsonArray("players")) {
            JsonObject p = e.getAsJsonObject();
            if (p.get("name").getAsString().equals(name)) return p;
        }
        return null;
    }

    private boolean playerFlag(String name, String key) {
        JsonObject p = player(name);
        return p != null && p.has(key) && p.get(key).getAsBoolean();
    }

    @Override
    public void tick() {
        if (++refresh % 40 == 0) ArcClientNet.adminRequest();
        if (ArcClientNet.adminStateAt() != lastStateAt) {
            boolean first = lastStateAt == 0;
            lastStateAt = ArcClientNet.adminStateAt();
            if (first || tab == Tab.WORLD) rebuildWidgets(); // правила игры появились / изменились
        }
        if (!ArcClientNet.adminAllowed()) onClose();
    }

    // ------------------------------------------------------------------ отрисовка
    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        frame(g);
        int accent = Theme.accent();
        JsonObject st = state();
        if (st != null) {
            JsonObject s = st.getAsJsonObject("server");
            String head = s.get("tps").getAsString() + " TPS · " + s.get("mspt").getAsString() + " ms · " + s.get("players").getAsInt() + "/"
                    + s.get("maxPlayers").getAsInt();
            Draw.small(g, font, head, px + pw - 10 - font.width(head) * 0.7F, py + 10, s.get("tps").getAsDouble() >= 18 ? 0xFF9CFF5A : 0xFFFFB74D, 0.7F);
        } else {
            g.drawCenteredString(font, Component.translatable("archud.adminp.loading"), px + pw / 2, py + ph / 2, Draw.TEXT_DIM);
        }
        if (st != null) {
            switch (tab) {
                case PLAYERS -> renderPlayers(g, st, mouseX, mouseY, accent);
                case WORLD -> {
                    JsonObject w = st.getAsJsonObject("world");
                    long t = w.get("time").getAsLong();
                    String info = Component.translatable("archud.adminp.world_info", w.get("day").getAsLong(),
                            String.format("%02d:%02d", ((t + 6000) % 24000) / 1000, ((t + 6000) % 1000) * 60 / 1000),
                            Component.translatable("archud.adminp.weather." + w.get("weather").getAsString()),
                            st.getAsJsonObject("server").get("difficulty").getAsString()).getString();
                    Draw.small(g, font, info, px + 10, py + 48 + 4 * 20 + 4, Draw.TEXT_DIM, 0.7F);
                }
                case SERVER -> renderServer(g, st);
                case LISTS -> renderLists(g, st, mouseX, mouseY, accent);
                case LOG -> renderLog(g, st);
            }
        }
        String res = ArcClientNet.adminResult();
        if (!res.isEmpty()) g.drawCenteredString(font, res, px + pw / 2, py + ph - 19, 0xFF9CFF5A);
        super.render(g, mouseX, mouseY, partialTick);
    }

    private List<JsonObject> players(JsonObject st) {
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement e : st.getAsJsonArray("players")) out.add(e.getAsJsonObject());
        return out;
    }

    private void renderPlayers(GuiGraphics g, JsonObject st, int mouseX, int mouseY, int accent) {
        List<JsonObject> list = players(st);
        int x = px + 10, top = py + 48, bottom = py + ph - 30, w = 152;
        playerScroll = Mth.clamp(playerScroll, 0, Math.max(0, list.size() * 22 - (bottom - top)));
        Draw.panel(g, x - 2, top - 2, w + 4, bottom - top + 4, 0x40000000, 0x30FFFFFF);
        g.enableScissor(x, top, x + w, bottom);
        int y = top - (int) playerScroll;
        for (JsonObject p : list) {
            String name = p.get("name").getAsString();
            boolean sel = name.equals(selected);
            boolean hov = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 21 && mouseY >= top && mouseY < bottom;
            g.fill(x, y, x + w, y + 21, sel ? Draw.alpha(accent, 0x50) : hov ? 0x26FFFFFF : 0x0CFFFFFF);
            if (sel) g.fill(x, y, x + 2, y + 21, Draw.alpha(accent, 0xFF));
            face(g, UUID.fromString(p.get("uuid").getAsString()), x + 4, y + 3, 15);
            int op = p.get("op").getAsInt();
            g.drawString(font, (op > 0 ? "⚑ " : "") + name, x + 23, y + 2, op > 0 ? 0xFFFFD54F : Draw.TEXT, true);
            Draw.small(g, font, Component.translatable("gameMode." + p.get("mode").getAsString()).getString() + " · ❤ " + p.get("health").getAsInt()
                    + " · " + p.get("ping").getAsInt() + " ms", x + 23, y + 12, Draw.TEXT_DIM, 0.58F);
            y += 22;
        }
        g.disableScissor();
        // карточка выбранного
        JsonObject p = player(selected);
        int rx = px + 170, ry = py + 48;
        if (p == null) {
            Draw.small(g, font, Component.translatable("archud.adminp.select").getString(), rx, ry + 4, Draw.TEXT_DIM, 0.8F);
            return;
        }
        g.drawString(font, p.get("name").getAsString(), rx, ry, Draw.alpha(accent, 0xFF), true);
        String l1 = "❤ " + p.get("health").getAsInt() + "   food " + p.get("food").getAsInt() + "   XP " + p.get("xp").getAsInt()
                + "   " + Component.translatable("archud.adminp.op_level", p.get("op").getAsInt()).getString();
        String dim = p.get("dim").getAsString();
        String l2 = dim.substring(dim.indexOf(':') + 1) + "  " + p.get("x").getAsInt() + " " + p.get("y").getAsInt() + " " + p.get("z").getAsInt()
                + "   IP " + p.get("ip").getAsString() + (p.get("arc").getAsBoolean() ? "   Arc HUD ✔" : "");
        Draw.small(g, font, l1, rx, ry + 11, Draw.TEXT_DIM, 0.68F);
        Draw.small(g, font, l2, rx, ry + 20, Draw.TEXT_DIM, 0.68F);
    }

    private void face(GuiGraphics g, UUID id, int x, int y, int size) {
        ResourceLocation skin = null;
        if (minecraft.getConnection() != null) {
            PlayerInfo info = minecraft.getConnection().getPlayerInfo(id);
            if (info != null) skin = info.getSkinLocation();
        }
        if (skin == null) skin = net.minecraft.client.resources.DefaultPlayerSkin.getDefaultSkin(id);
        PlayerFaceRenderer.draw(g, skin, x, y, size);
    }

    private void renderServer(GuiGraphics g, JsonObject st) {
        JsonObject s = st.getAsJsonObject("server");
        int x = px + 10, y = py + 48;
        long up = s.get("uptime").getAsLong();
        String[] lines = {
                s.get("brand").getAsString() + " " + s.get("version").getAsString() + "   ·   " + s.get("motd").getAsString(),
                "TPS " + s.get("tps").getAsString() + "   MSPT " + s.get("mspt").getAsString() + "   RAM " + s.get("memUsed").getAsLong() + " / "
                        + s.get("memMax").getAsLong() + " MB   " + Component.translatable("archud.adminp.uptime", up / 3600, (up / 60) % 60).getString(),
                Component.translatable("archud.adminp.distances", s.get("viewDistance").getAsInt(), s.get("simDistance").getAsInt()).getString()
                        + "   ·   " + Component.translatable("archud.adminp.your_level", s.get("level").getAsInt()).getString()};
        for (int i = 0; i < lines.length; i++) Draw.small(g, font, lines[i], x, y + i * 11, i == 1 ? Draw.TEXT : Draw.TEXT_DIM, 0.75F);
        long used = s.get("memUsed").getAsLong(), max = Math.max(1, s.get("memMax").getAsLong());
        float m = used / (float) max;
        g.fill(x, y + 38, x + pw - 20, y + 42, 0x40FFFFFF);
        g.fill(x, y + 38, x + Math.round((pw - 20) * m), y + 42, m > 0.85F ? 0xFFFF6E6E : m > 0.65F ? 0xFFFFD54F : 0xFF9CFF5A);
        double tps = s.get("tps").getAsDouble();
        g.fill(x, y + 46, x + pw - 20, y + 50, 0x40FFFFFF);
        g.fill(x, y + 46, x + (int) Math.round((pw - 20) * Math.min(1, tps / 20)), y + 50, tps >= 18 ? 0xFF9CFF5A : tps >= 12 ? 0xFFFFD54F : 0xFFFF6E6E);
    }

    /** Списки: {ключ JSON, действие снятия, подпись}. */
    private static final String[][] LISTS = {{"bans", "p_unban", "bans"}, {"ipBans", "p_unbanip", "ipbans"}, {"whitelistNames", "p_wlremove", "whitelist"},
            {"ops", "p_deop", "ops"}, {"mutedNames", "p_mute", "muted"}};

    private void renderLists(GuiGraphics g, JsonObject st, int mouseX, int mouseY, int accent) {
        int n = LISTS.length, gap = 6, cw = (pw - 20 - gap * (n - 1)) / n;
        int top = py + 60, bottom = py + ph - 30;
        for (int i = 0; i < n; i++) {
            int x = px + 10 + i * (cw + gap);
            JsonArray arr = st.getAsJsonArray(LISTS[i][0]);
            g.drawString(font, Component.translatable("archud.adminp.list." + LISTS[i][2]).getString() + " (" + arr.size() + ")", x, py + 48, Draw.TEXT, true);
            Draw.panel(g, x - 1, top - 1, cw + 2, bottom - top + 2, 0x40000000, 0x30FFFFFF);
            g.enableScissor(x, top, x + cw, bottom);
            int y = top - (int) listScroll;
            for (JsonElement e : arr) {
                String v = e.getAsString();
                boolean hov = mouseX >= x && mouseX < x + cw && mouseY >= y && mouseY < y + 12 && mouseY >= top && mouseY < bottom;
                if (hov) g.fill(x, y, x + cw, y + 12, 0x26FFFFFF);
                String t = v;
                if (font.width(t) * 0.7F > cw - 14) t = font.plainSubstrByWidth(t, (int) ((cw - 18) / 0.7F)) + "…";
                Draw.small(g, font, t, x + 3, y + 2.5F, Draw.TEXT, 0.7F);
                if (hov) Draw.small(g, font, "✕", x + cw - 9, y + 2, 0xFFFF6E6E, 0.8F);
                y += 13;
            }
            g.disableScissor();
        }
        Draw.small(g, font, Component.translatable("archud.adminp.list_hint").getString(), px + 10, bottom + 3, Draw.TEXT_DIM, 0.6F);
    }

    private void renderLog(GuiGraphics g, JsonObject st) {
        JsonArray log = st.getAsJsonArray("log");
        int y = py + 48;
        if (log.isEmpty()) Draw.small(g, font, Component.translatable("archud.adminp.log_empty").getString(), px + 10, y, Draw.TEXT_DIM, 0.75F);
        for (JsonElement e : log) {
            if (y > py + ph - 34) break;
            Draw.small(g, font, e.getAsString(), px + 10, y, Draw.TEXT, 0.7F);
            y += 9;
        }
    }

    // ------------------------------------------------------------------ ввод
    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        JsonObject st = state();
        if (st == null) return false;
        if (tab == Tab.PLAYERS) {
            int x = px + 10, top = py + 48, bottom = py + ph - 30;
            if (mx >= x && mx < x + 152 && my >= top && my < bottom) {
                int idx = (int) ((my - top + playerScroll) / 22);
                List<JsonObject> list = players(st);
                if (idx >= 0 && idx < list.size()) {
                    selected = list.get(idx).get("name").getAsString();
                    rebuildWidgets();
                }
                return true;
            }
        }
        if (tab == Tab.LISTS) {
            int n = LISTS.length, gap = 6, cw = (pw - 20 - gap * (n - 1)) / n;
            int top = py + 60, bottom = py + ph - 30;
            for (int i = 0; i < n; i++) {
                int x = px + 10 + i * (cw + gap);
                if (mx < x || mx >= x + cw || my < top || my >= bottom) continue;
                int idx = (int) ((my - top + listScroll) / 13);
                JsonArray arr = st.getAsJsonArray(LISTS[i][0]);
                if (idx >= 0 && idx < arr.size()) {
                    String v = arr.get(idx).getAsString();
                    if (LISTS[i][1].equals("p_unbanip")) act("p_unbanip", "_", v);
                    else act(LISTS[i][1], v, "");
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (tab == Tab.PLAYERS && mx < px + 166) playerScroll -= delta * 22;
        else listScroll = Math.max(0, listScroll - delta * 13);
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
