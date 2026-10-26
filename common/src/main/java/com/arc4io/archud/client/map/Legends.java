package com.arc4io.archud.client.map;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.config.ArcConfig;
import com.arc4io.archud.network.ArcNet;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Легенды на карте (клиент): значки открытых построек — деревни, аванпосты, храмы… Приходят с сервера Arc HUD,
 * не входят в список меток, не мешают навигации. Игрок может убрать легенду у себя (правый клик на карте мира),
 * а разрушенные постройки сервер убирает сам.
 */
public final class Legends {
    public record Legend(String id, String structure, String dim, int x, int y, int z) {}

    public static final int COLOR = 0xFFD9B44A;
    private static final Map<String, Legend> ALL = new LinkedHashMap<>();
    private static final Set<String> HIDDEN = new HashSet<>();
    private static String loadedFor = "";

    private Legends() {}

    public static void init() {
        ArcNet.onClient("legend_add", buf -> {
            Legend l = new Legend(buf.readUtf(256), buf.readUtf(128), buf.readUtf(128), buf.readInt(), buf.readInt(), buf.readInt());
            ALL.put(l.id(), l);
        });
        ArcNet.onClient("legend_remove", buf -> ALL.remove(buf.readUtf(256)));
    }

    /** Новый мир / сервер — список заново. */
    public static void reset() {
        ALL.clear();
        loadedFor = "";
    }

    private static Path hiddenFile() {
        return ArcHud.platform().configDir().resolve("archud").resolve("legends_hidden").resolve(WaypointManager.worldKey().replaceAll("[^A-Za-z0-9_.-]", "_") + ".txt");
    }

    private static void ensureHidden() {
        String key = WaypointManager.worldKey();
        if (key.equals(loadedFor)) return;
        loadedFor = key;
        HIDDEN.clear();
        try {
            Path f = hiddenFile();
            if (Files.exists(f)) HIDDEN.addAll(Files.readAllLines(f, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    public static void hide(Legend l) {
        ensureHidden();
        HIDDEN.add(l.id());
        try {
            Path f = hiddenFile();
            Files.createDirectories(f.getParent());
            Files.write(f, HIDDEN, StandardCharsets.UTF_8);
        } catch (Exception e) {
            ArcHud.LOGGER.warn("Arc HUD: cannot save hidden legends", e);
        }
    }

    public static List<Legend> visible(String dim) {
        List<Legend> out = new ArrayList<>();
        if (!ArcConfig.get().showLegends) return out;
        ensureHidden();
        for (Legend l : ALL.values()) if (l.dim().equals(dim) && !HIDDEN.contains(l.id())) out.add(l);
        return out;
    }

    /** Путь постройки без пространства имён: village_plains, pillager_outpost… */
    private static String path(Legend l) {
        int i = l.structure().indexOf(':');
        return i < 0 ? l.structure() : l.structure().substring(i + 1);
    }

    public static String icon(Legend l) {
        String p = path(l);
        if (p.startsWith("village")) return "house";
        if (p.contains("outpost")) return "tower";
        if (p.equals("mansion")) return "castle";
        if (p.equals("monument") || p.startsWith("ocean_ruin")) return "anchor";
        if (p.contains("pyramid")) return "mountain";
        if (p.equals("swamp_hut")) return "moon";
        if (p.equals("igloo")) return "tent";
        if (p.startsWith("shipwreck")) return "boat";
        if (p.startsWith("ruined_portal")) return "portal";
        if (p.equals("stronghold")) return "eye";
        if (p.equals("ancient_city")) return "skull";
        if (p.equals("trail_ruins")) return "pickaxe";
        if (p.equals("fortress")) return "fire";
        if (p.equals("bastion_remnant")) return "crown";
        if (p.equals("end_city")) return "tower";
        return "star";
    }

    public static Component name(Legend l) {
        String p = path(l);
        String key = "archud.legend." + (p.startsWith("village") ? "village" : p.startsWith("shipwreck") ? "shipwreck"
                : p.startsWith("ruined_portal") ? "ruined_portal" : p.startsWith("ocean_ruin") ? "ocean_ruin" : p);
        String pretty = p.replace('_', ' ');
        pretty = pretty.isEmpty() ? p : Character.toUpperCase(pretty.charAt(0)) + pretty.substring(1);
        return Component.translatableWithFallback(key, pretty);
    }

    /** Нарисовать легенды на карте (мини-карта и карта мира). */
    public static void draw(GuiGraphics g, Font font, String dim, float cx, float cy, double centerX, double centerZ, float zoom, float rotation,
                            float half, int r, boolean labels) {
        for (Legend l : visible(dim)) {
            float[] s = MapView.toScreen(l.x() + 0.5, l.z() + 0.5, centerX, centerZ, zoom, rotation);
            if (Math.abs(s[0]) > half - 2 || Math.abs(s[1]) > half - 2) continue;
            int x = Math.round(cx + s[0]), y = Math.round(cy + s[1]);
            MapIcons.badge(g, icon(l), x, y, r, COLOR, 0xE0);
            if (labels) Draw.smallCentered(g, font, name(l).getString(), x, y + r + 3, 0xFFF3E2B0, 0.55F);
        }
    }

    /** Легенда под курсором на карте мира. */
    public static Legend at(String dim, float cx, float cy, double centerX, double centerZ, float zoom, double mx, double my) {
        Legend best = null;
        double bd = 8 * 8;
        for (Legend l : visible(dim)) {
            float[] s = MapView.toScreen(l.x() + 0.5, l.z() + 0.5, centerX, centerZ, zoom, 0F);
            double d = Math.pow(cx + s[0] - mx, 2) + Math.pow(cy + s[1] - my, 2);
            if (d < bd) {
                bd = d;
                best = l;
            }
        }
        return best;
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
