package com.arc4io.archud.client.map;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.config.ArcConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Метки: свои (сохраняются по миру/серверу в config/archud/waypoints), точка смерти,
 * а также метки модов-интеграций (вэйстоуны, локации Tempad).
 */
public final class WaypointManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<Waypoint> USER = new CopyOnWriteArrayList<>();
    private static final List<Supplier<List<Waypoint>>> PROVIDERS = new CopyOnWriteArrayList<>();
    private static List<Waypoint> modCache = List.of();
    /** Метки, которыми поделились другие игроки (до сохранения — только в памяти). */
    private static final List<Waypoint> SHARED = new CopyOnWriteArrayList<>();
    private static long modCacheTime;
    private static String worldKey = "";
    /** Глобальные метки администратора (с сервера): видят все, удалить может только администратор. */
    private static final List<Waypoint> GLOBAL = new CopyOnWriteArrayList<>();
    private static boolean globalCanEdit;
    /** Скрытые у себя метки любого типа (ключи {@link Waypoint#key()}), по миру. */
    private static final java.util.Set<String> HIDDEN = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private WaypointManager() {}

    /** Точка возрождения: кровать или якорь возрождения (с сервера Arc HUD или запомненная, когда вы спали). */
    private static String respawnDim;
    private static net.minecraft.core.BlockPos respawnPos;
    private static boolean respawnAnchor, respawnFromServer;

    static {
        PROVIDERS.add(() -> {
            if (!ArcConfig.get().spawnWaypoint || respawnPos == null || respawnDim == null) return List.of();
            Waypoint w = new Waypoint(Component.translatable(respawnAnchor ? "archud.wp.anchor" : "archud.wp.bed").getString(),
                    respawnPos.getX(), respawnPos.getY(), respawnPos.getZ(), respawnAnchor ? 0xFFB388FF : 0xFFFF6E6E, respawnDim,
                    Waypoint.Source.SPAWN);
            return List.of(w);
        });
        // путеводные камни: компасы, привязанные к путеводному камню, в инвентаре
        PROVIDERS.add(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return List.of();
            List<Waypoint> out = new ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            var inv = mc.player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                net.minecraft.world.item.ItemStack st = inv.getItem(i);
                if (st.isEmpty() || !net.minecraft.world.item.CompassItem.isLodestoneCompass(st) || st.getTag() == null) continue;
                net.minecraft.core.GlobalPos gp = net.minecraft.world.item.CompassItem.getLodestonePosition(st.getTag());
                if (gp == null) continue;
                String dim = gp.dimension().location().toString();
                if (!seen.add(dim + gp.pos().asLong())) continue;
                String name = st.hasCustomHoverName() ? st.getHoverName().getString() : Component.translatable("archud.wp.lodestone").getString();
                out.add(new Waypoint(name, gp.pos().getX(), gp.pos().getY() + 1, gp.pos().getZ(), 0xFFB0BEC5, dim, Waypoint.Source.LODESTONE));
            }
            return out;
        });
    }

    /** Кровать / якорь сломаны — метки точки возрождения больше нет (проверяется, когда чанк загружен). */
    private static void checkRespawnBlock(Minecraft mc) {
        if (respawnPos == null || respawnDim == null || mc.level == null || mc.player == null) return;
        if (!respawnDim.equals(mc.level.dimension().location().toString()) || !mc.level.isLoaded(respawnPos)) return;
        if (!mc.level.hasChunk(respawnPos.getX() >> 4, respawnPos.getZ() >> 4)) return;
        net.minecraft.world.level.block.Block b = mc.level.getBlockState(respawnPos).getBlock();
        boolean ok = respawnAnchor ? b instanceof net.minecraft.world.level.block.RespawnAnchorBlock : b instanceof net.minecraft.world.level.block.BedBlock;
        if (ok) return;
        setRespawn(null, null, false, false);
        respawnFromServer = false;
    }

    public static void setRespawn(String dim, net.minecraft.core.BlockPos pos, boolean anchor, boolean fromServer) {
        respawnDim = dim;
        respawnPos = pos;
        respawnAnchor = anchor;
        if (fromServer) respawnFromServer = true;
        modCacheTime = 0;
        // запомнить для этого мира (если сервер без Arc HUD)
        String key = worldKey();
        if (pos == null) ArcConfig.get().respawnPoints.remove(key);
        else ArcConfig.get().respawnPoints.put(key, dim + "|" + pos.getX() + "|" + pos.getY() + "|" + pos.getZ() + "|" + anchor);
        ArcConfig.save();
    }

    /** Для теста: точка возрождения, которую знает клиент. */
    public static String respawnInfo() {
        return respawnDim + " " + respawnPos + " anchor=" + respawnAnchor + " server=" + respawnFromServer;
    }

    /** Без сервера Arc HUD: кровать запоминается, когда вы на ней спите. */
    private static void trackBed(Minecraft mc) {
        if (respawnFromServer || mc.player == null || mc.level == null || !mc.player.isSleeping()) return;
        mc.player.getSleepingPos().ifPresent(pos -> {
            String dim = mc.level.dimension().location().toString();
            if (!pos.equals(respawnPos) || !dim.equals(respawnDim)) setRespawn(dim, pos, false, false);
        });
    }

    private static void loadRespawn() {
        respawnFromServer = false;
        respawnPos = null;
        respawnDim = null;
        String v = ArcConfig.get().respawnPoints.get(worldKey);
        if (v == null) return;
        try {
            String[] p = v.split("\\|");
            respawnDim = p[0];
            respawnPos = new net.minecraft.core.BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
            respawnAnchor = p.length > 4 && Boolean.parseBoolean(p[4]);
        } catch (Exception ignored) {
        }
    }

    /** Источник меток другого мода (вызывается раз в секунду). */
    public static void registerProvider(Supplier<List<Waypoint>> provider) {
        PROVIDERS.add(provider);
    }

    public static List<Waypoint> user() {
        return USER;
    }

    public static List<Waypoint> all() {
        long now = System.currentTimeMillis();
        if (now - modCacheTime > 1000) {
            modCacheTime = now;
            List<Waypoint> list = new ArrayList<>();
            for (Supplier<List<Waypoint>> p : PROVIDERS) {
                try {
                    List<Waypoint> l = p.get();
                    if (l != null) list.addAll(l);
                } catch (Throwable ignored) {
                }
            }
            modCache = list;
        }
        List<Waypoint> out = new ArrayList<>(USER);
        out.addAll(SHARED);
        out.addAll(GLOBAL);
        out.addAll(modCache);
        for (Waypoint w : out) {
            // у своих меток видимость хранится в самой метке, у остальных (пересоздаются) — в списке скрытых
            if (!w.editable() || w.source == Waypoint.Source.SHARED) w.visible = !HIDDEN.contains(w.key());
        }
        return out;
    }

    public static String currentDimension() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? "" : mc.level.dimension().location().toString();
    }

    public static String worldKey() {
        if (worldKey.isEmpty()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) return computeWorldKey(mc);
        }
        return worldKey.isEmpty() ? "unknown" : worldKey;
    }

    private static final java.util.Set<String> MIGRATED = new java.util.HashSet<>();

    /**
     * Ключ мира для карты и меток. В одиночной игре — имя ПАПКИ мира (уникально), а не отображаемое имя:
     * раньше все не-латинские буквы заменялись на «_», и миры «Новый мир» / «Мой дом 1» или два
     * «New World» делили одну карту. Старые данные один раз переносятся под новый ключ.
     */
    private static String computeWorldKey(Minecraft mc) {
        if (mc.getSingleplayerServer() != null) {
            var server = mc.getSingleplayerServer();
            String folder = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString();
            String key = "sp_" + folder.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F]", "_");
            String legacy = ("sp_" + server.getWorldData().getLevelName()).replaceAll("[^A-Za-z0-9_.\\-]", "_");
            if (!legacy.equals(key) && MIGRATED.add(key)) migrate(legacy, key);
            return key;
        }
        String k;
        if (mc.getCurrentServer() != null) {
            String ip = mc.getCurrentServer().ip;
            // в локальной сети порт каждый раз новый — без него карта и метки не терялись бы между сессиями
            if (mc.getCurrentServer().isLan() || ip.matches("(?i)(localhost|127\\..*|10\\..*|192\\.168\\..*|172\\.(1[6-9]|2\\d|3[01])\\..*|25\\..*|26\\..*)(:\\d+)?")) {
                int c = ip.lastIndexOf(':');
                if (c > 0 && ip.indexOf(':') == c) ip = ip.substring(0, c);
            }
            k = "mp_" + ip;
        }
        else k = "unknown";
        return k.replaceAll("[^A-Za-z0-9_.\\-]", "_");
    }

    /** Перенос карты и меток со старого ключа, если под новым ещё ничего нет. */
    private static void migrate(String from, String to) {
        try {
            Path base = ArcHud.platform().configDir().resolve("archud");
            Path mFrom = base.resolve("map").resolve(from), mTo = base.resolve("map").resolve(to);
            if (Files.isDirectory(mFrom) && !Files.exists(mTo)) Files.move(mFrom, mTo);
            Path wFrom = base.resolve("waypoints").resolve(from + ".json"), wTo = base.resolve("waypoints").resolve(to + ".json");
            if (Files.exists(wFrom) && !Files.exists(wTo)) Files.move(wFrom, wTo);
        } catch (Exception e) {
            ArcHud.LOGGER.warn("Arc HUD: map migration " + from + " -> " + to + " failed", e);
        }
    }

    private static Path file() {
        return ArcHud.platform().configDir().resolve("archud").resolve("waypoints").resolve(worldKey + ".json");
    }

    public static void tick(Minecraft mc) {
        if (mc.level == null) {
            worldKey = "";
            return;
        }
        String k = computeWorldKey(mc);
        if (!k.equals(worldKey)) {
            worldKey = k;
            load();
            loadRespawn();
        }
        trackBed(mc);
        if (mc.level.getGameTime() % 20 == 0) checkRespawnBlock(mc);
        trackDeath(mc);
    }

    /**
     * Смерть без экрана смерти (правило «мгновенное возрождение», моды): игрок умер, если здоровье упало до нуля
     * или после возрождения появился новый игрок, а старый был мёртв. Метка — там, где игрок был жив в последний раз.
     */
    private static Object lastPlayer;
    private static boolean lastDead;
    private static double[] lastAlive;
    private static String lastAliveDim;

    private static void trackDeath(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) return;
        boolean dead = p.isDeadOrDying();
        if (p != lastPlayer) {
            if (lastPlayer != null && lastDead && lastAlive != null) onDeathAt(lastAlive[0], lastAlive[1], lastAlive[2], lastAliveDim);
            lastPlayer = p;
            lastDead = dead;
        }
        if (dead && !lastDead && lastAlive != null) onDeathAt(lastAlive[0], lastAlive[1], lastAlive[2], lastAliveDim);
        lastDead = dead;
        if (!dead) {
            lastAlive = new double[]{p.getX(), p.getY(), p.getZ()};
            lastAliveDim = currentDimension();
        }
    }

    private static Path hiddenFile() {
        return ArcHud.platform().configDir().resolve("archud").resolve("waypoints").resolve(worldKey + ".hidden.txt");
    }

    private static void load() {
        HIDDEN.clear();
        try {
            Path h = hiddenFile();
            if (Files.exists(h)) HIDDEN.addAll(Files.readAllLines(h, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
        USER.clear();
        try {
            Path f = file();
            if (Files.exists(f)) {
                try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    List<Waypoint> list = GSON.fromJson(r, new TypeToken<List<Waypoint>>() {}.getType());
                    if (list != null) USER.addAll(list);
                }
            }
        } catch (Exception e) {
            ArcHud.LOGGER.warn("Arc HUD: не удалось загрузить метки", e);
        }
    }

    public static void save() {
        if (worldKey.isEmpty()) return;
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
                GSON.toJson(new ArrayList<>(USER), w);
            }
        } catch (Exception e) {
            ArcHud.LOGGER.warn("Arc HUD: не удалось сохранить метки", e);
        }
        com.arc4io.archud.client.ArcClientNet.waypointsChanged();
    }

    private static final Gson COMPACT = new Gson();

    /** Свои метки этого мира в JSON (для сервера). */
    public static String exportJson() {
        return COMPACT.toJson(new ArrayList<>(USER));
    }

    /**
     * Метки с сервера: добавляются те, которых ещё нет локально (совпадение — измерение, координаты и имя).
     * Так при входе с другого компьютера метки появляются сразу, а локальные не теряются. Возвращает число добавленных.
     */
    public static int mergeFromServer(String json) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            String k = computeWorldKey(mc);
            if (!k.equals(worldKey)) {
                worldKey = k;
                load();
                loadRespawn();
            }
        }
        if (json == null || json.isEmpty()) return 0;
        List<Waypoint> list;
        try {
            list = COMPACT.fromJson(json, new TypeToken<List<Waypoint>>() {}.getType());
        } catch (Exception e) {
            return 0;
        }
        if (list == null) return 0;
        int added = 0;
        for (Waypoint w : list) {
            if (w == null || w.dimension == null || w.name == null) continue;
            if (w.source != Waypoint.Source.USER && w.source != Waypoint.Source.DEATH) continue;
            boolean exists = false;
            for (Waypoint o : USER) {
                if (o.x == w.x && o.y == w.y && o.z == w.z && w.dimension.equals(o.dimension) && w.name.equals(o.name)) {
                    exists = true;
                    break;
                }
            }
            if (exists) continue;
            USER.add(w);
            added++;
        }
        if (added > 0) save();
        return added;
    }

    /** Новая метка на месте игрока — ещё НЕ сохранена (сохраняется кнопкой «Готово» в редакторе, Esc — отмена). */
    public static Waypoint createHere() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return null;
        int n = (int) USER.stream().filter(w -> w.source == Waypoint.Source.USER).count() + 1;
        return new Waypoint(Component.translatable("archud.wp.default", n).getString(), p.getBlockX(), p.getBlockY(), p.getBlockZ(),
                Waypoint.PALETTE[(n - 1) % Waypoint.PALETTE.length], currentDimension(), Waypoint.Source.USER);
    }

    public static Waypoint addHere() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return null;
        int n = (int) USER.stream().filter(w -> w.source == Waypoint.Source.USER).count() + 1;
        Waypoint w = new Waypoint(Component.translatable("archud.wp.default", n).getString(), p.getBlockX(), p.getBlockY(), p.getBlockZ(),
                Waypoint.PALETTE[(n - 1) % Waypoint.PALETTE.length], currentDimension(), Waypoint.Source.USER);
        USER.add(w);
        save();
        return w;
    }

    public static void add(Waypoint w) {
        if (w.source == Waypoint.Source.SHARED) {
            SHARED.remove(w);
            w.source = Waypoint.Source.USER;
        }
        if (!USER.contains(w)) USER.add(w);
        save();
    }

    public static void remove(Waypoint w) {
        USER.remove(w);
        SHARED.remove(w);
        save();
    }

    private static long lastDeath;

    /** История смертей: последние 5 точек, новые — ярче. */
    public static void onDeath() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        onDeathAt(mc.player.getX(), mc.player.getY(), mc.player.getZ(), currentDimension());
    }

    private static void onDeathAt(double x, double y, double z, String dim) {
        Minecraft mc = Minecraft.getInstance();
        if (!ArcConfig.get().deathWaypoint || mc.player == null || dim == null) return;
        long now = System.currentTimeMillis();
        if (now - lastDeath < 3000) return; // экран смерти может открываться повторно
        lastDeath = now;
        String time = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        Waypoint w = new Waypoint(Component.translatable("archud.wp.death_at", time).getString(), (int) Math.floor(x), (int) Math.floor(y),
                (int) Math.floor(z), 0xFFFF5252, dim, Waypoint.Source.DEATH);
        USER.add(w);
        List<Waypoint> deaths = new ArrayList<>();
        for (Waypoint d : USER) if (d.source == Waypoint.Source.DEATH) deaths.add(d);
        deaths.sort(java.util.Comparator.comparingLong(d -> d.created));
        while (deaths.size() > 5) USER.remove(deaths.remove(0));
        for (Waypoint d : deaths) if (d != w) d.color = 0xFF8E3B3B;
        if (ArcConfig.get().navigateToDeath) Navigator.set(w);
        save();
    }

    public static List<Waypoint> inDimension() {
        String dim = currentDimension();
        List<Waypoint> out = new ArrayList<>();
        for (Waypoint w : all()) if (w.visible && dim.equals(w.dimension) && sourceVisible(w.source)) out.add(w);
        Waypoint nav = Navigator.target();
        if (nav != null && nav.source == Waypoint.Source.TEMP && dim.equals(nav.dimension)) out.add(nav);
        return Collections.unmodifiableList(out);
    }

    /** Типы меток для фильтров: вэйстоуны и Tempad — только если стоят эти моды, «поделились» — в сетевой игре. */
    public static List<Waypoint.Source> filterSources() {
        List<Waypoint.Source> out = new ArrayList<>();
        out.add(Waypoint.Source.USER);
        out.add(Waypoint.Source.DEATH);
        out.add(Waypoint.Source.SPAWN);
        Minecraft mc = Minecraft.getInstance();
        boolean multi = !SHARED.isEmpty() || (mc.getConnection() != null && !mc.isSingleplayer())
                || (mc.getSingleplayerServer() != null && mc.getSingleplayerServer().isPublished());
        if (multi) out.add(Waypoint.Source.SHARED);
        if (!GLOBAL.isEmpty() || globalCanEdit()) out.add(Waypoint.Source.GLOBAL);
        out.add(Waypoint.Source.LODESTONE);
        if (ArcHud.platform().isModLoaded("waystones")) out.add(Waypoint.Source.WAYSTONE);
        if (ArcHud.platform().isModLoaded("tempad")) out.add(Waypoint.Source.TEMPAD);
        return out;
    }

    /** Показать / скрыть метку у себя (любого типа: свои, общие, глобальные, кровать, Tempad, вэйстоуны…). */
    public static void setVisible(Waypoint w, boolean visible) {
        w.visible = visible;
        if (w.editable() && w.source != Waypoint.Source.SHARED) {
            save();
            return;
        }
        if (visible) HIDDEN.remove(w.key());
        else HIDDEN.add(w.key());
        modCacheTime = 0;
        try {
            Path h = hiddenFile();
            Files.createDirectories(h.getParent());
            Files.write(h, new ArrayList<>(HIDDEN), StandardCharsets.UTF_8);
        } catch (Exception e) {
            ArcHud.LOGGER.warn("Arc HUD: cannot save hidden waypoints", e);
        }
    }

    // ------------------------------------------------------------------ глобальные метки администратора
    public static void setGlobal(List<Waypoint> list, boolean canEdit) {
        GLOBAL.clear();
        GLOBAL.addAll(list);
        globalCanEdit = canEdit;
    }

    /** Этот игрок — администратор: может создавать и удалять глобальные метки. */
    public static boolean globalCanEdit() {
        return globalCanEdit && com.arc4io.archud.client.ArcClientNet.connected();
    }

    public static boolean sourceVisible(Waypoint.Source s) {
        return !ArcConfig.get().hiddenWaypointSources.contains(s.name());
    }

    public static void toggleSource(Waypoint.Source s) {
        List<String> h = ArcConfig.get().hiddenWaypointSources;
        if (!h.remove(s.name())) h.add(s.name());
        ArcConfig.save();
    }

    /** Метка от другого игрока. */
    public static void addShared(Waypoint w) {
        SHARED.removeIf(o -> o.x == w.x && o.y == w.y && o.z == w.z && o.dimension.equals(w.dimension));
        SHARED.add(w);
        while (SHARED.size() > 30) SHARED.remove(0);
    }

    public static void clearShared() {
        SHARED.clear();
        GLOBAL.clear();
        globalCanEdit = false;
    }

    /** Поделиться меткой с игроком (target = ник) или со всеми (target пустой). Без Arc HUD на сервере — в чат. */
    public static boolean shareWith(Waypoint w, String target) {
        if (!com.arc4io.archud.client.ArcClientNet.canShare()) {
            if (target == null || target.isEmpty()) share(w);
            else if (Minecraft.getInstance().getConnection() != null) {
                Minecraft.getInstance().getConnection().sendCommand("msg " + target + " [" + w.name + "] x:" + w.x + " y:" + w.y + " z:" + w.z
                        + " (" + w.dimension.substring(w.dimension.indexOf(':') + 1) + ")");
            }
            return false;
        }
        com.arc4io.archud.client.ArcClientNet.shareWaypoint(w, target == null ? "" : target);
        return true;
    }

    /** Отправить координаты метки в чат. */
    public static void share(Waypoint w) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return;
        String dim = w.dimension.substring(w.dimension.indexOf(':') + 1);
        mc.getConnection().sendChat("[" + w.name + "] x:" + w.x + " y:" + w.y + " z:" + w.z + " (" + dim + ")");
    }
}
