package com.arc4io.archud.client.screen;

import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Theme;
import com.arc4io.archud.mixin.IntegratedServerAccessor;
import com.arc4io.archud.mixin.ServerConnectionListenerAccessor;
import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.server.LanServerPinger;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.UserBanListEntry;
import net.minecraft.server.players.UserWhiteListEntry;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Панель хоста для игры «Открыть для сети».
 * Игроки: кик, бан, оператор, режим игры, телепорт к игроку / призвать, вылечить.
 * Мир: время, погода, сложность, игровые правила, PvP, сообщение всем, сохранение.
 * Сеть: адрес для друзей, белый список, список банов, закрыть игру для сети.
 */
public class HostPanelScreen extends ArcScreen {
    private enum Tab {PLAYERS, WORLD, NETWORK}

    private record PRow(UUID id, String name, GameType mode, boolean op, boolean host, float hp, float maxHp, String dim, int x, int y, int z) {}

    private record Toggle(ArcButton button, Supplier<Component> label) {}

    private static Tab tab = Tab.PLAYERS;
    private volatile List<PRow> players = List.of();
    private volatile List<String> bans = List.of();
    private volatile boolean[] flags = new boolean[12];
    private volatile Difficulty difficulty = Difficulty.NORMAL;
    private UUID selected;
    private int refresh;
    private final List<Toggle> toggles = new ArrayList<>();
    private EditBox message;
    private String status = "";
    private long statusTime;

    // индексы flags
    private static final int F_KEEP = 0, F_GRIEF = 1, F_FIRE = 2, F_SPAWN = 3, F_WEATHER = 4, F_DAYLIGHT = 5, F_PVP = 6, F_WHITELIST = 7,
            F_DEATHMSG = 8, F_RESPAWN = 9, F_PUBLISHED = 10;

    public HostPanelScreen(Screen parent) {
        super(Component.translatable("archud.admin.title"), parent);
    }

