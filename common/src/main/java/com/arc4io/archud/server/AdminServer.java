package com.arc4io.archud.server;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.config.ArcConfig;
import com.arc4io.archud.network.ArcNet;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Серверная часть админ-панели Arc HUD. Открывается только операторам (уровень прав из настройки
 * adminPermissionLevel, по умолчанию 2) и только после входа в аккаунт (/login на пиратских серверах).
 *
 * Обмен — JSON (тот же протокол реализует плагин для Bukkit/Paper):
 *  C2S admin_open / admin_refresh — прислать состояние; admin_action {action, target, arg} — выполнить;
 *  S2C admin_allowed (bool), admin_state (JSON), admin_result (текст).
 * Большинство действий выполняются ванильными командами ОТ ИМЕНИ администратора — значит, права
 * проверяет сама игра (оператор 2 уровня не сможет /stop), а действия видны в логе сервера.
 */
public final class AdminServer {
    private static final Gson GSON = new Gson();
    private static final Set<UUID> GOD = new HashSet<>();
    private static final Map<UUID, double[]> FROZEN = new HashMap<>();
    private static final Set<String> MUTED = new HashSet<>();
    private static final Deque<String> LOG = new ArrayDeque<>();
    private static final Map<UUID, Boolean> LAST_ALLOWED = new HashMap<>();
    private static MinecraftServer loadedFor;
    private static long startedAt = System.currentTimeMillis();

    /** Игровые правила-переключатели в панели. */
    private static final String[] RULES = {"keepInventory", "doDaylightCycle", "doWeatherCycle", "mobGriefing", "doFireTick", "doMobSpawning",
            "doInsomnia", "doImmediateRespawn", "naturalRegeneration", "showDeathMessages", "announceAdvancements", "doMobLoot",
            "doTileDrops", "fallDamage", "fireDamage", "drowningDamage", "doPatrolSpawning", "doTraderSpawning", "forgiveDeadPlayers",
            "sendCommandFeedback"};

    private AdminServer() {}

    public static void init() {
        ArcNet.onServer("admin_open", (p, buf) -> sendState(p));
        ArcNet.onServer("admin_refresh", (p, buf) -> sendState(p));
        ArcNet.onServer("admin_action", AdminServer::onAction);
    }

    public static boolean allowed(ServerPlayer p) {
        if (p == null || p.getServer() == null || !ArcServer.isArcPlayer(p.getUUID())) return false;
        if (p.getServer().isSingleplayerOwner(p.getGameProfile())) return true;
        return p.hasPermissions(Math.max(1, Math.min(4, ArcConfig.get().adminPermissionLevel)));
    }

