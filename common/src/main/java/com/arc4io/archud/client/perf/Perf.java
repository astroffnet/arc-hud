package com.arc4io.archud.client.perf;

import com.arc4io.archud.config.ArcConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * 100 переключателей оптимизации (вкладка «Оптимизация» в настройках графики). Работают поверх ванилы,
 * Sodium / Embeddium и OptiFine: каждый либо отсекает лишнюю работу (дальние сущности, слои моделей,
 * блоки-сущности, частицы, эффекты), либо переключает дешёвый режим ванильной настройки.
 * Значения хранятся в ArcConfig.perf (0 — выключено; для дальностей — число блоков).
 */
public final class Perf {
    public enum Kind {TOGGLE, DIST, COUNT}

    /** Описание: id, группа, вид, варианты значений, значение в пресете «Баланс», в пресете «Максимум FPS». */
    public record Opt(String id, String group, Kind kind, int[] values, int balanced, int max, IntSupplier getter, Consumer<Integer> setter) {
        public int get() {
            return getter != null ? getter.getAsInt() : ArcConfig.get().perf.getOrDefault(id, 0);
        }

        public void set(int v) {
            if (setter != null) setter.accept(v);
            else if (v == 0) ArcConfig.get().perf.remove(id);
            else ArcConfig.get().perf.put(id, v);
            VanillaOpts.onChanged(id, v);
        }
    }

    private static final int[] DIST = {0, 8, 12, 16, 24, 32, 48, 64, 96, 128};
    private static final int[] ONOFF = {0, 1};
    public static final List<Opt> ALL = new ArrayList<>();
    private static final Map<String, Opt> BY_ID = new LinkedHashMap<>();
    public static final String[] GROUPS = {"distance", "tps", "entities", "layers", "details", "blockentities", "particles", "world", "system", "archud"};

    private static void dist(String group, String id, int bal, int max) {
        add(new Opt(id, group, Kind.DIST, DIST, bal, max, null, null));
    }

    private static void toggle(String group, String id, int bal, int max) {
        add(new Opt(id, group, Kind.TOGGLE, ONOFF, bal, max, null, null));
    }

    private static void bind(String group, String id, int bal, int max, IntSupplier get, Consumer<Integer> set) {
        add(new Opt(id, group, Kind.TOGGLE, ONOFF, bal, max, get, set));
    }

    private static void add(Opt o) {
        ALL.add(o);
        BY_ID.put(o.id(), o);
    }

