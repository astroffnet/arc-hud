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
import com.arc4io.archud.client.screen.ArcGameRulesScreen;
import com.arc4io.archud.client.screen.HostPanelScreen;
import com.arc4io.archud.client.screen.HudLayoutScreen;
import com.arc4io.archud.config.ArcConfig;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Автоматическая «витрина» для проверки мода и скриншотов (только при переменной окружения ARCHUD_SHOWCASE=1,
 * используется в CI). Проходит по экранам, создаёт мир, делает снимки и закрывает игру.
 * У обычных игроков не работает.
 */
public final class ShowcaseDriver {
    private record Step(int delay, Consumer<Minecraft> action, String shot) {}

    private static final List<Step> STEPS = new ArrayList<>();
    private static int index = -1, timer, guard;
    private static boolean enabled;
    /** Показать Tab в одиночной игре (ванилла скрывает его, когда игрок один). */
    public static boolean forceTab;
    private static Screen title;
    private static volatile double[] spot;

    private ShowcaseDriver() {}

    public static void init() {
        enabled = "1".equals(System.getenv("ARCHUD_SHOWCASE"));
        if (!enabled) return;
        ArcHud.LOGGER.info("[Showcase] enabled");
        String e2e = System.getenv("ARCHUD_E2E");
        if (e2e != null && !e2e.isBlank()) buildE2e(e2e.trim());
        else build();
    }

