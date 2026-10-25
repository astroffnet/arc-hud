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
        build();
    }

    private static void build() {
        add(200, mc -> {
            mc.options.guiScale().set(3);
            mc.options.onboardAccessibility = false;
            mc.options.tutorialStep = net.minecraft.client.tutorial.TutorialSteps.NONE;
            mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
            mc.options.save();
            ArcConfig.get().fps = false; // в CI программный рендер — FPS на скринах не показываем
            mc.resizeDisplay();
            mc.setScreen(new TitleScreen());
            title = mc.screen;
        }, null);
        add(60, mc -> {}, "01_title_screen");
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
        add(10, mc -> lookAt(mc, 0), null);
        add(40, mc -> {}, "06_waypoints_in_world");
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
        add(10, mc -> {
            ArcConfig.get().firstPersonStyle = 0;
            mc.options.setCameraType(ArcCamera.nextPerspective(mc.options.getCameraType())); // F5: улучшенный
            ArcHud.LOGGER.info("[Showcase] F5 -> {} style {}", mc.options.getCameraType(), ArcConfig.get().firstPersonStyle);
            if (mc.player != null) mc.player.setXRot(62F);
        }, null);
        add(40, mc -> {}, "09b_first_person_enhanced");
        add(10, mc -> {
            mc.options.setCameraType(ArcCamera.nextPerspective(mc.options.getCameraType())); // F5: бодикам
            ArcHud.LOGGER.info("[Showcase] F5 -> {} style {}", mc.options.getCameraType(), ArcConfig.get().firstPersonStyle);
            lookAt(mc, 0);
        }, null);
        add(60, mc -> {}, "09c_bodycam");
        add(10, mc -> {
            mc.options.setCameraType(ArcCamera.nextPerspective(mc.options.getCameraType())); // F5: камера Arc
            ArcHud.LOGGER.info("[Showcase] F5 -> {} style {} arc {}", mc.options.getCameraType(), ArcConfig.get().firstPersonStyle, ArcCamera.active());
            ArcCamera.toggle(mc); // обратно к первому лицу
            mc.gui.setOverlayMessage(net.minecraft.network.chat.Component.empty(), false);
        }, null);
        add(60, mc -> mc.setScreen(new WorldMapScreen(null)), null);
        add(60, mc -> {}, "10_world_map");
        add(10, mc -> mc.setScreen(new WaypointListScreen(null)), null);
        add(30, mc -> {}, "11_waypoint_menu");
        add(10, mc -> mc.setScreen(new net.minecraft.client.gui.screens.PauseScreen(true)), null);
        add(40, mc -> {}, "12_pause_menu");
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
            if (mc.screen instanceof com.arc4io.archud.client.screen.ArcEnchantmentScreen s) s.showcasePick();
        }, null);
        add(30, mc -> {}, "15c_enchanting_pick");
        add(10, mc -> {
            if (mc.player != null) mc.player.closeContainer();
        }, null);
        add(10, mc -> mc.setScreen(new HostPanelScreen(null)), null);
        add(40, mc -> {}, "16_host_panel");
        add(10, mc -> mc.setScreen(new net.minecraft.client.gui.screens.OptionsScreen(null, mc.options)), null);
        add(30, mc -> {}, "17_options");
        add(10, mc -> {
            mc.setScreen(null);
            if (mc.level != null) mc.level.disconnect();
            mc.clearLevel(new net.minecraft.client.gui.screens.GenericDirtMessageScreen(net.minecraft.network.chat.Component.literal("...")));
            mc.setScreen(new SelectWorldScreen(new TitleScreen()));
        }, null);
        add(100, mc -> {}, "18_singleplayer");
        add(40, mc -> {
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

    public static void tick(Minecraft mc) {
        if (!enabled) return;
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
}