    static {
        // ---- дальность прорисовки без потери FPS (2)
        add(new Opt("r_adaptive", "distance", Kind.COUNT, new int[]{0, 30, 45, 60, 75, 90, 120, 144}, 0, 60, null, null));
        toggle("distance", "r_upload_budget", 1, 1);
        // ---- TPS: мир на своём компьютере работает ровнее, когда сервер не успевает (5)
        add(new Opt("t_activation", "tps", Kind.DIST, new int[]{0, 24, 32, 48, 64}, 48, 32, null, null));
        toggle("tps", "t_dyn_sim", 1, 1);
        toggle("tps", "t_spawn", 1, 1);
        toggle("tps", "t_random", 0, 1);
        toggle("tps", "t_smooth_entities", 1, 1);
        // ---- сущности: дальность отрисовки по типам (15) + лимит, динамика, невидимые (3)
        dist("entities", "e_item", 32, 16);
        dist("entities", "e_xp", 24, 12);
        dist("entities", "e_item_frame", 48, 24);
        dist("entities", "e_armor_stand", 48, 24);
        dist("entities", "e_painting", 64, 32);
        dist("entities", "e_projectile", 48, 24);
        dist("entities", "e_hostile", 0, 64);
        dist("entities", "e_passive", 64, 32);
        dist("entities", "e_ambient", 32, 16);
        dist("entities", "e_water", 48, 24);
        dist("entities", "e_villager", 64, 32);
        dist("entities", "e_player", 0, 96);
        dist("entities", "e_vehicle", 64, 32);
        dist("entities", "e_falling", 48, 24);
        dist("entities", "e_other", 96, 48);
        add(new Opt("e_cap", "entities", Kind.COUNT, new int[]{0, 400, 250, 150, 100, 60}, 0, 150, null, null));
        toggle("entities", "e_dynamic", 1, 1);
        toggle("entities", "e_invisible", 1, 1);
        // ---- слои моделей (броня, предметы в руках, плащи…) — только вблизи (9)
        dist("layers", "l_armor", 48, 24);
        dist("layers", "l_held", 48, 24);
        dist("layers", "l_cape", 32, 16);
        dist("layers", "l_elytra", 48, 24);
        dist("layers", "l_head", 32, 16);
        dist("layers", "l_arrows", 16, 8);
        dist("layers", "l_stingers", 16, 8);
        dist("layers", "l_parrot", 32, 16);
        dist("layers", "l_other", 0, 32);
        // ---- детали (8)
        dist("details", "n_tag_dist", 32, 16);
        toggle("details", "n_mob_tags", 0, 1);
        toggle("details", "f_fire", 1, 1);
        bind("details", "s_shadow_dist", 1, 1, () -> ArcConfig.get().shadowDistance > 0 ? 1 : 0, v -> ArcConfig.get().shadowDistance = v == 0 ? 0 : 16);
        dist("details", "x_leash", 32, 16);
        toggle("details", "x_item_single", 1, 1);
        toggle("details", "x_item_spin", 0, 1);
        toggle("details", "x_glint", 0, 1);
        toggle("details", "x_fast_paintings", 1, 1);
        // ---- блоки-сущности: дальность (17) + за стенами + текст табличек (2)
        dist("blockentities", "b_chest", 48, 24);
        dist("blockentities", "b_sign", 64, 32);
        bind("blockentities", "b_sign_text", 1, 1, () -> ArcConfig.get().signTextDistance > 0 ? 1 : 0, v -> ArcConfig.get().signTextDistance = v == 0 ? 0 : 16);
        dist("blockentities", "b_banner", 48, 24);
        dist("blockentities", "b_skull", 48, 24);
        toggle("blockentities", "b_beacon", 0, 1);
        dist("blockentities", "b_enchant_book", 32, 16);
        dist("blockentities", "b_spawner", 24, 16);
        dist("blockentities", "b_shulker", 48, 24);
        dist("blockentities", "b_bed", 48, 24);
        dist("blockentities", "b_bell", 32, 16);
        dist("blockentities", "b_conduit", 48, 24);
        dist("blockentities", "b_campfire", 32, 16);
        dist("blockentities", "b_lectern", 32, 16);
        dist("blockentities", "b_pot", 32, 16);
        dist("blockentities", "b_brushable", 24, 12);
        dist("blockentities", "b_end_portal", 0, 32);
        dist("blockentities", "b_modded", 96, 48);
        bind("blockentities", "b_walls", 1, 1, () -> ArcConfig.get().blockEntityCulling ? 1 : 0, v -> ArcConfig.get().blockEntityCulling = v != 0);
        // ---- частицы (19)
        add(new Opt("p_limit", "particles", Kind.COUNT, new int[]{0, 8000, 4000, 2000, 1000, 500}, 4000, 1000, null, null));
        dist("particles", "p_distance", 48, 24);
        toggle("particles", "p_rain", 0, 1);
        toggle("particles", "p_block_break", 1, 1);
        toggle("particles", "p_block_crack", 0, 1);
        toggle("particles", "p_smoke", 1, 1);
        toggle("particles", "p_explosion", 1, 1);
        toggle("particles", "p_potion", 1, 1);
        toggle("particles", "p_footstep", 0, 1);
        toggle("particles", "p_drip", 1, 1);
        toggle("particles", "p_portal", 1, 1);
        toggle("particles", "p_biome", 1, 1);
        toggle("particles", "p_firework", 0, 1);
        toggle("particles", "p_crit", 0, 1);
        toggle("particles", "p_totem", 0, 1);
        toggle("particles", "p_bubble", 1, 1);
        toggle("particles", "p_social", 0, 1);
        toggle("particles", "p_dust", 0, 1);
        toggle("particles", "p_lava", 1, 1);
        toggle("particles", "p_light_cache", 1, 1);
        toggle("particles", "p_async", 0, 1);
        // ---- мир (13)
        toggle("world", "w_clouds", 0, 1);
        toggle("world", "w_stars", 0, 1);
        toggle("world", "w_rain", 0, 1);
        toggle("world", "w_lightning", 1, 1);
        toggle("world", "w_fog", 0, 1);
        toggle("world", "w_vignette", 0, 1);
        toggle("world", "w_portal_overlay", 0, 1);
        toggle("world", "w_biome_blend", 0, 1);
        toggle("world", "w_smooth_light", 0, 1);
        toggle("world", "w_mipmaps", 0, 1);
        toggle("world", "w_fast_graphics", 0, 1);
        toggle("world", "w_vanilla_shadows", 0, 1);
        toggle("world", "w_chunk_priority", 1, 1);
        // ---- система (7)
        toggle("system", "s_menu_fps", 1, 1);
        bind("system", "s_bg_fps", 1, 1, () -> ArcConfig.get().dynamicFps ? 1 : 0, v -> ArcConfig.get().dynamicFps = v != 0);
        toggle("system", "s_minimized", 1, 1);
        bind("system", "s_lazy_anim", 1, 1, () -> ArcConfig.get().lazyTextureAnimations ? 1 : 0, v -> ArcConfig.get().lazyTextureAnimations = v != 0);
        toggle("system", "s_gc", 1, 1);
        toggle("system", "s_world_behind_menu", 0, 1);
        toggle("system", "s_entity_outline", 0, 1);
        // ---- Arc HUD (7)
        toggle("archud", "a_minimap_30fps", 1, 1);
        bind("archud", "a_minimap_entities", 0, 1, () -> ArcConfig.get().minimapEntities ? 0 : 1, v -> ArcConfig.get().minimapEntities = v == 0);
        bind("archud", "a_world_beacons", 0, 1, () -> ArcConfig.get().waypointBeams ? 0 : 1, v -> ArcConfig.get().waypointBeams = v == 0);
        toggle("archud", "a_map_scan", 0, 1);
        bind("archud", "a_hud_anim", 0, 1, () -> ArcConfig.get().smoothAnimations ? 0 : 1, v -> ArcConfig.get().smoothAnimations = v == 0);
        bind("archud", "a_trail", 0, 1, () -> ArcConfig.get().minimapTrail ? 0 : 1, v -> ArcConfig.get().minimapTrail = v == 0);
        bind("archud", "a_damage_numbers", 0, 1, () -> ArcConfig.get().damageNumbers ? 0 : 1, v -> ArcConfig.get().damageNumbers = v == 0);
    }

