package com.arc4io.archud.client.dev;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.client.camera.ArcCamera;
import com.arc4io.archud.client.fx.WeaponInspect;
import com.arc4io.archud.client.map.Navigator;
import com.arc4io.archud.client.map.Waypoint;
import com.arc4io.archud.client.map.WaypointListScreen;
import com.arc4io.archud.client.map.WaypointManager;
import com.arc4io.archud.client.map.WorldMapScreen;
import com.arc4io.archud.client.screen.ArcConfigScreen;
import com.arc4io.archud.client.screen.ArcControlsScreen;
import com.arc4io.archud.client.screen.ArcGameRulesScreen;
import com.arc4io.archud.client.screen.HostPanelScreen;
import com.arc4io.archud.client.screen.HudLayoutScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.HttpUtil;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Автоматический «тур» для проверки мода и скриншотов (только при переменной окружения ARCHUD_TOUR=1,
 * используется сборкой на GitHub Actions). Создаёт мир, открывает основные экраны, сохраняет скриншоты
 * в папку screenshots и закрывает игру. В обычной игре не работает.
 */
public final class ScreenshotTour {
    public static final boolean ENABLED = "1".equals(System.getenv("ARCHUD_TOUR"));

    private record Step(String name, Runnable action, int wait, BooleanSupplier ready, String shot) {}

    private static final List<Step> STEPS = new ArrayList<>();
    private static int index, timer, waitedForReady, totalTicks;
    private static boolean actionDone;
    private static int lanPort;

    private ScreenshotTour() {}

    private static boolean ru() {
        return Minecraft.getInstance().options.languageCode.startsWith("ru");
    }

    private static void build() {
        Minecraft mc = Minecraft.getInstance();
        STEPS.add(new Step("title", () -> {}, 80, () -> mc.screen != null && mc.getOverlay() == null
                && mc.screen.getClass().getSimpleName().contains("Title"), "01_title_screen"));
        STEPS.add(new Step("create", () -> CreateWorldScreen.openFresh(mc, mc.screen), 50, () -> true, "02_create_world"));
        STEPS.add(new Step("rules", () -> mc.setScreen(new ArcGameRulesScreen(new GameRules(), o -> {})), 40, () -> true, "03_game_rules"));
        STEPS.add(new Step("world", ScreenshotTour::createWorld, 60, () -> mc.level != null && mc.player != null && mc.screen == null, null));
        STEPS.add(new Step("setup", ScreenshotTour::setupWorld, 500, () -> true, null));
        STEPS.add(new Step("hud", () -> mc.setScreen(null), 40, () -> true, "04_hud"));
        STEPS.add(new Step("map", () -> mc.setScreen(new WorldMapScreen(null)), 60, () -> true, "05_world_map"));
        STEPS.add(new Step("waypoints", () -> mc.setScreen(new WaypointListScreen(null)), 40, () -> true, "06_waypoints"));
        STEPS.add(new Step("settings", () -> mc.setScreen(new ArcConfigScreen(null)), 40, () -> true, "07_settings"));
        STEPS.add(new Step("controls", () -> mc.setScreen(new ArcControlsScreen(null, mc.options)), 40, () -> true, "08_controls"));
        STEPS.add(new Step("layout", () -> mc.setScreen(new HudLayoutScreen(null)), 40, () -> true, "09_hud_layout"));
        STEPS.add(new Step("inventory", () -> mc.setScreen(new InventoryScreen(mc.player)), 40, () -> true, "10_inventory"));
        STEPS.add(new Step("inspect", () -> {
            mc.setScreen(null);
            mc.player.getInventory().selected = 0;
            WeaponInspect.start(mc);
        }, 24, () -> true, "11_item_inspect"));
        STEPS.add(new Step("camera", () -> {
            WeaponInspect.stop();
            ArcCamera.toggle(mc);
        }, 80, () -> true, "12_third_person_camera"));
        STEPS.add(new Step("pause", () -> {
            ArcCamera.toggle(mc);
            var server = mc.getSingleplayerServer();
            if (server != null && !server.isPublished()) {
                lanPort = HttpUtil.getAvailablePort();
                server.publishServer(GameType.SURVIVAL, true, lanPort);
            }
            mc.setScreen(new PauseScreen(true));
        }, 40, () -> true, "13_pause_menu"));
        STEPS.add(new Step("host", () -> mc.setScreen(new HostPanelScreen(null)), 50, () -> true, "14_host_panel"));
        STEPS.add(new Step("multiplayer", () -> {
            ServerList list = new ServerList(mc);
            list.load();
            list.add(new ServerData("Arc HUD Server", "localhost:" + lanPort, false), false);
            list.save();
            mc.setScreen(new JoinMultiplayerScreen(new TitleScreen()));
        }, 160, () -> true, "15_multiplayer"));
        STEPS.add(new Step("leave", () -> {
            mc.setScreen(null);
            if (mc.level != null) mc.level.disconnect();
            mc.clearLevel(new GenericDirtMessageScreen(Component.translatable("menu.savingLevel")));
            mc.setScreen(new TitleScreen());
        }, 60, () -> mc.level == null, null));
        STEPS.add(new Step("singleplayer", () -> mc.setScreen(new SelectWorldScreen(mc.screen)), 80, () -> true, "16_singleplayer"));
        STEPS.add(new Step("quit", () -> {
            ArcHud.LOGGER.info("ARCHUD_TOUR: done, no crashes");
            mc.stop();
        }, 1000, () -> true, null));
    }