    private static void build() {
        add(200, mc -> {
            mc.options.guiScale().set(3);
            ArcConfig.get().crosshairStyle = 2;
            installCitTestPack(mc);
            mc.options.onboardAccessibility = false;
            mc.options.tutorialStep = net.minecraft.client.tutorial.TutorialSteps.NONE;
            mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
            mc.options.save();
            ArcConfig.get().fps = false; // в CI программный рендер — FPS на скринах не показываем
            mc.resizeDisplay();
            mc.setScreen(new TitleScreen());
            title = mc.screen;
        }, null);
        add(5, mc -> org.lwjgl.glfw.GLFW.glfwSetCursorPos(mc.getWindow().getWindow(), 1300, 150), null);
        add(60, mc -> {}, "01_title_screen");
        add(5, mc -> org.lwjgl.glfw.GLFW.glfwSetCursorPos(mc.getWindow().getWindow(), 1890, 1000), null);
        add(20, mc -> {}, "01b_title_mouse_right_low");
        add(5, mc -> org.lwjgl.glfw.GLFW.glfwSetCursorPos(mc.getWindow().getWindow(), 960, 540), null);
        add(40, mc -> mc.setScreen(new JoinMultiplayerScreen(new TitleScreen())), null);
        add(10, mc -> {
            ServerList list = new ServerList(mc);
            list.load();
            if (list.size() == 0) {
                list.add(new ServerData("Hypixel", "mc.hypixel.net", false), false);
                list.add(new ServerData("2b2t", "2b2t.org", false), false);
                list.add(new ServerData("Mineplex", "us.mineplex.com", false), false);
                list.save();
            }
            ArcConfig.get().lastServerIp = "mc.hypixel.net";
            ArcConfig.get().lastServerName = "Hypixel";
            mc.setScreen(new JoinMultiplayerScreen(new TitleScreen()));
        }, null);
        add(160, mc -> {}, "02_multiplayer");
        add(5, mc -> {
            if (mc.screen instanceof com.arc4io.archud.client.screen.ArcMultiplayerScreen m) m.showcaseSelectFirst();
        }, null);
        add(120, mc -> {}, "02b_multiplayer_details");
        add(10, mc -> CreateWorldScreen.openFresh(mc, new TitleScreen()), null);
        add(60, mc -> {}, "03_create_world");
        add(10, mc -> mc.setScreen(new ArcGameRulesScreen(new GameRules(), o -> mc.setScreen(new TitleScreen()))), null);
        add(40, mc -> {}, "04_game_rules");
        add(10, mc -> {
            mc.setScreen(null);
            LevelSettings settings = new LevelSettings("Arc HUD", GameType.SURVIVAL, false, Difficulty.NORMAL, true, new GameRules(),
                    WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel("ArcHudShowcase", settings, new WorldOptions(20250101L, true, false),
                    ra -> ra.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.NORMAL).value().createWorldDimensions());
        }, null);
        // ждём мир
        addWaitWorld();
        add(10, ShowcaseDriver::prepareWorld, null);
        add(200, mc -> mc.setScreen(null), null); // прогрузка чанков
        add(10, ShowcaseDriver::addWaypoints, null);
        add(600, mc -> mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE), null); // карта
        add(10, ShowcaseDriver::returnToSpot, null);
        add(30, mc -> lookAt(mc, 1), null);
        add(40, mc -> {}, "05_hud_and_minimap");
        add(60, mc -> ArcHud.LOGGER.info("[Showcase] FPS square minimap: " + mc.getFps()), null);
        add(5, mc -> {
            var c = com.arc4io.archud.config.ArcConfig.get();
            c.minimapCircle = true;
            c.minimapChunkGrid = true;
            c.minimapSlimeChunks = true;
        }, null);
        add(60, mc -> ArcHud.LOGGER.info("[Showcase] FPS round minimap + grid + slime: " + mc.getFps()), "05b_minimap_round");
        add(5, mc -> {
            var c = com.arc4io.archud.config.ArcConfig.get();
            c.minimapCircle = false;
            c.minimapChunkGrid = false;
            c.minimapSlimeChunks = false;
        }, null);
        add(10, mc -> lookAt(mc, 0), null);
        add(40, mc -> {}, "06_waypoints_in_world");
        add(5, mc -> {
            var c = ArcConfig.get();
            c.shaderEnabled = true;
            c.renderScale = 70;
        }, null);
        add(40, mc -> ArcHud.LOGGER.info("[Showcase] shader on, render scale {} -> {}", ArcConfig.get().renderScale,
                com.arc4io.archud.client.fx.RenderScale.info()), "06c_shader_fsr70");
        add(5, mc -> {
            var c = ArcConfig.get();
            c.shaderEnabled = false;
            c.renderScale = 50;
            c.upscaler = 1;
        }, null);
        add(30, mc -> {}, "06d_fsr50");
        add(5, mc -> ArcConfig.get().upscaler = 2, null);
        // в CI рисуется программно (~1–2 кадра/с): ждём, пока TSR накопит несколько десятков кадров
        add(900, mc -> ArcHud.LOGGER.info("[Showcase] TSR on, temporal={} res={} jitterCalls={}", com.arc4io.archud.client.fx.RenderScale.temporal(),
                com.arc4io.archud.client.fx.RenderScale.info(), com.arc4io.archud.client.fx.Taau.jitterCalls), "06e_tsr50");
        add(5, mc -> {
            var c = ArcConfig.get();
            c.renderScale = 100;
            c.upscaler = 1;
        }, null);
        add(5, mc -> mc.options.renderDebug = true, null);
        add(30, mc -> {}, "06b_f3");
        add(5, mc -> mc.options.renderDebug = false, null);
        add(10, mc -> forceTab = true, null);
        add(20, mc -> {}, "07_player_list");
        add(10, mc -> {
            forceTab = false;
            ArcCamera.toggle(mc);
            mc.gui.setOverlayMessage(net.minecraft.network.chat.Component.empty(), false);
        }, null);
        add(80, mc -> {}, "08_third_person_camera");
        add(10, mc -> {
            ArcCamera.toggle(mc);
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            mc.gui.setOverlayMessage(net.minecraft.network.chat.Component.empty(), false);
            WeaponInspect.start(mc);
        }, null);
        add(26, mc -> {}, "09_item_inspect");
        add(5, mc -> ArcConfig.get().firstPersonStyle = 0, null);
        add(10, mc -> {
            mc.options.setCameraType(ArcCamera.nextPerspective(mc.options.getCameraType())); // F5: бодикам
            ArcHud.LOGGER.info("[Showcase] F5 -> {} style {}", mc.options.getCameraType(), ArcConfig.get().firstPersonStyle);
            lookAt(mc, 0);
        }, null);
        add(60, mc -> {}, "09c_bodycam");
        // вплотную к стене: раньше было видно сквозь блоки
        add(5, mc -> {
            var sp = mc.getSingleplayerServer();
            if (sp == null || mc.player == null) return;
            net.minecraft.core.BlockPos base = mc.player.blockPosition();
            java.util.UUID id = mc.player.getUUID();
            sp.execute(() -> {
                var sl = sp.getPlayerList().getPlayer(id);
                if (sl == null) return;
                var lvl = sl.serverLevel();
                // стена из камня перед игроком (на юг), 3x3
                for (int dx = -1; dx <= 1; dx++)
                    for (int dy = 0; dy <= 2; dy++)
                        lvl.setBlockAndUpdate(base.offset(dx, dy, 1), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                sl.teleportTo(lvl, base.getX() + 0.5, base.getY(), base.getZ() + 0.69, 0F, 10F); // лицом к стене, вплотную
            });
        }, null);
        add(40, mc -> {}, "09d_bodycam_wall");
        add(10, mc -> {
            mc.options.setCameraType(ArcCamera.nextPerspective(mc.options.getCameraType())); // F5: камера Arc
            ArcHud.LOGGER.info("[Showcase] F5 -> {} style {} arc {}", mc.options.getCameraType(), ArcConfig.get().firstPersonStyle, ArcCamera.active());
            ArcCamera.toggle(mc); // обратно к первому лицу
            mc.gui.setOverlayMessage(net.minecraft.network.chat.Component.empty(), false);
        }, null);
        add(60, mc -> mc.setScreen(new WorldMapScreen(null)), null);
        add(60, mc -> {}, "10_world_map");
        add(10, mc -> mc.setScreen(new com.arc4io.archud.client.map.WorldMapScreen(null).focusOn(mc.player.getUUID())), null);
        add(40, mc -> {}, "10b_map_players");
        add(10, mc -> mc.setScreen(new WaypointListScreen(null)), null);
        add(30, mc -> {}, "11_waypoint_menu");
        add(100, ShowcaseDriver::dropTestMod, null); // «закинули мод в папку» — наблюдатель замечает за ~4 с
        add(10, mc -> mc.setScreen(new net.minecraft.client.gui.screens.PauseScreen(true)), null);
        add(40, mc -> {}, "12_pause_menu");
        // сохранение мира: кнопка «Сохранить мир» (как нажатие) и проверка, что сервер сохранил
        add(5, mc -> {
            int before = com.arc4io.archud.server.WorldSaver.saveCount();
            com.arc4io.archud.client.WorldSaveClient.saveNow(mc);
            ArcHud.LOGGER.info("[Showcase] save requested, count before={}", before);
        }, null);
        add(60, mc -> ArcHud.LOGGER.info("[Showcase] save state={} count={} ms={} glCurrent={} glMax={}", com.arc4io.archud.server.WorldSaver.state(),
                com.arc4io.archud.server.WorldSaver.saveCount(), com.arc4io.archud.server.WorldSaver.lastDurationMs(),
                com.arc4io.archud.client.gfx.GlContext.current(), com.arc4io.archud.client.gfx.GlContext.maxSupported()), null);
        add(10, mc -> mc.setScreen(new com.arc4io.archud.client.screen.ArcPauseScreen()), null);
        add(30, mc -> {}, "12b_pause_saved");
        add(10, mc -> mc.setScreen(new ArcConfigScreen(null)), null);
        add(30, mc -> {}, "13_settings");
        add(10, mc -> mc.setScreen(new HudLayoutScreen(null)), null);
        add(30, mc -> {}, "14_hud_layout");
        add(10, mc -> mc.setScreen(new InventoryScreen(mc.player)), null);
        add(30, mc -> {}, "15_inventory");
        add(10, mc -> {
            if (mc.player != null) mc.player.closeContainer();
            openEnchanting(mc);
        }, null);
        add(60, mc -> {}, "15b_enchanting");
        add(5, mc -> {
            if (mc.player != null) mc.player.closeContainer();
            setMode(mc, net.minecraft.world.level.GameType.CREATIVE);
        }, null);
        add(20, mc -> mc.setScreen(new net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen(mc.player,
                mc.player.connection.enabledFeatures(), false)), null);
        add(40, mc -> {}, "15d_creative_home");
        add(5, mc -> creative(mc, s -> {
            s.showcaseTab("ALL");
            s.showcaseSelect();
        }), null);
        add(20, mc -> {}, "15e_creative_all");
        add(5, mc -> creative(mc, s -> s.showcaseInventory(true)), null);
        add(20, mc -> {}, "15e2_creative_inventory");
        add(5, mc -> creative(mc, s -> s.showcaseInventory(false)), null);
        add(5, mc -> creative(mc, s -> s.showcaseTab("ENCHANTS")), null);
        add(20, mc -> {}, "15f_creative_enchants");
        add(5, mc -> creative(mc, s -> {
            s.showcaseList(true);
            s.showcaseTab("POTIONS");
        }), null);
        add(20, mc -> {}, "15g_creative_potions_list");
        add(5, mc -> creative(mc, s -> {
            s.showcaseList(false);
            s.showcaseTab("MODS");
        }), null);
        add(20, mc -> {}, "15h_creative_mods");
        add(5, mc -> creative(mc, s -> s.showcaseWorkshop()), null);
        add(20, mc -> {}, "15i_creative_workshop");
        add(5, mc -> creative(mc, s -> s.showcaseTab("HOME")), null);
        add(5, mc -> {
            mc.setScreen(null);
            setMode(mc, net.minecraft.world.level.GameType.SURVIVAL);
        }, null);
        add(20, mc -> openEnchanting(mc), null);
        add(5, mc -> {
            if (mc.screen instanceof com.arc4io.archud.client.screen.ArcEnchantmentScreen s) s.showcasePick();
        }, null);
        add(30, mc -> {}, "15c_enchanting_pick");
        add(10, mc -> {
            if (mc.player != null) mc.player.closeContainer();
        }, null);
        for (String t : new String[]{"PLAYERS", "WORLD", "SERVER", "LISTS"}) {
            add(10, mc -> {
                com.arc4io.archud.client.screen.AdminPanelScreen.showcase(t, mc.player.getGameProfile().getName());
                mc.setScreen(new com.arc4io.archud.client.screen.AdminPanelScreen(null));
            }, null);
            add(40, mc -> ArcHud.LOGGER.info("[Showcase] admin allowed={} state={}", com.arc4io.archud.client.ArcClientNet.adminAllowed(),
                    com.arc4io.archud.client.ArcClientNet.adminState() != null), "16a_admin_" + t.toLowerCase());
        }
        add(10, mc -> mc.setScreen(new HostPanelScreen(null)), null);
        add(40, mc -> {}, "16_host_panel");
        add(10, mc -> mc.setScreen(new net.minecraft.client.gui.screens.OptionsScreen(null, mc.options)), null);
        add(30, mc -> {}, "17_options");
        add(10, mc -> mc.setScreen(new net.minecraft.client.gui.screens.VideoSettingsScreen(mc.screen, mc.options)), null);
        add(40, mc -> ArcHud.LOGGER.info("[Showcase] video screen: {}", mc.screen == null ? "none" : mc.screen.getClass().getName()), "17c_video");
        for (int fromEnd : new int[]{4, 3, 2, 1}) {
            add(10, mc -> {
                com.arc4io.archud.client.video.ArcVideoScreen.showcaseFromEnd = fromEnd;
                net.minecraft.client.gui.screens.Screen parent = mc.screen;
                if (parent == null) {
                    parent = new net.minecraft.client.gui.screens.TitleScreen();
                    parent.init(mc, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
                }
                mc.setScreen(new net.minecraft.client.gui.screens.VideoSettingsScreen(parent, mc.options));
            }, null);
            add(10, mc -> {
                if (mc.screen instanceof com.arc4io.archud.client.video.ArcVideoScreen v) v.selectFromEnd(fromEnd);
            }, null);
            add(40, mc -> {}, fromEnd == 4 ? "17c2_optimization" : fromEnd == 3 ? "17c3_effects" : fromEnd == 2 ? "17c5_crosshair" : "17c4_api");
        }
        add(10, mc -> {
            mc.setScreen(null);
            mc.options.guiScale().set(4);
            mc.resizeDisplay();
            mc.setScreen(new WorldMapScreen(null));
        }, null);
        add(40, mc -> {}, "17d_map_scale4");
        add(10, mc -> {
            mc.setScreen(null);
            mc.options.guiScale().set(3);
            mc.resizeDisplay();
        }, null);
        add(10, mc -> {
            mc.setScreen(null);
            long t = net.minecraft.Util.getMillis();
            mc.getLanguageManager().setSelected("ru_ru");
            mc.options.languageCode = "ru_ru";
            com.arc4io.archud.client.resources.FastReload.applyLanguage(mc);
            ArcHud.LOGGER.info("[Showcase] language ru_ru applied in {} ms, sample: {}", net.minecraft.Util.getMillis() - t,
                    net.minecraft.network.chat.Component.translatable("item.twilightforest.naga_scale").getString() + " / "
                            + net.minecraft.network.chat.Component.translatable("menu.options").getString());
            mc.setScreen(new InventoryScreen(mc.player));
        }, null);
        add(30, mc -> {}, "17b_russian_fast");
        // «импорт» встроенного пака: тот же пак zip-файлом в папке resourcepacks, включение через экран паков
        add(10, ShowcaseDriver::importRuPack, null);
        add(120, mc -> ArcHud.LOGGER.info("[Showcase] imported pack applied, selected: {}", mc.options.resourcePacks), "17c2_ru_pack_imported");
        add(10, mc -> {
            mc.setScreen(null);
            if (mc.level != null) mc.level.disconnect();
            mc.clearLevel(new net.minecraft.client.gui.screens.GenericDirtMessageScreen(net.minecraft.network.chat.Component.literal("...")));
            mc.setScreen(new SelectWorldScreen(new TitleScreen()));
        }, null);
        add(100, mc -> {}, "18_singleplayer");
        add(10, mc -> mc.setScreen(new TitleScreen()), null);
        add(60, mc -> {}, "19_title_mods_changed");
        // фоновая перезагрузка ресурсов (без экрана загрузки)
        add(10, mc -> {
            mc.setScreen(null);
            mc.reloadResourcePacks();
            ArcHud.LOGGER.info("[Showcase] background reload started, active={} overlay={}",
                    com.arc4io.archud.client.resources.BackgroundReload.active(), mc.getOverlay());
        }, null);
        add(6, mc -> {}, "22_bg_reload");
        add(200, mc -> ArcHud.LOGGER.info("[Showcase] background reload active={} overlay={}",
                com.arc4io.archud.client.resources.BackgroundReload.active(), mc.getOverlay()), null);
        // обычная перезагрузка (как с OptiFine) — экран загрузки в стиле Arc вместо красного Mojang
        add(10, mc -> {
            com.arc4io.archud.config.ArcConfig.get().backgroundReload = false;
            mc.reloadResourcePacks();
        }, null);
        add(25, mc -> ArcHud.LOGGER.info("[Showcase] styled reload overlay={}", mc.getOverlay()), "22b_styled_reload");
        add(200, mc -> com.arc4io.archud.config.ArcConfig.get().backgroundReload = true, null);
        // мод без кода — подгружается без перезапуска
        add(5, ShowcaseDriver::dropResourceMod, null);
        add(300, mc -> ArcHud.LOGGER.info("[Showcase] hot-load packs={} restartPending={}",
                mc.options.resourcePacks, com.arc4io.archud.client.mods.ModWatcher.changes()), null);
        // свой список модов
        add(10, mc -> mc.setScreen(new com.arc4io.archud.client.mods.ArcModListScreen(null)), null);
        add(40, mc -> ArcHud.LOGGER.info("[Showcase] mod list: {} mods, configurable: {}",
                com.arc4io.archud.client.ArcClient.platform().mods().size(),
                com.arc4io.archud.client.ArcClient.platform().mods().stream().filter(m -> m.config() != null).map(m -> m.id()).toList()), "21_mod_list");
        // антикрашер: искусственная ошибка в тике клиента → экран восстановления вместо закрытия игры
        add(10, mc -> {
            mc.setScreen(null);
            ArcHud.LOGGER.info("[Showcase] throwing a test crash");
            crashNext = true;
        }, null);
        add(80, mc -> ArcHud.LOGGER.info("[Showcase] after crash: screen={} level={}",
                mc.screen == null ? "none" : mc.screen.getClass().getSimpleName(), mc.level != null), "20_crash_recovered");
        add(40, mc -> {
            try {
                java.nio.file.Files.deleteIfExists(mc.gameDirectory.toPath().resolve("mods").resolve("showcase-test-mod.jar"));
            } catch (Exception ignored) {
            }
            try {
                try (var worlds = java.nio.file.Files.list(mc.gameDirectory.toPath().resolve("saves"))) {
                    for (java.nio.file.Path root : worlds.toList()) {
                        for (String d : new String[]{"playerdata", "advancements", "stats"}) {
                            if (!java.nio.file.Files.isDirectory(root.resolve(d))) continue;
                            try (var files = java.nio.file.Files.list(root.resolve(d))) {
                                ArcHud.LOGGER.info("[Showcase] {}/{}: {}", root.getFileName(), d, files.map(f -> f.getFileName().toString()).toList());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                ArcHud.LOGGER.info("[Showcase] storage listing: " + e);
            }
            ArcHud.LOGGER.info("[Showcase] done");
            mc.stop();
        }, null);
    }

    private static void add(int delay, Consumer<Minecraft> action, String shot) {
        STEPS.add(new Step(delay, action, shot));
    }

    private static final Consumer<Minecraft> WAIT_WORLD = mc -> {};

    private static void addWaitWorld() {
        STEPS.add(new Step(-1, WAIT_WORLD, null));
    }

    /** Мир для скриншотов: полдень, ясно, снаряжение, метки вокруг, навигатор. */
    private static void prepareWorld(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return;
        var uuid = mc.player.getUUID();
        server.execute(() -> {
            ServerLevel lvl = server.overworld();
            lvl.setDayTime(6000);
            lvl.setWeatherParameters(12000, 0, false, false);
            server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
            ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
            if (sp == null) return;
            ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
            sword.enchant(Enchantments.SHARPNESS, 5);
            sword.enchant(Enchantments.UNBREAKING, 3);
            sword.enchant(Enchantments.MOB_LOOTING, 3);
            sword.setDamageValue(400);
            sp.getInventory().setItem(0, sword);
            sp.getInventory().setItem(1, new ItemStack(Items.BOW));
            sp.getInventory().setItem(2, new ItemStack(Items.ARROW, 48));
            sp.getInventory().setItem(3, new ItemStack(Items.COOKED_BEEF, 24));
            sp.getInventory().setItem(4, new ItemStack(Items.TOTEM_OF_UNDYING));
            sp.getInventory().setItem(5, new ItemStack(Items.DIAMOND_PICKAXE));
            sp.getInventory().setItem(8, new ItemStack(Items.TORCH, 64));
            ItemStack excalibur = new ItemStack(Items.DIAMOND_SWORD);
            excalibur.setHoverName(net.minecraft.network.chat.Component.literal("Excalibur"));
            sp.getInventory().setItem(6, excalibur);
            sp.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            sp.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
            sp.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
            sp.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
            sp.getInventory().selected = 0;
            sp.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 20 * 600, 0, false, false, true));
            sp.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 20 * 600, 0, false, false, true));
            sp.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 20 * 90, 1, false, false, true));
            sp.setHealth(15F);
            sp.getFoodData().setFoodLevel(16);
            sp.giveExperienceLevels(23);
            server.setDifficulty(Difficulty.PEACEFUL, true);
            int tx = sp.getBlockX(), tz = sp.getBlockZ();
            try {
                var found = lvl.findClosestBiome3d(b -> b.is(net.minecraft.world.level.biome.Biomes.PLAINS)
                        || b.is(net.minecraft.world.level.biome.Biomes.SUNFLOWER_PLAINS) || b.is(net.minecraft.world.level.biome.Biomes.MEADOW)
                        || b.is(net.minecraft.world.level.biome.Biomes.SAVANNA), sp.blockPosition(), 3000, 32, 64);
                if (found != null) {
                    tx = found.getFirst().getX();
                    tz = found.getFirst().getZ();
                }
            } catch (Throwable t) {
                ArcHud.LOGGER.warn("[Showcase] biome search failed", t);
            }
            // сухая точка (не на воде): ищем вокруг найденного места
            int bestX = tx, bestZ = tz;
            search:
            for (int r = 0; r <= 48; r += 2) {
                for (int dx = -r; dx <= r; dx += 2) for (int dz = -r; dz <= r; dz += 2) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = tx + dx, z = tz + dz;
                    lvl.getChunk(x >> 4, z >> 4);
                    int h = lvl.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    var below = new net.minecraft.core.BlockPos(x, h - 1, z);
                    if (lvl.getFluidState(below).isEmpty() && lvl.getBlockState(below).isSolid() && h > lvl.getSeaLevel()) {
                        bestX = x;
                        bestZ = z;
                        break search;
                    }
                }
            }
            tx = bestX;
            tz = bestZ;
            lvl.getChunk(tx >> 4, tz >> 4);
            int ty = lvl.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, tx, tz);
            sp.teleportTo(lvl, tx + 0.5, ty + 1, tz + 0.5, 135F, 10F);
            spot = new double[]{tx + 0.5, ty + 1, tz + 0.5};
        });
    }

    /** Тестовый CIT-пакет: меч «Excalibur» получает золотую текстуру. */
    private static void installCitTestPack(Minecraft mc) {
        try {
            java.nio.file.Path root = mc.getResourcePackDirectory().resolve("arc_cit_test");
            java.nio.file.Path cit = root.resolve("assets/minecraft/optifine/cit/swords");
            java.nio.file.Files.createDirectories(cit);
            java.nio.file.Files.writeString(root.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":15,\"description\":\"CIT test\"}}");
            java.nio.file.Files.writeString(cit.resolve("excalibur.properties"), "type=item\nitems=diamond_sword\ntexture=excalibur\nnbt.display.Name=ipattern:*excalibur*\n");
            try (com.mojang.blaze3d.platform.NativeImage img = new com.mojang.blaze3d.platform.NativeImage(16, 16, true)) {
                for (int i = 0; i < 16; i++) {
                    for (int d = -1; d <= 1; d++) {
                        int x = i, y = 15 - i + d;
                        if (y >= 0 && y < 16) img.setPixelRGBA(x, y, 0xFF20D4FF); // ABGR: золотой
                    }
                }
                img.writeToFile(cit.resolve("excalibur.png"));
            }
            var repo = mc.getResourcePackRepository();
            repo.reload();
            java.util.List<String> ids = new java.util.ArrayList<>(repo.getSelectedIds());
            if (!ids.contains("file/arc_cit_test")) ids.add("file/arc_cit_test");
            repo.setSelected(ids);
            mc.options.updateResourcePacks(repo);
            ArcHud.LOGGER.info("[Showcase] CIT test pack installed, selected: {}", repo.getSelectedIds());
        } catch (Exception e) {
            ArcHud.LOGGER.error("[Showcase] CIT test pack failed", e);
        }
    }

    private static void creative(Minecraft mc, Consumer<com.arc4io.archud.client.screen.ArcCreativeScreen> action) {
        if (mc.screen instanceof com.arc4io.archud.client.screen.ArcCreativeScreen s) action.accept(s);
    }

    private static void setMode(Minecraft mc, net.minecraft.world.level.GameType type) {
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return;
        var uuid = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
            if (sp != null) sp.setGameMode(type);
        });
    }

    /** Стол зачарования с книжными полками рядом, предмет и лазурит внутри. */
    private static void openEnchanting(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return;
        var uuid = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
            if (sp == null) return;
            ServerLevel lvl = sp.serverLevel();
            net.minecraft.core.BlockPos c = sp.blockPosition().offset(0, 0, -6);
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                lvl.setBlockAndUpdate(c.offset(dx, -1, dz), net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState());
                for (int dy = 0; dy <= 2; dy++) {
                    boolean ring = Math.max(Math.abs(dx), Math.abs(dz)) == 2 && dy <= 1;
                    lvl.setBlockAndUpdate(c.offset(dx, dy, dz), ring ? net.minecraft.world.level.block.Blocks.BOOKSHELF.defaultBlockState()
                            : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                }
            }
            lvl.setBlockAndUpdate(c, net.minecraft.world.level.block.Blocks.ENCHANTING_TABLE.defaultBlockState());
            var access = net.minecraft.world.inventory.ContainerLevelAccess.create(lvl, c);
            sp.openMenu(new net.minecraft.world.SimpleMenuProvider((id, inv, pl) -> new net.minecraft.world.inventory.EnchantmentMenu(id, inv, access),
                    net.minecraft.network.chat.Component.translatable("container.enchant")));
            if (sp.containerMenu instanceof net.minecraft.world.inventory.EnchantmentMenu m) {
                m.getSlot(0).set(new ItemStack(Items.DIAMOND_CHESTPLATE));
                m.getSlot(1).set(new ItemStack(Items.LAPIS_LAZULI, 10));
                m.broadcastChanges();
            }
        });
    }

    /** Вернуть игрока на выбранную точку (за время прогрузки его могло снести водой). */
    private static void returnToSpot(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        double[] p = spot;
        if (server == null || mc.player == null || p == null) return;
        var uuid = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
            if (sp == null) return;
            sp.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            sp.teleportTo(server.overworld(), p[0], p[1], p[2], sp.getYRot(), 10F);
        });
    }

    private static void addWaypoints(Minecraft mc) {
        if (mc.player == null) return;
        // метки вокруг
        int x = mc.player.getBlockX(), y = mc.player.getBlockY(), z = mc.player.getBlockZ();
        String dim = WaypointManager.currentDimension();
        String[] names = {"Base", "Village", "Mine", "Portal", "Ocean Monument"};
        int[][] off = {{40, 0, 60}, {-120, 0, 30}, {60, -20, -90}, {-60, 0, -140}, {300, 0, 260}};
        Waypoint nav = null;
        for (int i = 0; i < names.length; i++) {
            Waypoint w = new Waypoint(names[i], x + off[i][0], y + off[i][1], z + off[i][2], Waypoint.PALETTE[i], dim, Waypoint.Source.USER);
            WaypointManager.add(w);
            if (i == 0) nav = w;
        }
        if (nav != null) Navigator.set(nav);
    }

    /** Повернуть игрока к метке (0 — «Base», 1 — вдаль, к горизонту). */
    private static void lookAt(Minecraft mc, int which) {
        if (mc.player == null) return;
        if (which == 0) {
            Waypoint t = Navigator.target();
            if (t == null) return;
            double dx = t.x + 0.5 - mc.player.getX(), dz = t.z + 0.5 - mc.player.getZ();
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz)) - 25F;
            mc.player.setYRot(yaw);
            mc.player.setXRot(8F);
        } else {
            var all = WaypointManager.all();
            for (Waypoint t : all) {
                if (!"Village".equals(t.name)) continue;
                double dx = t.x + 0.5 - mc.player.getX(), dz = t.z + 0.5 - mc.player.getZ();
                mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
            }
            mc.player.setXRot(10F);
        }
    }

    private static boolean crashNext;

    public static void tick(Minecraft mc) {
        if (!enabled) return;
        if (crashNext) {
            // вне try/catch шага — исключение уходит в игровой цикл, его должен поймать антикрашер
            crashNext = false;
            throw new IllegalStateException("Arc HUD showcase: test crash");
        }
        if (index < 0) {
            next(mc); // шаг 0
            return;
        }
        if (index >= STEPS.size()) return;
        if (++guard > 20 * 600) {
            ArcHud.LOGGER.error("[Showcase] timeout");
            mc.stop();
            return;
        }
        Step step = STEPS.get(index);
        if (step.delay() == -2) {
            // ждать окончания загрузки ресурсов (оверлей загрузки) и главного меню
            if (mc.getOverlay() == null && mc.screen != null) next(mc);
            return;
        }
        if (step.delay() < 0) {
            // ждать загрузки мира
            if (mc.level != null && mc.player != null && mc.screen == null) next(mc);
            else if (mc.level != null && mc.player != null && mc.screen != null && mc.screen.isPauseScreen()) mc.setScreen(null);
            return;
        }
        if (--timer > 0) return;
        if (step.shot() != null) {
            mc.getToasts().clear();
            Screenshot.grab(mc.gameDirectory, step.shot() + ".png", mc.getMainRenderTarget(), msg -> {});
            ArcHud.LOGGER.info("[Showcase] screenshot {}", step.shot());
        }
        next(mc);
    }

    private static void next(Minecraft mc) {
        index++;
        if (index >= STEPS.size()) return;
        Step s = STEPS.get(index);
        try {
            s.action().accept(mc);
        } catch (Throwable t) {
            ArcHud.LOGGER.error("[Showcase] step {} failed", index, t);
        }
        timer = Math.max(1, s.delay());
    }

    private static void dropResourceMod(Minecraft mc) {
        try {
            java.nio.file.Path dir = com.arc4io.archud.client.ArcClient.platform().modsDir();
            java.nio.file.Files.createDirectories(dir);
            try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(dir.resolve("showcase-res-mod.jar")))) {
                z.putNextEntry(new java.util.zip.ZipEntry("assets/showcase_res/lang/en_us.json"));
                z.write("{\"showcase_res.hello\":\"Hot-loaded!\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                z.closeEntry();
            }
        } catch (Exception e) {
            ArcHud.LOGGER.warn("[Showcase] resource mod: " + e);
        }
    }

    private static void dropTestMod(Minecraft mc) {
        try {
            java.nio.file.Path dir = mc.gameDirectory.toPath().resolve("mods");
            java.nio.file.Files.createDirectories(dir);
            try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(dir.resolve("showcase-test-mod.jar")))) {
                z.putNextEntry(new java.util.zip.ZipEntry("fabric.mod.json"));
                z.write("{\"schemaVersion\":1,\"id\":\"showcase_test\",\"version\":\"1\",\"name\":\"Showcase Test Mod\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                z.closeEntry();
            }
        } catch (Exception e) {
            ArcHud.LOGGER.warn("[Showcase] test mod: " + e);
        }
    }

    private static void importRuPack(Minecraft mc) {
        try {
            var repo = mc.getResourcePackRepository();
            net.minecraft.server.packs.repository.Pack builtin = null;
            for (var p : repo.getAvailablePacks()) if (p.getId().contains("rodnoe")) builtin = p;
            if (builtin == null) {
                ArcHud.LOGGER.info("[Showcase] builtin pack not found");
                return;
            }
            java.nio.file.Path zip = mc.getResourcePackDirectory().resolve("Rodnoe_slovo_ru_RU_by_arc4io.zip");
            try (var res = builtin.open(); var out = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(zip))) {
                var meta = res.getRootResource("pack.mcmeta");
                if (meta != null) {
                    out.putNextEntry(new java.util.zip.ZipEntry("pack.mcmeta"));
                    try (var in = meta.get()) {
                        in.transferTo(out);
                    }
                    out.closeEntry();
                }
                for (String ns : res.getNamespaces(net.minecraft.server.packs.PackType.CLIENT_RESOURCES)) {
                    for (String dir : new String[]{"lang", "patchouli_books", "books"}) res.listResources(net.minecraft.server.packs.PackType.CLIENT_RESOURCES, ns, dir, (loc, sup) -> {
                        try (var in = sup.get()) {
                            out.putNextEntry(new java.util.zip.ZipEntry("assets/" + loc.getNamespace() + "/" + loc.getPath()));
                            in.transferTo(out);
                            out.closeEntry();
                        } catch (Exception e) {
                            ArcHud.LOGGER.warn("[Showcase] zip entry " + loc + ": " + e);
                        }
                    });
                }
            }
            repo.reload();
            java.util.List<String> ids = new java.util.ArrayList<>(repo.getSelectedIds());
            ids.add("file/Rodnoe_slovo_ru_RU_by_arc4io.zip");
            ArcHud.LOGGER.info("[Showcase] importing pack, available: {}", repo.getAvailableIds());
            repo.setSelected(ids);
            mc.options.updateResourcePacks(repo);
            mc.setScreen(new com.arc4io.archud.client.screen.ArcPackScreen(null, mc.options));
        } catch (Throwable e) {
            ArcHud.LOGGER.error("[Showcase] import pack failed", e);
        }
    }

    /**
     * Сквозная проверка с сервером Bukkit/Paper + плагин Arc HUD (ARCHUD_E2E=адрес): подключение, приветствие,
     * синхронизация меток, админ-панель и действие из неё, карта и меню паузы. Результаты — строки [E2E] в логе.
     */
    private static void buildE2e(String address) {
        STEPS.add(new Step(-2, mc -> {}, null)); // ресурсы загружены
        add(100, mc -> {}, null);
        add(20, mc -> {
            mc.options.guiScale().set(3);
            mc.options.onboardAccessibility = false;
            mc.options.tutorialStep = net.minecraft.client.tutorial.TutorialSteps.NONE;
            mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
            mc.options.save();
            mc.resizeDisplay();
            ArcHud.LOGGER.info("[E2E] connecting to {}", address);
            net.minecraft.client.gui.screens.ConnectScreen.startConnecting(new TitleScreen(), mc,
                    net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(address), new ServerData("E2E", address, false), false);
        }, null);
        addWaitWorld();
        add(200, mc -> ArcHud.LOGGER.info("[E2E] welcomed={} modServer={} positions={} mapSync={} waypointsOnServer={} adminAllowed={}",
                com.arc4io.archud.client.ArcClientNet.connected(), com.arc4io.archud.client.ArcClientNet.modServer(),
                com.arc4io.archud.client.ArcClientNet.positionsActive(), com.arc4io.archud.client.ArcClientNet.mapSyncActive(),
                com.arc4io.archud.client.ArcClientNet.waypointsOnServer(), com.arc4io.archud.client.ArcClientNet.adminAllowed()), "e2e_01_joined");
        add(10, mc -> {
            if (mc.player == null) return;
            Waypoint w = new Waypoint("E2E point", mc.player.getBlockX() + 20, mc.player.getBlockY(), mc.player.getBlockZ(), 0xFF4FC3F7,
                    mc.level.dimension().location().toString(), Waypoint.Source.USER);
            WaypointManager.add(w);
            ArcHud.LOGGER.info("[E2E] waypoint added");
        }, null);
        add(120, mc -> {
            com.arc4io.archud.client.screen.AdminPanelScreen.showcase("PLAYERS", mc.player.getGameProfile().getName());
            mc.setScreen(new com.arc4io.archud.client.screen.AdminPanelScreen(null));
        }, null);
        add(100, mc -> ArcHud.LOGGER.info("[E2E] adminAllowed={} adminState={}", com.arc4io.archud.client.ArcClientNet.adminAllowed(),
                com.arc4io.archud.client.ArcClientNet.adminState() != null), "e2e_02_admin_players");
        add(10, mc -> com.arc4io.archud.client.ArcClientNet.adminAction("w_noon", "", ""), null);
        add(60, mc -> ArcHud.LOGGER.info("[E2E] admin result='{}' time={}", com.arc4io.archud.client.ArcClientNet.adminResult(),
                mc.level == null ? -1 : mc.level.getDayTime() % 24000), null);
        add(10, mc -> {
            com.arc4io.archud.client.screen.AdminPanelScreen.showcase("SERVER", mc.player.getGameProfile().getName());
            mc.setScreen(new com.arc4io.archud.client.screen.AdminPanelScreen(null));
        }, null);
        add(60, mc -> {}, "e2e_03_admin_server");
        add(10, mc -> mc.setScreen(new WorldMapScreen(null)), null);
        add(60, mc -> {}, "e2e_04_map");
        add(10, mc -> mc.setScreen(new com.arc4io.archud.client.screen.ArcPauseScreen()), null);
        add(40, mc -> {}, "e2e_05_pause");
        add(20, mc -> {
            ArcHud.LOGGER.info("[E2E] done");
            if (mc.level != null) mc.level.disconnect();
            mc.clearLevel();
            mc.stop();
        }, null);
    }
}