    // ------------------------------------------------------------------ тик
    public static void tick(MinecraftServer server, int tick) {
        if (loadedFor != server) {
            loadedFor = server;
            startedAt = System.currentTimeMillis();
            load(server);
        }
        // заморозка: вернуть на место
        if (!FROZEN.isEmpty()) {
            FROZEN.entrySet().removeIf(e -> {
                ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
                if (p == null) return true;
                double[] a = e.getValue();
                double dx = p.getX() - a[0], dy = p.getY() - a[1], dz = p.getZ() - a[2];
                if (dx * dx + dy * dy + dz * dz > 0.01) p.teleportTo(p.serverLevel(), a[0], a[1], a[2], p.getYRot(), p.getXRot());
                return false;
            });
        }
        // кому можно открыть панель (права могут поменяться — /op, /deop)
        if (tick % 40 == 0) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (!ArcServer.isArcPlayer(p.getUUID())) continue;
                boolean a = allowed(p);
                Boolean was = LAST_ALLOWED.put(p.getUUID(), a);
                if (was == null || was != a) {
                    FriendlyByteBuf buf = ArcNet.create("admin_allowed");
                    buf.writeBoolean(a);
                    ArcNet.sendToPlayer(p, buf);
                }
            }
        }
    }

    public static void onLogout(ServerPlayer p) {
        LAST_ALLOWED.remove(p.getUUID());
    }

    /** Заглушённый игрок (мут из админ-панели) не может писать в чат. */
    public static boolean muted(ServerPlayer p) {
        return MUTED.contains(p.getGameProfile().getName());
    }

    // ------------------------------------------------------------------ состояние
    private static void sendState(ServerPlayer admin) {
        if (!allowed(admin)) return;
        MinecraftServer s = admin.getServer();
        JsonObject o = new JsonObject();
        JsonObject srv = new JsonObject();
        srv.addProperty("motd", s.getMotd());
        srv.addProperty("version", s.getServerVersion());
        srv.addProperty("brand", s.getServerModName());
        float mspt = s.getAverageTickTime();
        srv.addProperty("mspt", Math.round(mspt * 10) / 10.0);
        srv.addProperty("tps", Math.round(Math.min(20.0, 1000.0 / Math.max(mspt, 1e-3)) * 10) / 10.0);
        Runtime rt = Runtime.getRuntime();
        srv.addProperty("memUsed", (rt.totalMemory() - rt.freeMemory()) >> 20);
        srv.addProperty("memMax", rt.maxMemory() >> 20);
        srv.addProperty("uptime", (System.currentTimeMillis() - startedAt) / 1000);
        srv.addProperty("players", s.getPlayerCount());
        srv.addProperty("maxPlayers", s.getMaxPlayers());
        srv.addProperty("viewDistance", s.getPlayerList().getViewDistance());
        srv.addProperty("simDistance", s.getPlayerList().getSimulationDistance());
        srv.addProperty("whitelist", s.getPlayerList().isUsingWhitelist());
        srv.addProperty("pvp", s.isPvpAllowed());
        srv.addProperty("difficulty", s.getWorldData().getDifficulty().getKey());
        srv.addProperty("antiEsp", ArcConfig.get().antiEsp);
        srv.addProperty("level", permissionLevel(admin));
        o.add("server", srv);

        ServerLevel ow = s.overworld();
        JsonObject world = new JsonObject();
        world.addProperty("time", ow.getDayTime() % 24000L);
        world.addProperty("day", ow.getDayTime() / 24000L + 1);
        world.addProperty("weather", ow.isThundering() ? "thunder" : ow.isRaining() ? "rain" : "clear");
        JsonObject rules = new JsonObject();
        GameRules gr = ow.getGameRules();
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public void visitBoolean(GameRules.Key<GameRules.BooleanValue> key, GameRules.Type<GameRules.BooleanValue> type) {
                for (String r : RULES) if (r.equals(key.getId())) rules.addProperty(r, gr.getBoolean(key));
            }
        });
        world.add("rules", rules);
        o.add("world", world);

        JsonArray players = new JsonArray();
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            JsonObject j = new JsonObject();
            j.addProperty("name", p.getGameProfile().getName());
            j.addProperty("uuid", p.getUUID().toString());
            j.addProperty("op", permissionLevel(p));
            j.addProperty("mode", p.gameMode.getGameModeForPlayer().getName());
            j.addProperty("health", Math.round(p.getHealth()));
            j.addProperty("food", p.getFoodData().getFoodLevel());
            j.addProperty("xp", p.experienceLevel);
            j.addProperty("dim", p.level().dimension().location().toString());
            j.addProperty("x", p.getBlockX());
            j.addProperty("y", p.getBlockY());
            j.addProperty("z", p.getBlockZ());
            j.addProperty("ping", p.latency);
            j.addProperty("fly", p.getAbilities().mayfly);
            j.addProperty("god", GOD.contains(p.getUUID()));
            j.addProperty("frozen", FROZEN.containsKey(p.getUUID()));
            j.addProperty("muted", MUTED.contains(p.getGameProfile().getName()));
            j.addProperty("arc", ArcServer.isArcPlayer(p.getUUID()));
            j.addProperty("hidden", ArcServer.isHidden(p.getUUID()));
            j.addProperty("ip", p.getIpAddress());
            players.add(j);
        }
        o.add("players", players);
        o.add("bans", strings(s.getPlayerList().getBans().getUserList()));
        o.add("ipBans", strings(s.getPlayerList().getIpBans().getUserList()));
        o.add("whitelistNames", strings(s.getPlayerList().getWhiteListNames()));
        o.add("ops", strings(s.getPlayerList().getOpNames()));
        o.add("mutedNames", strings(MUTED.toArray(new String[0])));
        o.add("log", strings(LOG.toArray(new String[0])));

        byte[] data = GSON.toJson(o).getBytes(StandardCharsets.UTF_8);
        FriendlyByteBuf buf = ArcNet.create("admin_state");
        buf.writeByteArray(data);
        ArcNet.sendToPlayer(admin, buf);
    }

    private static JsonArray strings(String[] list) {
        JsonArray a = new JsonArray();
        for (String x : list) a.add(x);
        return a;
    }

    private static int permissionLevel(ServerPlayer p) {
        for (int i = 4; i >= 1; i--) if (p.hasPermissions(i)) return i;
        return 0;
    }

    // ------------------------------------------------------------------ действия
    private static void onAction(ServerPlayer admin, FriendlyByteBuf buf) {
        String action = buf.readUtf(64), target = buf.readUtf(64), arg = buf.readUtf(256);
        if (!allowed(admin)) {
            result(admin, Component.translatable("archud.admin.denied").getString());
            return;
        }
        MinecraftServer s = admin.getServer();
        ServerPlayer t = target.isEmpty() ? null : s.getPlayerList().getPlayerByName(target);
        String name = target.replaceAll("[^A-Za-z0-9_]", "");
        String msg = execute(s, admin, action, t, name, arg);
        if (msg != null) {
            log(admin, action + (name.isEmpty() ? "" : " " + name) + (arg.isEmpty() ? "" : " " + arg));
            result(admin, msg);
        }
        sendState(admin);
    }

    /** null — неизвестное действие. */
    private static String execute(MinecraftServer s, ServerPlayer admin, String action, ServerPlayer t, String name, String arg) {
        String safeArg = arg.replace('\n', ' ').replace('§', ' ');
        boolean need = action.startsWith("p_");
        if (need && name.isEmpty()) return "?";
        String me = admin.getGameProfile().getName();
        switch (action) {
            // ---------- игроки
            case "p_kick" -> cmd(admin, "kick " + name + (safeArg.isEmpty() ? "" : " " + safeArg));
            case "p_ban" -> cmd(admin, "ban " + name + (safeArg.isEmpty() ? "" : " " + safeArg));
            case "p_banip" -> cmd(admin, "ban-ip " + name);
            case "p_op" -> cmd(admin, "op " + name);
            case "p_deop" -> cmd(admin, "deop " + name);
            case "p_survival", "p_creative", "p_adventure", "p_spectator" -> cmd(admin, "gamemode " + action.substring(2) + " " + name);
            case "p_tpto" -> cmd(admin, "tp " + me + " " + name);
            case "p_tphere" -> cmd(admin, "tp " + name + " " + me);
            case "p_heal" -> {
                if (t == null) return offline(name);
                t.setHealth(t.getMaxHealth());
                t.clearFire();
            }
            case "p_feed" -> {
                if (t == null) return offline(name);
                t.getFoodData().setFoodLevel(20);
                t.getFoodData().setSaturation(20F);
            }
            case "p_kill" -> cmd(admin, "kill " + name);
            case "p_clearinv" -> cmd(admin, "clear " + name);
            case "p_cleareffects" -> cmd(admin, "effect clear " + name);
            case "p_fly" -> {
                if (t == null) return offline(name);
                boolean on = !t.getAbilities().mayfly;
                t.getAbilities().mayfly = on;
                if (!on) t.getAbilities().flying = false;
                t.onUpdateAbilities();
            }
            case "p_god" -> {
                if (t == null) return offline(name);
                boolean on = GOD.add(t.getUUID());
                if (!on) GOD.remove(t.getUUID());
                t.getAbilities().invulnerable = on || t.isCreative() || t.isSpectator();
                t.onUpdateAbilities();
            }
            case "p_freeze" -> {
                if (t == null) return offline(name);
                if (FROZEN.remove(t.getUUID()) == null) FROZEN.put(t.getUUID(), new double[]{t.getX(), t.getY(), t.getZ()});
            }
            case "p_mute" -> {
                if (!MUTED.remove(name)) MUTED.add(name);
                save(s);
            }
            case "p_smite" -> cmd(admin, "execute at " + name + " run summon lightning_bolt ~ ~ ~");
            case "p_launch" -> {
                if (t == null) return offline(name);
                t.setDeltaMovement(t.getDeltaMovement().add(0, 2.5, 0));
                t.hurtMarked = true;
            }
            case "p_xp" -> cmd(admin, "xp add " + name + " 10 levels");
            case "p_xpreset" -> {
                cmd(admin, "xp set " + name + " 0 levels");
                cmd(admin, "xp set " + name + " 0 points");
            }
            case "p_speed" -> effect(t, MobEffects.MOVEMENT_SPEED, 1);
            case "p_nightvision" -> effect(t, MobEffects.NIGHT_VISION, 0);
            case "p_invis" -> effect(t, MobEffects.INVISIBILITY, 0);
            case "p_fireres" -> effect(t, MobEffects.FIRE_RESISTANCE, 0);
            case "p_regen" -> effect(t, MobEffects.REGENERATION, 1);
            case "p_wladd" -> cmd(admin, "whitelist add " + name);
            case "p_wlremove" -> cmd(admin, "whitelist remove " + name);
            case "p_msg" -> {
                if (t == null) return offline(name);
                t.sendSystemMessage(Component.literal("[" + me + " → " + Component.translatable("archud.admin.you").getString() + "] " + safeArg)
                        .withStyle(ChatFormatting.LIGHT_PURPLE));
            }
            case "p_spawnpoint" -> cmd(admin, "spawnpoint " + name);
            case "p_spectate" -> {
                cmd(admin, "gamemode spectator " + me);
                cmd(admin, "spectate " + name + " " + me);
            }
            case "p_extinguish" -> {
                if (t == null) return offline(name);
                t.clearFire();
            }
            case "p_unban" -> cmd(admin, "pardon " + name);
            case "p_unbanip" -> cmd(admin, "pardon-ip " + safeArg.replaceAll("[^0-9a-fA-F.:]", ""));
            case "p_tpspawn" -> {
                if (t == null) return offline(name);
                var lvl = s.overworld();
                var sp = lvl.getSharedSpawnPos();
                t.teleportTo(lvl, sp.getX() + 0.5, sp.getY(), sp.getZ() + 0.5, t.getYRot(), t.getXRot());
            }
            // ---------- мир
            case "w_day" -> cmd(admin, "time set day");
            case "w_noon" -> cmd(admin, "time set noon");
            case "w_night" -> cmd(admin, "time set night");
            case "w_midnight" -> cmd(admin, "time set midnight");
            case "w_clear" -> cmd(admin, "weather clear");
            case "w_rain" -> cmd(admin, "weather rain");
            case "w_thunder" -> cmd(admin, "weather thunder");
            case "w_peaceful", "w_easy", "w_normal", "w_hard" -> cmd(admin, "difficulty " + action.substring(2));
            case "w_killmobs" -> cmd(admin, "kill @e[type=!player,type=!item,type=!item_frame,type=!glow_item_frame,type=!painting,type=!armor_stand,type=!villager,type=!wandering_trader]");
            case "w_killitems" -> cmd(admin, "kill @e[type=item]");
            case "w_setspawn" -> cmd(admin, "setworldspawn");
            case "w_rule" -> {
                if (!java.util.Arrays.asList(RULES).contains(safeArg)) return "?";
                Boolean cur = ruleValue(s.overworld().getGameRules(), safeArg);
                cmd(admin, "gamerule " + safeArg + " " + (cur == null || !cur));
            }
            case "w_pvp" -> s.setPvpAllowed(!s.isPvpAllowed());
            // ---------- сервер
            case "s_broadcast" -> {
                if (safeArg.isBlank()) return "?";
                s.getPlayerList().broadcastSystemMessage(Component.literal("[" + Component.translatable("archud.admin.broadcast_prefix").getString() + "] " + safeArg)
                        .withStyle(ChatFormatting.GOLD), false);
            }
            case "s_save" -> cmd(admin, "save-all");
            case "s_whitelist" -> cmd(admin, "whitelist " + (s.getPlayerList().isUsingWhitelist() ? "off" : "on"));
            case "s_reload" -> cmd(admin, "reload");
            case "s_view_up", "s_view_down" -> {
                int v = s.getPlayerList().getViewDistance() + (action.endsWith("up") ? 1 : -1);
                s.getPlayerList().setViewDistance(Math.max(2, Math.min(32, v)));
            }
            case "s_sim_up", "s_sim_down" -> {
                int v = s.getPlayerList().getSimulationDistance() + (action.endsWith("up") ? 1 : -1);
                s.getPlayerList().setSimulationDistance(Math.max(2, Math.min(32, v)));
            }
            case "s_healall" -> {
                for (ServerPlayer p : s.getPlayerList().getPlayers()) {
                    p.setHealth(p.getMaxHealth());
                    p.getFoodData().setFoodLevel(20);
                }
            }
            case "s_kicknonops" -> {
                for (ServerPlayer p : List.copyOf(s.getPlayerList().getPlayers())) {
                    if (!p.hasPermissions(2) && p != admin) p.connection.disconnect(Component.translatable("archud.admin.kicked_all"));
                }
            }
            case "s_antiesp" -> {
                ArcConfig.get().antiEsp = !ArcConfig.get().antiEsp;
                ArcConfig.save();
            }
            case "s_stop" -> cmd(admin, "stop");
            default -> {
                return null;
            }
        }
        return Component.translatable("archud.admin.done").getString();
    }

    private static Boolean ruleValue(GameRules gr, String id) {
        Boolean[] out = {null};
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public void visitBoolean(GameRules.Key<GameRules.BooleanValue> key, GameRules.Type<GameRules.BooleanValue> type) {
                if (key.getId().equals(id)) out[0] = gr.getBoolean(key);
            }
        });
        return out[0];
    }

    private static String offline(String name) {
        return Component.translatable("archud.admin.offline", name).getString();
    }

    private static void effect(ServerPlayer t, net.minecraft.world.effect.MobEffect e, int amp) {
        if (t != null) t.addEffect(new MobEffectInstance(e, 20 * 60 * 5, amp));
    }

    /** Команда от имени администратора (права проверяет игра, вывод — ему в чат). */
    private static void cmd(ServerPlayer admin, String command) {
        CommandSourceStack src = admin.createCommandSourceStack();
        admin.getServer().getCommands().performPrefixedCommand(src, command);
    }

    private static void result(ServerPlayer admin, String text) {
        FriendlyByteBuf buf = ArcNet.create("admin_result");
        buf.writeUtf(text, 256);
        ArcNet.sendToPlayer(admin, buf);
    }

    private static void log(ServerPlayer admin, String what) {
        String line = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "  " + admin.getGameProfile().getName() + ": " + what;
        LOG.addFirst(line);
        while (LOG.size() > 60) LOG.removeLast();
        ArcHud.LOGGER.info("[Arc HUD admin] {}", line);
    }

    // ------------------------------------------------------------------ хранение мутов
    private static Path file(MinecraftServer s) {
        return s.getWorldPath(LevelResource.ROOT).resolve("data").resolve("archud_admin.json");
    }

    private static void load(MinecraftServer s) {
        MUTED.clear();
        GOD.clear();
        FROZEN.clear();
        LOG.clear();
        try {
            Path f = file(s);
            if (!Files.exists(f)) return;
            JsonObject o = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), JsonObject.class);
            if (o != null && o.has("muted")) for (var e : o.getAsJsonArray("muted")) MUTED.add(e.getAsString());
        } catch (Exception e) {
            ArcHud.LOGGER.warn("Arc HUD: cannot read admin data", e);
        }
    }

    private static void save(MinecraftServer s) {
        try {
            JsonObject o = new JsonObject();
            o.add("muted", strings(MUTED.toArray(new String[0])));
            Path f = file(s);
            Files.createDirectories(f.getParent());
            Files.writeString(f, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception e) {
            ArcHud.LOGGER.warn("Arc HUD: cannot save admin data", e);
        }
    }
}