    private Perf() {}

    public static Opt opt(String id) {
        return BY_ID.get(id);
    }

    /** Значение настройки (0 — выключено). */
    public static int get(String id) {
        return ArcConfig.get().perf.getOrDefault(id, 0);
    }

    public static boolean on(String id) {
        return get(id) != 0;
    }

    /** Пресет: 0 — всё выключить, 1 — баланс, 2 — максимум FPS. */
    public static void preset(int which) {
        for (Opt o : ALL) o.set(which == 0 ? 0 : which == 1 ? o.balanced() : o.max());
        ArcConfig.save();
    }

    public static int enabledCount() {
        int n = 0;
        for (Opt o : ALL) if (o.get() != 0) n++;
        return n;
    }

    // ------------------------------------------------------------------ дальности
    /** Множитель дальностей при низком FPS (e_dynamic): ниже 30 FPS — 0.6, ниже 45 — 0.8. */
    public static double scale() {
        if (!on("e_dynamic")) return 1.0;
        int fps = Minecraft.getInstance().getFps();
        return fps < 30 ? 0.6 : fps < 45 ? 0.8 : 1.0;
    }

    /** true — дальше заданной дальности (0 — без ограничения). */
    public static boolean beyond(String id, double distSqr) {
        int d = get(id);
        if (d <= 0) return false;
        double lim = d * scale();
        return distSqr > lim * lim;
    }

    static double distSqrToCamera(Entity e) {
        return e.distanceToSqr(Minecraft.getInstance().gameRenderer.getMainCamera().getPosition());
    }
}