    public static boolean available() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        return mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null;
    }

    private IntegratedServer server() {
        return minecraft.getSingleplayerServer();
    }

    // ------------------------------------------------------------------ данные (собираются на серверном потоке)
    private void snapshot() {
        IntegratedServer s = server();
        if (s == null) return;
        s.execute(() -> {
            List<PRow> list = new ArrayList<>();
            for (ServerPlayer p : s.getPlayerList().getPlayers()) {
                list.add(new PRow(p.getUUID(), p.getGameProfile().getName(), p.gameMode.getGameModeForPlayer(),
                        s.getPlayerList().isOp(p.getGameProfile()), s.isSingleplayerOwner(p.getGameProfile()), p.getHealth(), p.getMaxHealth(),
                        p.level().dimension().location().getPath(), p.getBlockX(), p.getBlockY(), p.getBlockZ()));
            }
            list.sort((a, b) -> a.host() != b.host() ? (a.host() ? -1 : 1) : a.name().compareToIgnoreCase(b.name()));
            players = list;
            List<String> b = new ArrayList<>();
            Collections.addAll(b, s.getPlayerList().getBans().getUserList());
            bans = b;
            GameRules r = s.getGameRules();
            boolean[] f = new boolean[12];
            f[F_KEEP] = r.getBoolean(GameRules.RULE_KEEPINVENTORY);
            f[F_GRIEF] = r.getBoolean(GameRules.RULE_MOBGRIEFING);
            f[F_FIRE] = r.getBoolean(GameRules.RULE_DOFIRETICK);
            f[F_SPAWN] = r.getBoolean(GameRules.RULE_DOMOBSPAWNING);
            f[F_WEATHER] = r.getBoolean(GameRules.RULE_WEATHER_CYCLE);
            f[F_DAYLIGHT] = r.getBoolean(GameRules.RULE_DAYLIGHT);
            f[F_PVP] = s.isPvpAllowed();
            f[F_WHITELIST] = s.getPlayerList().isUsingWhitelist();
            f[F_DEATHMSG] = r.getBoolean(GameRules.RULE_SHOWDEATHMESSAGES);
            f[F_RESPAWN] = r.getBoolean(GameRules.RULE_DO_IMMEDIATE_RESPAWN);
            f[F_PUBLISHED] = s.isPublished();
            flags = f;
            difficulty = s.getWorldData().getDifficulty();
        });
    }

    /** Выполнить на серверном потоке и обновить данные. */
    private void run(String done, java.util.function.Consumer<IntegratedServer> action) {
        IntegratedServer s = server();
        if (s == null) return;
        s.execute(() -> {
            try {
                action.accept(s);
            } catch (Throwable t) {
                com.arc4io.archud.ArcHud.LOGGER.warn("Arc HUD: host panel action failed", t);
            }
        });
        if (done != null) {
            status = Component.translatable(done).getString();
            statusTime = System.currentTimeMillis();
        }
        snapshot();
    }

    private ServerPlayer target(IntegratedServer s) {
        return selected == null ? null : s.getPlayerList().getPlayer(selected);
    }

    private PRow selectedRow() {
        for (PRow r : players) if (r.id().equals(selected)) return r;
        return null;
    }

    // ------------------------------------------------------------------ интерфейс
    @Override
    protected void init() {
        layout(420, 260);
        toggles.clear();
        if (server() != null) flags[F_PUBLISHED] = server().isPublished();
        snapshot();
        int tw = (pw - 20 - 8) / 3;
        int tx = px + 10;
        for (Tab t : Tab.values()) {
            ArcButton b = addRenderableWidget(new ArcButton(tx, py + 26, tw, 16,
                    Component.translatable("archud.admin.tab." + t.name().toLowerCase(Locale.ROOT)), btn -> {
                tab = t;
                rebuildWidgets();
            }));
            b.active = tab != t;
            tx += tw + 4;
        }
        switch (tab) {
            case PLAYERS -> initPlayers();
            case WORLD -> initWorld();
            case NETWORK -> initNetwork();
        }
        addBack();
    }

    private Component onOff(String key, int flag) {
        boolean on = flags[flag];
        return Component.translatable(key).append(": ").append(Component.translatable(on ? "options.on" : "options.off")
                .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    private void toggle(int x, int y, int w, String key, int flag, java.util.function.BiConsumer<IntegratedServer, Boolean> setter) {
        ArcButton b = addRenderableWidget(new ArcButton(x, y, w, 18, onOff(key, flag), btn -> {
            boolean v = !flags[flag];
            flags[flag] = v;
            run(null, s -> setter.accept(s, v));
        }));
        toggles.add(new Toggle(b, () -> onOff(key, flag)));
    }

    private void initPlayers() {
        int rx = px + 160, w = pw - 170, y = py + 50;
        int half = (w - 4) / 2;
        addRenderableWidget(new ArcButton(rx, y, half, 18, Component.translatable("archud.admin.kick"), b -> kick(false)).danger());
        addRenderableWidget(new ArcButton(rx + half + 4, y, w - half - 4, 18, Component.translatable("archud.admin.ban"), b -> kick(true)).danger());
        y += 22;
        addRenderableWidget(new ArcButton(rx, y, half, 18, Component.translatable("archud.admin.op"), b -> run("archud.admin.done", s -> {
            ServerPlayer p = target(s);
            if (p == null) return;
            if (s.getPlayerList().isOp(p.getGameProfile())) s.getPlayerList().deop(p.getGameProfile());
            else s.getPlayerList().op(p.getGameProfile());
        })));
        addRenderableWidget(new ArcButton(rx + half + 4, y, w - half - 4, 18, Component.translatable("archud.admin.gamemode"), b -> run("archud.admin.done", s -> {
            ServerPlayer p = target(s);
            if (p == null) return;
            GameType next = switch (p.gameMode.getGameModeForPlayer()) {
                case SURVIVAL -> GameType.CREATIVE;
                case CREATIVE -> GameType.ADVENTURE;
                case ADVENTURE -> GameType.SPECTATOR;
                case SPECTATOR -> GameType.SURVIVAL;
            };
            p.setGameMode(next);
        })));
        y += 22;
        addRenderableWidget(new ArcButton(rx, y, half, 18, Component.translatable("archud.admin.tp_to"), b -> run("archud.admin.done", s -> {
            ServerPlayer p = target(s), me = host(s);
            if (p == null || me == null || p == me) return;
            me.teleportTo(p.serverLevel(), p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());
        })));
        addRenderableWidget(new ArcButton(rx + half + 4, y, w - half - 4, 18, Component.translatable("archud.admin.summon"), b -> run("archud.admin.done", s -> {
            ServerPlayer p = target(s), me = host(s);
            if (p == null || me == null || p == me) return;
            p.teleportTo(me.serverLevel(), me.getX(), me.getY(), me.getZ(), me.getYRot(), me.getXRot());
        })));
        y += 22;
        addRenderableWidget(new ArcButton(rx, y, half, 18, Component.translatable("archud.admin.heal"), b -> run("archud.admin.done", s -> {
            ServerPlayer p = target(s);
            if (p == null) return;
            p.setHealth(p.getMaxHealth());
            p.getFoodData().setFoodLevel(20);
            p.getFoodData().setSaturation(20F);
            p.setAirSupply(p.getMaxAirSupply());
            p.clearFire();
        })));
        addRenderableWidget(new ArcButton(rx + half + 4, y, w - half - 4, 18, Component.translatable("archud.admin.heal_all"), b -> run("archud.admin.done", s -> {
            for (ServerPlayer p : s.getPlayerList().getPlayers()) {
                p.setHealth(p.getMaxHealth());
                p.getFoodData().setFoodLevel(20);
                p.getFoodData().setSaturation(20F);
            }
        })));
    }

    private ServerPlayer host(IntegratedServer s) {
        return minecraft.player == null ? null : s.getPlayerList().getPlayer(minecraft.player.getUUID());
    }

    private void kick(boolean ban) {
        PRow row = selectedRow();
        if (row == null || row.host()) return;
        String reason = Component.translatable(ban ? "multiplayer.disconnect.banned" : "multiplayer.disconnect.kicked").getString();
        run(ban ? "archud.admin.banned" : "archud.admin.kicked", s -> {
            ServerPlayer p = target(s);
            if (p == null) return;
            if (ban) {
                GameProfile profile = p.getGameProfile();
                s.getPlayerList().getBans().add(new UserBanListEntry(profile, null, "Arc HUD", null, reason));
            }
            p.connection.disconnect(Component.literal(reason));
        });
        selected = null;
    }

    private void initWorld() {
        int x = px + 10, w = pw - 20, y = py + 50;
        int q = (w - 12) / 4;
        String[] times = {"morning", "day", "evening", "night"};
        int[] ticks = {0, 6000, 12000, 18000};
        for (int i = 0; i < 4; i++) {
            int t = ticks[i];
            addRenderableWidget(new ArcButton(x + i * (q + 4), y, q, 18, Component.translatable("archud.admin.time." + times[i]), b ->
                    run("archud.admin.done", s -> {
                        for (ServerLevel lvl : s.getAllLevels()) {
                            long day = lvl.getDayTime() / 24000L;
                            lvl.setDayTime(day * 24000L + t + (lvl.getDayTime() % 24000L > t ? 24000L : 0));
                        }
                    })));
        }
        y += 22;
        String[] weathers = {"clear", "rain", "thunder"};
        int third = (w - 8) / 3;
        for (int i = 0; i < 3; i++) {
            int k = i;
            addRenderableWidget(new ArcButton(x + i * (third + 4), y, third, 18, Component.translatable("archud.admin.weather." + weathers[i]), b ->
                    run("archud.admin.done", s -> s.overworld().setWeatherParameters(k == 0 ? 12000 : 0, k == 0 ? 0 : 6000, k > 0, k == 2))));
        }
        y += 22;
        int half = (w - 4) / 2;
        ArcButton diff = addRenderableWidget(new ArcButton(x, y, half, 18, difficultyLabel(), b -> {
            Difficulty next = Difficulty.byId((difficulty.getId() + 1) % 4);
            difficulty = next;
            run(null, s -> s.setDifficulty(next, true));
        }));
        toggles.add(new Toggle(diff, this::difficultyLabel));
        toggle(x + half + 4, y, w - half - 4, "archud.admin.pvp", F_PVP, (s, v) -> s.setPvpAllowed(v));
        y += 22;
        toggle(x, y, half, "archud.admin.rule.keep_inventory", F_KEEP, (s, v) -> s.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(v, s));
        toggle(x + half + 4, y, w - half - 4, "archud.admin.rule.mob_griefing", F_GRIEF, (s, v) -> s.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(v, s));
        y += 22;
        toggle(x, y, half, "archud.admin.rule.fire_tick", F_FIRE, (s, v) -> s.getGameRules().getRule(GameRules.RULE_DOFIRETICK).set(v, s));
        toggle(x + half + 4, y, w - half - 4, "archud.admin.rule.mob_spawning", F_SPAWN, (s, v) -> s.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(v, s));
        y += 22;
        toggle(x, y, half, "archud.admin.rule.daylight", F_DAYLIGHT, (s, v) -> s.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(v, s));
        toggle(x + half + 4, y, w - half - 4, "archud.admin.rule.weather", F_WEATHER, (s, v) -> s.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(v, s));
        y += 22;
        toggle(x, y, half, "archud.admin.rule.death_messages", F_DEATHMSG, (s, v) -> s.getGameRules().getRule(GameRules.RULE_SHOWDEATHMESSAGES).set(v, s));
        toggle(x + half + 4, y, w - half - 4, "archud.admin.rule.instant_respawn", F_RESPAWN,
                (s, v) -> s.getGameRules().getRule(GameRules.RULE_DO_IMMEDIATE_RESPAWN).set(v, s));
        y += 24;
        message = addRenderableWidget(new EditBox(font, x, y + 1, w - 150, 16, Component.translatable("archud.admin.broadcast")));
        message.setMaxLength(200);
        message.setHint(Component.translatable("archud.admin.broadcast"));
        addRenderableWidget(new ArcButton(x + w - 146, y, 70, 18, Component.translatable("archud.admin.send"), b -> broadcast()));
        addRenderableWidget(new ArcButton(x + w - 72, y, 72, 18, Component.translatable("archud.admin.save"), b ->
                run("archud.admin.saved", s -> s.saveEverything(false, true, true))));
    }

    private Component difficultyLabel() {
        return Component.translatable("options.difficulty").append(": ").append(difficulty.getDisplayName());
    }

    private void broadcast() {
        if (message == null || message.getValue().isBlank()) return;
        String text = message.getValue().trim();
        String prefix = Component.translatable("archud.admin.host_prefix").getString();
        message.setValue("");
        run("archud.admin.sent", s -> s.getPlayerList().broadcastSystemMessage(
                Component.literal(prefix + " ").withStyle(ChatFormatting.GOLD).append(Component.literal(text).withStyle(ChatFormatting.WHITE)), false));
    }

    private void initNetwork() {
        int x = px + 10, w = pw - 20, y = py + 70;
        int half = (w - 4) / 2;
        if (flags[F_PUBLISHED]) {
            addRenderableWidget(new ArcButton(x, y, half, 18, Component.translatable("archud.admin.copy_address"), b -> {
                minecraft.keyboardHandler.setClipboard(address());
                status = Component.translatable("archud.admin.copied").getString();
                statusTime = System.currentTimeMillis();
            }));
            addRenderableWidget(new ArcButton(x + half + 4, y, w - half - 4, 18, Component.translatable("archud.admin.close_lan"), b -> closeLan()).danger());
        } else {
            addRenderableWidget(new ArcButton(x, y, w, 18, Component.translatable("menu.shareToLan"),
                    b -> minecraft.setScreen(new net.minecraft.client.gui.screens.ShareToLanScreen(this))));
        }
        y += 22;
        toggle(x, y, half, "archud.admin.whitelist", F_WHITELIST, (s, v) -> s.getPlayerList().setUsingWhiteList(v));
        addRenderableWidget(new ArcButton(x + half + 4, y, w - half - 4, 18, Component.translatable("archud.admin.whitelist_online"), b ->
                run("archud.admin.done", s -> {
                    for (ServerPlayer p : s.getPlayerList().getPlayers()) {
                        if (!s.getPlayerList().isWhiteListed(p.getGameProfile())) s.getPlayerList().getWhiteList().add(new UserWhiteListEntry(p.getGameProfile()));
                    }
                })));
        y += 30;
        // баны: по кнопке «Разбанить» на строку
        int row = 0;
        for (String name : bans) {
            if (row >= 4) break;
            int ry = y + 12 + row * 20;
            addRenderableWidget(new ArcButton(x + w - 90, ry, 90, 16, Component.translatable("archud.admin.unban"), b -> run("archud.admin.done", s ->
            {
                if (s.getProfileCache() != null) s.getProfileCache().get(name).ifPresent(p -> s.getPlayerList().getBans().remove(p));
            })));
            row++;
        }
    }

    private String address() {
        IntegratedServer s = server();
        int port = s == null ? 0 : ((IntegratedServerAccessor) s).archud$getPublishedPort();
        String ip = "127.0.0.1";
        try {
            outer:
            for (Iterator<NetworkInterface> it = NetworkInterface.getNetworkInterfaces().asIterator(); it.hasNext(); ) {
                NetworkInterface ni = it.next();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (Iterator<InetAddress> ia = ni.getInetAddresses().asIterator(); ia.hasNext(); ) {
                    InetAddress a = ia.next();
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        ip = a.getHostAddress();
                        break outer;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return ip + ":" + port;
    }

    /** Закрыть игру для сети: перестать принимать подключения, отключить гостей, убрать из списка LAN. */
    private void closeLan() {
        String msg = Component.translatable("archud.admin.lan_closed_kick").getString();
        UUID me = minecraft.player == null ? null : minecraft.player.getUUID();
        run("archud.admin.lan_closed", s -> {
            IntegratedServerAccessor acc = (IntegratedServerAccessor) s;
            LanServerPinger pinger = acc.archud$getLanPinger();
            if (pinger != null) pinger.interrupt();
            acc.archud$setLanPinger(null);
            acc.archud$setPublishedPort(-1);
            if (s.getConnection() != null) {
                List<ChannelFuture> channels = ((ServerConnectionListenerAccessor) s.getConnection()).archud$getChannels();
                synchronized (channels) {
                    channels.removeIf(f -> {
                        if (f.channel() instanceof io.netty.channel.local.LocalServerChannel) return false; // своё подключение не трогаем
                        try {
                            f.channel().close().syncUninterruptibly();
                        } catch (Exception ignored) {
                        }
                        return true;
                    });
                }
            }
            for (ServerPlayer p : new ArrayList<>(s.getPlayerList().getPlayers())) {
                if (!p.getUUID().equals(me) && !s.isSingleplayerOwner(p.getGameProfile())) p.connection.disconnect(Component.literal(msg));
            }
        });
        flags[F_PUBLISHED] = false;
        rebuildWidgets();
    }

    @Override
    public void tick() {
        if (server() == null) {
            onClose();
            return;
        }
        if (++refresh % 10 == 0) snapshot();
        for (Toggle t : toggles) t.button().setMessage(t.label().get());
        int state = (flags[F_PUBLISHED] ? 1 : 0) + 31 * bans.hashCode();
        if (tab == Tab.NETWORK && state != lastNetState) {
            lastNetState = state;
            rebuildWidgets();
        }
    }

    private int lastNetState;

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (tab == Tab.PLAYERS && mx >= px + 10 && mx < px + 150 && my >= py + 50) {
            int i = (int) ((my - py - 50) / 20);
            if (i >= 0 && i < players.size()) {
                selected = players.get(i).id();
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        frame(g);
        int accent = Theme.accent();
        IntegratedServer s = server();
        String head = s != null && flags[F_PUBLISHED]
                ? Component.translatable("archud.admin.open", ((IntegratedServerAccessor) s).archud$getPublishedPort(), players.size()).getString()
                : Component.translatable("archud.admin.closed").getString();
        Draw.small(g, font, head, px + pw - 10 - font.width(head) * 0.7F, py + 10, flags[F_PUBLISHED] ? 0xFF9CFF5A : 0xFFFFB74D, 0.7F);
        switch (tab) {
            case PLAYERS -> renderPlayers(g, mouseX, mouseY, accent);
            case NETWORK -> renderNetwork(g);
            default -> {
            }
        }
        if (!status.isEmpty() && System.currentTimeMillis() - statusTime < 3000) {
            g.drawCenteredString(font, status, px + pw / 2, py + ph - 19, 0xFF9CFF5A);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void renderPlayers(GuiGraphics g, int mouseX, int mouseY, int accent) {
        int x = px + 10, y = py + 50;
        for (PRow r : players) {
            if (y > py + ph - 50) break;
            boolean sel = r.id().equals(selected);
            boolean hov = mouseX >= x && mouseX < x + 140 && mouseY >= y && mouseY < y + 19;
            g.fill(x, y, x + 140, y + 19, sel ? Draw.alpha(accent, 0x50) : hov ? 0x20FFFFFF : 0x14FFFFFF);
            if (sel) g.fill(x, y, x + 2, y + 19, Draw.alpha(accent, 0xFF));
            String name = (r.host() ? "★ " : r.op() ? "⚑ " : "") + r.name();
            g.drawString(font, name, x + 5, y + 2, r.host() ? 0xFFFFD54F : Draw.TEXT, true);
            PlayerInfo info = minecraft.getConnection() == null ? null : minecraft.getConnection().getPlayerInfo(r.id());
            String ping = info == null ? "" : info.getLatency() + " ms";
            Draw.small(g, font, Component.translatable("gameMode." + r.mode().getName()).getString() + " · " + ping, x + 5, y + 12, Draw.TEXT_DIM, 0.55F);
            y += 20;
        }
        PRow sel = selectedRow();
        int rx = px + 160;
        int iy = py + 142;
        if (sel == null) {
            Draw.small(g, font, Component.translatable("archud.admin.select").getString(), rx, iy, Draw.TEXT_DIM, 0.7F);
            return;
        }
        g.drawString(font, sel.name(), rx, iy, Draw.TEXT, true);
        Draw.small(g, font, "❤ " + Math.round(sel.hp()) + "/" + Math.round(sel.maxHp()) + "   " + sel.dim() + "  " + sel.x() + ", " + sel.y() + ", " + sel.z(),
                rx, iy + 12, Draw.TEXT_DIM, 0.65F);
        Draw.small(g, font, Component.translatable("gameMode." + sel.mode().getName()).getString()
                + (sel.op() ? "  ·  " + Component.translatable("archud.admin.is_op").getString() : ""), rx, iy + 21, Draw.TEXT_DIM, 0.65F);
    }

    private void renderNetwork(GuiGraphics g) {
        int x = px + 10, y = py + 50;
        if (flags[F_PUBLISHED]) {
            g.drawString(font, Component.translatable("archud.admin.address", address()), x, y, Draw.TEXT, true);
        } else {
            g.drawString(font, Component.translatable("archud.admin.closed"), x, y, 0xFFFFB74D, true);
        }
        int by = py + 70 + 22 + 30;
        g.drawString(font, Component.translatable("archud.admin.bans", bans.size()), x, by, Draw.TEXT_DIM, true);
        int row = 0;
        for (String name : bans) {
            if (row >= 4) break;
            g.drawString(font, "⛔ " + name, x + 4, by + 16 + row * 20, Draw.TEXT, true);
            row++;
        }
    }
}