    private static void createWorld() {
        Minecraft mc = Minecraft.getInstance();
        GameRules rules = new GameRules();
        LevelSettings settings = new LevelSettings(ru() ? "Мир Arc HUD" : "Arc HUD World", GameType.SURVIVAL, false, Difficulty.NORMAL, true,
                rules, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel("archud_tour", settings, new WorldOptions(20261023L, true, false),
                ra -> ra.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.NORMAL).value().createWorldDimensions());
    }

    private static void setupWorld() {
        Minecraft mc = Minecraft.getInstance();
        var c = mc.getConnection();
        if (c == null || mc.player == null) return;
        for (String cmd : new String[]{"time set 1000", "weather clear", "gamerule doDaylightCycle false", "gamerule doWeatherCycle false",
                "gamerule doMobSpawning false",
                "give @s diamond_sword{Enchantments:[{id:\"minecraft:sharpness\",lvl:5},{id:\"minecraft:unbreaking\",lvl:3},{id:\"minecraft:looting\",lvl:3}]} 1",
                "give @s bow", "give @s arrow 64", "give @s cooked_beef 24", "give @s totem_of_undying 2", "give @s torch 64",
                "item replace entity @s armor.chest with diamond_chestplate", "item replace entity @s armor.head with iron_helmet",
                "item replace entity @s weapon.offhand with shield", "effect give @s speed 999999 0 true",
                "effect give @s night_vision 999999 0 true"}) {
            c.sendCommand(cmd);
        }
        mc.player.getInventory().selected = 0;
        mc.player.setXRot(8F);
        // метки вокруг
        int x = mc.player.getBlockX(), y = mc.player.getBlockY(), z = mc.player.getBlockZ();
        String dim = mc.level.dimension().location().toString();
        boolean ru = ru();
        Waypoint home = new Waypoint(ru ? "Дом" : "Home", x + 24, y, z - 18, Waypoint.PALETTE[0], dim, Waypoint.Source.USER);
        WaypointManager.add(home);
        WaypointManager.add(new Waypoint(ru ? "Шахта" : "Mine", x - 40, y, z + 30, Waypoint.PALETTE[3], dim, Waypoint.Source.USER));
        WaypointManager.add(new Waypoint(ru ? "Деревня" : "Village", x + 70, y, z + 55, Waypoint.PALETTE[2], dim, Waypoint.Source.USER));
        WaypointManager.add(new Waypoint(ru ? "Портал" : "Portal", x - 65, y, z - 50, Waypoint.PALETTE[4], dim, Waypoint.Source.USER));
        Navigator.set(home);
    }

    /** Вызывается каждый клиентский тик (до проверки на игрока). */
    public static void tick(Minecraft mc) {
        if (!ENABLED) return;
        if (STEPS.isEmpty()) build();
        if (index >= STEPS.size()) return;
        if (++totalTicks > 20 * 60 * 12) { // страховка: 12 минут
            ArcHud.LOGGER.error("ARCHUD_TOUR: timeout at step {}", STEPS.get(index).name());
            mc.stop();
            return;
        }
        Step s = STEPS.get(index);
        try {
            if (!actionDone) {
                // для первого шага ждём, пока загрузится главное меню
                if (index == 0 && !s.ready().getAsBoolean()) return;
                s.action().run();
                actionDone = true;
                timer = 0;
                waitedForReady = 0;
                return;
            }
            if (!s.ready().getAsBoolean()) {
                if (++waitedForReady > 20 * 120) {
                    ArcHud.LOGGER.error("ARCHUD_TOUR: step {} never became ready", s.name());
                    next();
                }
                return;
            }
            if (++timer < s.wait()) return;
            if (s.shot() != null) {
                Screenshot.grab(mc.gameDirectory, s.shot() + ".png", mc.getMainRenderTarget(),
                        msg -> ArcHud.LOGGER.info("ARCHUD_TOUR: shot {} -> {}", s.shot(), msg.getString()));
            }
            next();
        } catch (Throwable t) {
            ArcHud.LOGGER.error("ARCHUD_TOUR: step {} failed", s.name(), t);
            next();
        }
    }

    private static void next() {
        index++;
        actionDone = false;
        timer = 0;
    }
}
