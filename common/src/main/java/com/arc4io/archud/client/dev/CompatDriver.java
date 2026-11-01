package com.arc4io.archud.client.dev;

import com.arc4io.archud.ArcHud;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
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
 * Проверка совместимости с модами (CI: ARCHUD_SHOWCASE=compat): новый инвентарь со слотами Curios/Aether,
 * эффекты на разных масштабах интерфейса, кнопки модов (Регенерация), способности Legends с надетыми перчатками.
 */
public final class CompatDriver {
    /** Тест: наклон руки от первого лица (как у модов анимаций вроде Punchy), градусы. */
    public static float handTilt;
    private static int[] bed = {0, 0, 0};

    /** Простой шейдерпак в старом стиле OptiFine (GLSL 120, gl_FragData, texture2D, DRAWBUFFERS) для проверки загрузчика. */
    private static void writeTestShaderPack(Minecraft mc) {
        try {
            var dir = mc.gameDirectory.toPath().resolve("shaderpacks").resolve("ArcTest").resolve("shaders");
            java.nio.file.Files.createDirectories(dir);
            String vsh = "#version 120\nvarying vec2 texcoord;\nvoid main() {\n gl_Position = ftransform();\n texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;\n}\n";
            java.nio.file.Files.writeString(dir.resolve("composite.vsh"), vsh);
            java.nio.file.Files.writeString(dir.resolve("final.vsh"), vsh);
            java.nio.file.Files.writeString(dir.resolve("composite.fsh"), "#version 120\n#include \"/lib/tone.glsl\"\n"
                    + "uniform sampler2D colortex0;\nuniform sampler2D depthtex0;\nuniform float viewWidth, viewHeight, frameTimeCounter;\n"
                    + "varying vec2 texcoord;\n/* DRAWBUFFERS:04 */\nvoid main() {\n vec3 c = texture2D(colortex0, texcoord).rgb;\n"
                    + " float d = texture2D(depthtex0, texcoord).r;\n vec3 s = sepia(c);\n"
                    + " gl_FragData[0] = vec4(mix(c, s, 0.8), 1.0);\n gl_FragData[1] = vec4(vec3(pow(d, 64.0)), 1.0);\n}\n");
            java.nio.file.Files.createDirectories(dir.resolve("lib"));
            java.nio.file.Files.writeString(dir.resolve("lib").resolve("tone.glsl"),
                    "vec3 sepia(vec3 c) { return vec3(dot(c, vec3(0.393, 0.769, 0.189)), dot(c, vec3(0.349, 0.686, 0.168)), dot(c, vec3(0.272, 0.534, 0.131))); }\n");
            java.nio.file.Files.writeString(dir.resolve("final.fsh"), "#version 120\nuniform sampler2D gcolor;\nuniform sampler2D gaux1;\n"
                    + "uniform float aspectRatio;\nvarying vec2 texcoord;\nvoid main() {\n vec3 c = texture2D(gcolor, texcoord).rgb;\n"
                    + " vec2 v = texcoord - 0.5; v.x *= aspectRatio;\n c *= 1.0 - dot(v, v) * 0.9;\n"
                    + " if (texcoord.x > 0.8 && texcoord.y > 0.8) c = texture2D(gaux1, (texcoord - 0.8) / 0.2).rgb;\n"
                    + " gl_FragColor = vec4(c, 1.0);\n}\n");
        } catch (Exception e) {
            ArcHud.LOGGER.warn("[Compat] cannot write test shader pack", e);
        }
    }
    /** Тест: как будто перехват отрисовки частей модели не сработал (OptiFine и т. п.). */
    public static boolean noPartHook;
    private record Step(int delay, Consumer<Minecraft> action, String shot) {}

    private static final List<Step> STEPS = new ArrayList<>();
    private static int index = -1, timer, guard;
    private static KeyMapping held;

    private CompatDriver() {}

    public static boolean enabled() {
        return "compat".equals(System.getenv("ARCHUD_SHOWCASE"));
    }

    private static void add(int delay, Consumer<Minecraft> action, String shot) {
        STEPS.add(new Step(delay, action, shot));
    }

    /** Навести курсор на ячейку с предметом в открытом окне. */
    private static void hover(Minecraft mc, net.minecraft.world.item.Item item) {
        if (!(mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> s)) return;
        var acc = (com.arc4io.archud.mixin.ContainerScreenAccessor) s;
        for (Slot sl : s.getMenu().slots) {
            if (!sl.hasItem() || sl.getItem().getItem() != item) continue;
            double scale = mc.getWindow().getGuiScale();
            double x = (acc.archud$left() + sl.x + 8) * scale, y = (acc.archud$top() + sl.y + 8) * scale;
            org.lwjgl.glfw.GLFW.glfwSetCursorPos(mc.getWindow().getWindow(), x, y);
            try {
                var m = mc.mouseHandler.getClass().getDeclaredMethod("onMove", long.class, double.class, double.class);
                m.setAccessible(true);
                m.invoke(mc.mouseHandler, mc.getWindow().getWindow(), x, y);
            } catch (Throwable t) {
                ArcHud.LOGGER.info("[Compat] cannot move mouse: {}", t.toString());
            }
            ArcHud.LOGGER.info("[Compat] hover {} at slot {}", BuiltInRegistries.ITEM.getKey(item), sl.index);
            return;
        }
        ArcHud.LOGGER.info("[Compat] hover: no {} in window", BuiltInRegistries.ITEM.getKey(item));
    }

    private static void press(Minecraft mc, int key) {
        long w = mc.getWindow().getWindow();
        int scan = org.lwjgl.glfw.GLFW.glfwGetKeyScancode(key);
        mc.keyboardHandler.keyPress(w, key, scan, 1, 0);
        mc.keyboardHandler.keyPress(w, key, scan, 0, 0);
    }

    /** Перенести первый рецепт открытого окна рецептов в окно, из которого его открыли. */
    private static void transferFirst(Minecraft mc, String tag) {
        if (!(mc.screen instanceof com.arc4io.archud.client.recipes.ArcRecipeScreen rs)) {
            ArcHud.LOGGER.info("[Compat] {} transfer: no recipe screen", tag);
            return;
        }
        String r = rs.transferFirstForTest();
        ArcHud.LOGGER.info("[Compat] {} transfer: {}", tag, r);
    }

    private static String invOrder(Minecraft mc) {
        StringBuilder sb = new StringBuilder();
        for (int i = 9; i < 36; i++) {
            var st = mc.player.getInventory().getItem(i);
            if (!st.isEmpty()) sb.append(BuiltInRegistries.ITEM.getKey(st.getItem()).getPath()).append('x').append(st.getCount()).append(' ');
        }
        return sb.toString();
    }

    private static void cmd(Minecraft mc, String c) {
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return;
        var uuid = mc.player.getUUID();
        server.execute(() -> {
            var sp = server.getPlayerList().getPlayer(uuid);
            if (sp == null) return;
            server.getCommands().performPrefixedCommand(sp.createCommandSourceStack().withPermission(4).withSuppressedOutput(), c);
        });
    }

    private static KeyMapping key(Minecraft mc, String name) {
        for (KeyMapping k : mc.options.keyMappings) if (k.getName().equals(name)) return k;
        return null;
    }

    private static void ability(Minecraft mc, String name, boolean down) {
        KeyMapping k = key(mc, name);
        if (k == null) {
            ArcHud.LOGGER.info("[Compat] no key {}", name);
            return;
        }
        k.setDown(down);
        if (down) KeyMapping.click(com.mojang.blaze3d.platform.InputConstants.getKey(k.saveString()));
        held = down ? k : null;
    }

    private static void inventoryShots(String tag, int... scales) {
        for (int s : scales) {
            add(5, m -> {
                m.options.guiScale().set(s);
                m.resizeDisplay();
                closeScreen(m);
            }, null);
            add(10, m -> m.setScreen(new InventoryScreen(m.player)), null);
            add(30, m -> ArcHud.LOGGER.info("[Compat] inventory screen at scale {} (fps {}): {}", s, m.getFps(), (m.screen == null ? "none" : m.screen.getClass().getName()) + " menu=" + m.player.containerMenu.getClass().getSimpleName()),
                    tag + "_s" + s);
        }
    }

    private static void build() {
        add(200, mc -> {
            mc.options.guiScale().set(3);
            mc.options.onboardAccessibility = false;
            mc.options.tutorialStep = net.minecraft.client.tutorial.TutorialSteps.NONE;
            mc.options.save();
            mc.setScreen(new TitleScreen());
        }, null);
        add(40, mc -> {
            closeScreen(mc);
            LevelSettings settings = new LevelSettings("Compat", GameType.SURVIVAL, false, Difficulty.NORMAL, true, new GameRules(),
                    WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel("ArcHudCompat", settings, new WorldOptions(20250101L, true, false),
                    ra -> ra.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
        }, null);
        STEPS.add(new Step(-1, mc -> {}, null));
        add(100, mc -> {
            cmd(mc, "time set noon");
            cmd(mc, "weather clear");
            cmd(mc, "gamerule doDaylightCycle false");
            for (String e : new String[]{"speed", "regeneration", "strength", "night_vision", "jump_boost", "haste", "fire_resistance",
                    "water_breathing", "absorption", "luck", "resistance", "slow_falling"}) cmd(mc, "effect give @s minecraft:" + e + " 9999 1");
            for (String it : new String[]{"aether:iron_gloves", "aether:iron_ring", "aether:golden_ring", "aether:iron_pendant", "aether:blue_cape"})
                cmd(mc, "give @s " + it);
            cmd(mc, "item replace entity @s armor.head with legendsmod:steve_head");
            cmd(mc, "item replace entity @s armor.chest with legendsmod:steve_chest");
            cmd(mc, "item replace entity @s armor.legs with legendsmod:steve_legs");
            cmd(mc, "item replace entity @s armor.feet with legendsmod:steve_feet");
            cmd(mc, "give @s minecraft:diamond_sword");
        }, null);
        add(60, mc -> ArcHud.LOGGER.info("[Compat] mods: aether={} curios={} legends={} regen={}", ArcHud.platform().isModLoaded("aether"),
                ArcHud.platform().isModLoaded("curios"), ArcHud.platform().isModLoaded("legendsmod"), ArcHud.platform().isModLoaded("regen")), null);
        inventoryShots("c01_inv", 2, 3, 4);
        // надеть аксессуары shift-кликом в новом инвентаре
        add(10, mc -> {
            if (mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> s) {
                var menu = s.getMenu();
                int moved = 0;
                for (int pass = 0; pass < 6; pass++) {
                    for (int i = 9; i < Math.min(46, menu.slots.size()); i++) {
                        Slot sl = menu.slots.get(i);
                        String id = BuiltInRegistries.ITEM.getKey(sl.getItem().getItem()).toString();
                        if (id.startsWith("aether:")) {
                            mc.gameMode.handleInventoryMouseClick(menu.containerId, i, 0, ClickType.QUICK_MOVE, mc.player);
                            moved++;
                            break;
                        }
                    }
                }
                ArcHud.LOGGER.info("[Compat] menu {} slots={} shift-clicked={}", menu.getClass().getName(), menu.slots.size(), moved);
            }
        }, null);
        add(30, mc -> {
            if (mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> s) {
                List<String> acc = new ArrayList<>();
                var menu = s.getMenu();
                for (int i = 46; i < menu.slots.size(); i++) {
                    Slot sl = menu.slots.get(i);
                    if (sl.hasItem()) acc.add(i + "=" + BuiltInRegistries.ITEM.getKey(sl.getItem().getItem()));
                }
                ArcHud.LOGGER.info("[Compat] accessories equipped: {}", acc);
                if (menu instanceof com.arc4io.archud.menu.AccessoryProvider ap) {
                    ArcHud.LOGGER.info("[Compat] Legends equipment slots: {}", ap.extraAccessorySlots().stream()
                            .map(sl -> sl.getClass().getSimpleName() + ":" + ap.accessoryLabel(sl).getString()).toList());
                }
            }
        }, "c02_inv_equipped");
        // надеть аксессуары как игрок: выбрать в хотбаре и нажать ПКМ (у Aether/Curios это надевает в слот)
        add(10, mc -> closeScreen(mc), null);
        for (int k = 0; k < 9; k++) {
            final int slot = k;
            add(4, mc -> {
                var it = mc.player.getInventory().getItem(slot);
                String id = BuiltInRegistries.ITEM.getKey(it.getItem()).toString();
                if (!id.startsWith("aether:")) return;
                mc.player.getInventory().selected = slot;
                var res = mc.gameMode.useItem(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND);
                ArcHud.LOGGER.info("[Compat] right-click {} in slot {} -> {}", id, slot, res);
            }, null);
        }
        add(20, mc -> {
            List<String> left = new ArrayList<>();
            for (int i = 0; i < 36; i++) {
                String id = BuiltInRegistries.ITEM.getKey(mc.player.getInventory().getItem(i).getItem()).toString();
                if (id.startsWith("aether:")) left.add(i + "=" + id);
            }
            ArcHud.LOGGER.info("[Compat] aether items still in inventory (not equipped): {}", left);
            mc.player.getInventory().selected = 8; // пустая рука
        }, null);
        add(10, mc -> mc.setScreen(new InventoryScreen(mc.player)), null);
        add(30, mc -> {
            if (mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> s) {
                List<String> acc = new ArrayList<>();
                var menu = s.getMenu();
                for (int i = 46; i < menu.slots.size(); i++) {
                    Slot sl = menu.slots.get(i);
                    if (sl.hasItem()) acc.add(i + "=" + BuiltInRegistries.ITEM.getKey(sl.getItem().getItem()));
                }
                ArcHud.LOGGER.info("[Compat] accessory slots after right-click: {} (screen {})", acc, s.getClass().getSimpleName());
            }
        }, "c02b_inv_after_equip");
        // кнопки модов
        add(10, mc -> {
            if (mc.screen != null) ArcHud.LOGGER.info("[Compat] widgets: {}", mc.screen.children().stream().map(c -> c.getClass().getName()).toList());
        }, null);
        add(10, mc -> closeScreen(mc), null);
        // способности Legends: вид от третьего лица спереди, перчатки на руках
        add(20, mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT), "c03_idle");
        add(10, mc -> com.arc4io.archud.client.camera.ArcCamera.devView(mc, 90F, 10F), "c03_idle_side");
        add(5, mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT), null);
        for (String[] ab : new String[][]{{"legendsmod.key.ability_2", "c04_ab2"}, {"legendsmod.key.ability_4", "c05_ab4"}, {"legendsmod.key.ability_1", "c06_ab1"},
                {"legendsmod.key.ability_3", "c07_ab3"}}) {
            add(5, mc -> ability(mc, ab[0], true), null);
            add(6, mc -> {}, ab[1] + "_a");
            add(8, mc -> {}, ab[1] + "_b");
            // сбоку и в три четверти — видно, остались ли перчатки на кистях
            add(2, mc -> com.arc4io.archud.client.camera.ArcCamera.devView(mc, 90F, 5F), null);
            add(4, mc -> {}, ab[1] + "_side");
            add(2, mc -> com.arc4io.archud.client.camera.ArcCamera.devView(mc, 145F, 15F), null);
            add(4, mc -> {}, ab[1] + "_34");
            add(2, mc -> com.arc4io.archud.client.camera.ArcCamera.freelookEnd(mc), null);
            add(2, mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT), null);
            add(5, mc -> ability(mc, ab[0], false), null);
            add(60, mc -> {}, null);
        }
        add(10, mc -> mc.options.setCameraType(CameraType.FIRST_PERSON), null);
        // перчатки Aether вместе с рукой: пустая рука, затем рука, сдвинутая «модом анимаций», с фиксом и без
        add(5, mc -> {
            mc.player.getInventory().selected = 8;
            cmd(mc, "item replace entity @s hotbar.8 with air");
            mc.player.setXRot(10F);
        }, null);
        add(15, mc -> {}, "c40_glove_fp");
        add(3, mc -> handTilt = 12F, null);
        add(10, mc -> {}, "c41_glove_fp_tilt_fixed");
        add(3, mc -> noPartHook = true, null);
        add(10, mc -> {}, "c41b_glove_fp_tilt_fallback");
        add(3, mc -> noPartHook = false, null);
        add(3, mc -> com.arc4io.archud.config.ArcConfig.get().aetherGlovesFix = false, null);
        add(10, mc -> {}, "c42_glove_fp_tilt_aether_only");
        add(3, mc -> {
            com.arc4io.archud.config.ArcConfig.get().aetherGlovesFix = true;
            handTilt = 0F;
            mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
            mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        }, null);
        add(3, mc -> {}, "c43_glove_tp_swing");
        add(10, mc -> mc.options.setCameraType(CameraType.FIRST_PERSON), null);
        // от первого лица: удар, присед, способности Legends — перчатка должна оставаться на кисти
        add(3, mc -> mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND), null);
        add(2, mc -> {}, "c44_glove_fp_swing");
        add(10, mc -> mc.options.keyShift.setDown(true), null);
        add(8, mc -> {}, "c45_glove_fp_sneak");
        add(3, mc -> mc.options.keyShift.setDown(false), null);
        // с мечом в руке (Punchy показывает руку и с предметом), удар мечом, ходьба
        add(5, mc -> mc.player.getInventory().selected = 5, null);
        add(15, mc -> {}, "c49_glove_fp_sword");
        add(2, mc -> mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND), null);
        add(3, mc -> {}, "c50_glove_fp_sword_swing");
        add(2, mc -> mc.options.keyUp.setDown(true), null);
        add(12, mc -> {}, "c51_glove_fp_walk");
        add(2, mc -> {
            mc.options.keyUp.setDown(false);
            mc.player.getInventory().selected = 8;
        }, null);
        // Punchy без костюма Legends: рука игрока, перчатка на ней — покой, удар, меч, удар мечом, ходьба; затем без фикса
        add(3, mc -> cmd(mc, "item replace entity @s armor.chest with air"), null);
        add(10, mc -> {}, "c53_punchy_no_suit");
        add(2, mc -> mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND), null);
        add(3, mc -> {}, "c56_punchy_no_suit_swing");
        add(3, mc -> mc.player.getInventory().selected = 5, null);
        add(12, mc -> {}, "c57_punchy_no_suit_sword");
        add(2, mc -> mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND), null);
        add(3, mc -> {}, "c58_punchy_no_suit_sword_swing");
        add(2, mc -> mc.options.keyUp.setDown(true), null);
        add(10, mc -> {}, "c59_punchy_no_suit_walk");
        add(2, mc -> {
            mc.options.keyUp.setDown(false);
            mc.player.getInventory().selected = 8;
            com.arc4io.archud.config.ArcConfig.get().aetherGlovesFix = false;
        }, null);
        add(10, mc -> {}, "c52_punchy_fix_off");
        add(2, mc -> mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND), null);
        add(3, mc -> {}, "c52b_punchy_fix_off_swing");
        // без перчаток вовсе, в костюме — чья тёмная рука
        add(3, mc -> {
            com.arc4io.archud.config.ArcConfig.get().aetherGlovesFix = true;
            cmd(mc, "curios clear @s aether_gloves");
            cmd(mc, "item replace entity @s armor.chest with legendsmod:steve_chest");
        }, null);
        add(10, mc -> {}, "c54_punchy_suit_no_gloves");
        add(3, mc -> cmd(mc, "curios set aether_gloves 0 @s aether:iron_gloves"), null);
        for (String[] ab : new String[][]{{"legendsmod.key.ability_2", "c46_glove_fp_ab2"}, {"legendsmod.key.ability_1", "c47_glove_fp_ab1"},
                {"legendsmod.key.ability_3", "c48_glove_fp_ab3"}}) {
            add(3, mc -> ability(mc, ab[0], true), null);
            add(6, mc -> {}, ab[1]);
            add(3, mc -> ability(mc, ab[0], false), null);
            add(40, mc -> {}, null);
        }
        add(20, mc -> cmd(mc, "give @s minecraft:beef 3"), null);
        // просмотр рецептов: список предметов у инвентаря и экран рецептов
        add(5, mc -> {
            mc.options.guiScale().set(2);
            mc.resizeDisplay();
        }, null);
        add(10, mc -> mc.setScreen(new InventoryScreen(mc.player)), null);
        add(30, mc -> {}, "c12_inv_itemlist");
        add(5, mc -> com.arc4io.archud.client.recipes.ArcRecipeScreen.open(mc.screen, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_PICKAXE), false), null);
        add(20, mc -> ArcHud.LOGGER.info("[Compat] recipe screen: {}", mc.screen == null ? "none" : mc.screen.getClass().getSimpleName()), "c13_recipes");
        add(5, mc -> com.arc4io.archud.client.recipes.ArcRecipeScreen.open(mc.screen, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT), true), null);
        add(20, mc -> {}, "c14_uses");
        add(5, mc -> com.arc4io.archud.client.recipes.ArcRecipeScreen.open(mc.screen, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.NETHER_WART), true), null);
        add(20, mc -> {}, "c15_brewing");
        add(5, mc -> {
            ArcHud.LOGGER.info("[Compat] fps with recipe screen: {}", mc.getFps());
        }, null);
        add(5, mc -> { if (mc.screen != null) mc.screen.onClose(); }, null);
        add(10, mc -> closeScreen(mc), null);
        add(5, mc -> {
            mc.options.guiScale().set(3);
            mc.resizeDisplay();
        }, null);
        // рюкзак, наковальня, чат
        add(10, mc -> {
            cmd(mc, "give @s archud:diamond_backpack");
            cmd(mc, "give @s minecraft:cobblestone 40");
            cmd(mc, "give @s minecraft:iron_ingot 20");
            cmd(mc, "give @s minecraft:diamond_pickaxe");
            cmd(mc, "give @s minecraft:enchanted_book{StoredEnchantments:[{id:\"minecraft:efficiency\",lvl:4s},{id:\"minecraft:unbreaking\",lvl:3s}]}");
            cmd(mc, "experience add @s 30 levels");
            cmd(mc, "setblock ~ ~ ~1 minecraft:anvil");
        }, null);
        add(20, mc -> com.arc4io.archud.client.ArcClient.platform().sendToServer(com.arc4io.archud.network.ArcPacket.OPEN_BACKPACK), null);
        add(30, mc -> ArcHud.LOGGER.info("[Compat] backpack screen: {}", mc.screen == null ? "none" : mc.screen.getClass().getSimpleName()), "c08_backpack");
        add(10, mc -> closeScreen(mc), null);
        add(10, mc -> {
            mc.player.setXRot(60F);
            mc.player.setYRot(0F);
        }, null);
        add(5, mc -> {
            ArcHud.LOGGER.info("[Compat] looking at {}", mc.hitResult == null ? "nothing" : mc.hitResult.getType());
            KeyMapping.click(com.mojang.blaze3d.platform.InputConstants.getKey(mc.options.keyUse.saveString()));
        }, null);
        add(20, mc -> {
            ArcHud.LOGGER.info("[Compat] anvil screen: {}", mc.screen == null ? "none" : mc.screen.getClass().getSimpleName());
            if (mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> s) {
                var menu = s.getMenu();
                for (int i = 3; i < menu.slots.size(); i++) {
                    String id = BuiltInRegistries.ITEM.getKey(menu.slots.get(i).getItem().getItem()).getPath();
                    if (id.equals("diamond_pickaxe")) mc.gameMode.handleInventoryMouseClick(menu.containerId, i, 0, ClickType.QUICK_MOVE, mc.player);
                }
                for (int i = 3; i < menu.slots.size(); i++) {
                    String id = BuiltInRegistries.ITEM.getKey(menu.slots.get(i).getItem().getItem()).getPath();
                    if (id.equals("enchanted_book")) mc.gameMode.handleInventoryMouseClick(menu.containerId, i, 0, ClickType.QUICK_MOVE, mc.player);
                }
            }
        }, null);
        add(30, mc -> {}, "c09_anvil");
        add(10, mc -> closeScreen(mc), null);
        // кнопки «Сортировать» и «Рюкзак» в инвентаре выживания
        add(5, mc -> {
            cmd(mc, "give @s minecraft:dirt 5");
            cmd(mc, "give @s minecraft:apple 3");
            cmd(mc, "give @s minecraft:dirt 7");
        }, null);
        add(10, mc -> mc.setScreen(new InventoryScreen(mc.player)), null);
        add(10, mc -> {
            ArcHud.LOGGER.info("[Compat] inventory before sort: {}", invOrder(mc));
            if (mc.screen instanceof com.arc4io.archud.client.screen.ArcSurvivalScreen s) {
                int[] c = s.debugButton(0);
                boolean r = s.mouseClicked(c[0], c[1], 0);
                s.mouseReleased(c[0], c[1], 0);
                ArcHud.LOGGER.info("[Compat] sort button click handled={}", r);
            } else ArcHud.LOGGER.info("[Compat] inventory screen is {}", mc.screen == null ? "none" : mc.screen.getClass().getSimpleName());
        }, null);
        add(15, mc -> ArcHud.LOGGER.info("[Compat] inventory after sort: {}", invOrder(mc)), "c09b_sorted");
        add(5, mc -> {
            if (mc.screen instanceof com.arc4io.archud.client.screen.ArcSurvivalScreen s) {
                int[] c = s.debugButton(1);
                boolean r = s.mouseClicked(c[0], c[1], 0);
                s.mouseReleased(c[0], c[1], 0);
                ArcHud.LOGGER.info("[Compat] backpack button click handled={}", r);
            }
        }, null);
        add(20, mc -> ArcHud.LOGGER.info("[Compat] after backpack button: {}", mc.screen == null ? "none" : mc.screen.getClass().getSimpleName()), "c09c_backpack_btn");
        add(5, mc -> closeScreen(mc), null);
        add(10, mc -> closeScreen(mc), null);
        // плагин Farmer's Delight для JEI (без JEI): его категории в нашем окне рецептов и «кликабельная стрелка» котелка
        if (ArcHud.platform().isModLoaded("farmersdelight")) {
            add(5, mc -> {
                ArcHud.LOGGER.info("[Compat] JEI bridge: api={} real={} categories={}", com.arc4io.archud.client.jei.JeiGate.apiPresent(),
                        com.arc4io.archud.client.jei.JeiGate.realJei(), com.arc4io.archud.client.recipes.RecipeIndex.TITLES.keySet());
                cmd(mc, "setblock ~ ~ ~1 farmersdelight:cooking_pot");
            }, null);
            add(10, mc -> KeyMapping.click(com.mojang.blaze3d.platform.InputConstants.getKey(mc.options.keyUse.saveString())), null);
            add(20, mc -> ArcHud.LOGGER.info("[Compat] cooking pot screen: {}", mc.screen == null ? "none" : mc.screen.getClass().getName()), "c16_fd_pot");
            add(5, mc -> {
                if (mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> s) {
                    var acc = (com.arc4io.archud.mixin.ContainerScreenAccessor) s;
                    boolean ok = com.arc4io.archud.client.jei.JeiGate.clickArea(s, acc.archud$left() + 100, acc.archud$top() + 33);
                    ArcHud.LOGGER.info("[Compat] cooking pot click area: {} -> {}", ok, mc.screen == null ? "none" : mc.screen.getClass().getSimpleName());
                }
            }, null);
            add(20, mc -> {}, "c17_fd_clickarea");
            add(5, mc -> { if (mc.screen != null) mc.screen.onClose(); }, null);
            add(10, mc -> closeScreen(mc), null);
            add(5, mc -> com.arc4io.archud.client.recipes.ArcRecipeScreen.open(mc.screen,
                    new net.minecraft.world.item.ItemStack(BuiltInRegistries.ITEM.get(new net.minecraft.resources.ResourceLocation("farmersdelight", "beef_stew"))), false), null);
            add(20, mc -> {}, "c18_fd_recipe");
            add(5, mc -> com.arc4io.archud.client.recipes.ArcRecipeScreen.open(mc.screen,
                    new net.minecraft.world.item.ItemStack(BuiltInRegistries.ITEM.get(new net.minecraft.resources.ResourceLocation("farmersdelight", "cutting_board"))), true), null);
            add(20, mc -> {}, "c19_fd_board");
            add(5, mc -> com.arc4io.archud.client.recipes.ArcRecipeScreen.open(mc.screen,
                    new net.minecraft.world.item.ItemStack(BuiltInRegistries.ITEM.get(new net.minecraft.resources.ResourceLocation("farmersdelight", "tomato"))), false), null);
            add(20, mc -> {}, "c20_fd_info");
            add(5, mc -> {
                if (mc.screen instanceof com.arc4io.archud.client.recipes.ArcRecipeScreen rs) rs.selectCategory(new net.minecraft.resources.ResourceLocation("jei", "information"));
            }, null);
            add(20, mc -> {}, "c21_fd_info_page");
            add(5, mc -> { if (mc.screen != null) mc.screen.onClose(); }, null);
            add(10, mc -> closeScreen(mc), null);
            // перенос рецепта в котелок (обработчик переноса Farmer's Delight для JEI)
            add(5, mc -> {
                cmd(mc, "give @s minecraft:beef 4");
                cmd(mc, "give @s minecraft:carrot 4");
                cmd(mc, "give @s minecraft:potato 4");
                cmd(mc, "give @s minecraft:bowl 4");
            }, null);
            add(10, mc -> KeyMapping.click(com.mojang.blaze3d.platform.InputConstants.getKey(mc.options.keyUse.saveString())), null);
            add(15, mc -> com.arc4io.archud.client.recipes.ArcRecipeScreen.open(mc.screen,
                    new net.minecraft.world.item.ItemStack(BuiltInRegistries.ITEM.get(new net.minecraft.resources.ResourceLocation("farmersdelight", "beef_stew"))), false), null);
            add(20, mc -> {}, "c22_fd_transfer_button");
            add(5, mc -> transferFirst(mc, "c22"), null);
            add(20, mc -> ArcHud.LOGGER.info("[Compat] after pot transfer: {}", mc.screen == null ? "none" : mc.screen.getClass().getSimpleName()), "c23_fd_transferred");
            add(5, mc -> closeScreen(mc), null);
            add(10, mc -> closeScreen(mc), null);
        }
        // клавиши R / U над предметом в инвентаре, перенос рецепта в верстак, встроенные категории JEI
        add(5, mc -> {
            cmd(mc, "give @s minecraft:stick 8");
            cmd(mc, "give @s minecraft:oak_planks 16");
            cmd(mc, "give @s minecraft:wheat_seeds 8");
        }, null);
        add(10, mc -> mc.setScreen(new InventoryScreen(mc.player)), null);
        add(10, mc -> hover(mc, net.minecraft.world.item.Items.COBBLESTONE), null);
        add(5, mc -> press(mc, org.lwjgl.glfw.GLFW.GLFW_KEY_U), null);
        add(20, mc -> ArcHud.LOGGER.info("[Compat] key U -> {}", mc.screen == null ? "none" : mc.screen.getClass().getSimpleName()), "c24_key_u");
        add(5, mc -> { if (mc.screen != null) mc.screen.onClose(); }, null);
        add(10, mc -> hover(mc, net.minecraft.world.item.Items.STICK), null);
        add(5, mc -> press(mc, org.lwjgl.glfw.GLFW.GLFW_KEY_R), null);
        add(20, mc -> ArcHud.LOGGER.info("[Compat] key R -> {}", mc.screen == null ? "none" : mc.screen.getClass().getSimpleName()), "c25_key_r");
        add(5, mc -> { if (mc.screen != null) mc.screen.onClose(); }, null);
        add(10, mc -> closeScreen(mc), null);
        add(5, mc -> cmd(mc, "setblock ~ ~ ~1 minecraft:crafting_table"), null);
        add(10, mc -> KeyMapping.click(com.mojang.blaze3d.platform.InputConstants.getKey(mc.options.keyUse.saveString())), null);
        add(15, mc -> com.arc4io.archud.client.recipes.ArcRecipeScreen.open(mc.screen, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE_PICKAXE), false), null);
        add(20, mc -> {}, "c26_transfer_button");
        add(5, mc -> transferFirst(mc, "c26"), null);
        add(20, mc -> {}, "c27_transferred");
        add(5, mc -> closeScreen(mc), null);
        add(10, mc -> closeScreen(mc), null);
        add(5, mc -> com.arc4io.archud.client.recipes.ArcRecipeScreen.open(mc.screen, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE), true), null);
        add(5, mc -> {
            if (mc.screen instanceof com.arc4io.archud.client.recipes.ArcRecipeScreen rs) rs.selectCategory(new net.minecraft.resources.ResourceLocation("anvil"));
        }, null);
        add(20, mc -> {}, "c28_anvil");
        add(5, mc -> com.arc4io.archud.client.recipes.ArcRecipeScreen.open(mc.screen, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WHEAT_SEEDS), true), null);
        add(5, mc -> {
            if (mc.screen instanceof com.arc4io.archud.client.recipes.ArcRecipeScreen rs) rs.selectCategory(new net.minecraft.resources.ResourceLocation("compostable"));
        }, null);
        add(20, mc -> {}, "c29_compost");
        add(5, mc -> com.arc4io.archud.client.recipes.ArcRecipeScreen.open(mc.screen, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TIPPED_ARROW), false), null);
        add(20, mc -> {}, "c30_tipped");
        add(5, mc -> { if (mc.screen != null) mc.screen.onClose(); }, null);
        add(10, mc -> closeScreen(mc), null);
        // тени игроков (встроенный шейдер), апскейл с мобами/водой/лавой, свой вывод окон
        add(5, mc -> {
            cmd(mc, "time set 4000");
            cmd(mc, "weather clear");
            var c = com.arc4io.archud.config.ArcConfig.get();
            c.shaderEnabled = true;
            cmd(mc, "tp @s ~ ~ ~-6");
            mc.player.setXRot(55F);
            mc.player.setYRot(0F);
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        }, null);
        add(5, mc -> {
            cmd(mc, "summon minecraft:zombie ~2 ~ ~3 {NoAI:1b,PersistenceRequired:1b}");
            cmd(mc, "summon minecraft:pig ~-2 ~ ~4 {NoAI:1b}");
        }, null);
        add(30, mc -> {}, "c31_no_blob_shadows");
        add(5, mc -> {
            cmd(mc, "kill @e[type=!player]");
            cmd(mc, "time set 4000");
            cmd(mc, "tp @s ~ ~ ~6");
        }, null);
        add(5, mc -> {
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            cmd(mc, "fill ~-1 ~-1 ~3 ~1 ~-1 ~4 minecraft:water");
            cmd(mc, "fill ~2 ~-1 ~3 ~3 ~-1 ~4 minecraft:lava");
            cmd(mc, "summon minecraft:pig ~-2 ~ ~5 {NoAI:1b}");
            cmd(mc, "summon minecraft:cow ~1 ~ ~6 {NoAI:1b}");
            cmd(mc, "summon minecraft:zombie ~3 ~ ~6 {NoAI:1b,PersistenceRequired:1b}");
            mc.player.setXRot(25F);
            mc.player.setYRot(0F);
        }, null);
        add(10, mc -> cmd(mc, "particle minecraft:flame ~ ~1.5 ~4 0.6 0.4 0.6 0 300 force"), null);
        add(3, mc -> cmd(mc, "particle minecraft:happy_villager ~ ~1.5 ~4 0.6 0.4 0.6 0 200 force"), "c32_native");
        add(5, mc -> {
            var c = com.arc4io.archud.config.ArcConfig.get();
            c.renderScale = 50;
            c.upscaler = 1;
        }, null);
        add(10, mc -> cmd(mc, "particle minecraft:flame ~ ~1.5 ~4 0.6 0.4 0.6 0 300 force"), null);
        add(3, mc -> cmd(mc, "particle minecraft:happy_villager ~ ~1.5 ~4 0.6 0.4 0.6 0 200 force"), "c33_fsr50");
        add(5, mc -> com.arc4io.archud.config.ArcConfig.get().upscaler = 2, null);
        add(40, mc -> {}, "c34_tsr50");
        add(5, mc -> {
            var c = com.arc4io.archud.config.ArcConfig.get();
            c.renderScale = 100;
            c.upscaler = 1;
            cmd(mc, "kill @e[type=!player]");
        }, null);
        add(10, mc -> com.arc4io.archud.client.recipes.ArcRecipeScreen.open(mc.screen, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STICK), true), null);
        add(60, mc -> ArcHud.LOGGER.info("[Compat] fps recipe screen, cached output: {}", mc.getFps()), "c35_cached_recipes");
        add(5, mc -> com.arc4io.archud.config.ArcConfig.get().guiCache = false, null);
        add(60, mc -> ArcHud.LOGGER.info("[Compat] fps recipe screen, direct output: {}", mc.getFps()), null);
        add(5, mc -> {
            com.arc4io.archud.config.ArcConfig.get().guiCache = true;
            if (mc.screen != null) mc.screen.onClose();
        }, null);
        add(10, mc -> closeScreen(mc), null);
        add(10, mc -> {
            cmd(mc, "tellraw @a [{\"text\":\"<Notch> \"},{\"text\":\"привет, как дела?\"}]");
            cmd(mc, "tellraw @a {\"text\":\"[Сервер] Рестарт через 5 минут\",\"color\":\"gold\"}");
            mc.player.connection.sendChat("Тест чата с головой игрока");
        }, null);
        add(20, mc -> mc.setScreen(new net.minecraft.client.gui.screens.ChatScreen("")), "c10_chat");
        add(10, mc -> mc.setScreen(null), null);
        add(10, mc -> {}, "c11_chat_hud");

        // ---------------- метки, консоль, моды, карта, шейдерпаки
        add(5, mc -> {
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            cmd(mc, "time set 6000");
            cmd(mc, "gamerule doImmediateRespawn true");
            cmd(mc, "kill @s");
        }, null);
        add(40, mc -> {
            if (mc.player != null && mc.player.isDeadOrDying()) mc.player.respawn();
        }, null);
        add(40, mc -> {
            long deaths = com.arc4io.archud.client.map.WaypointManager.user().stream()
                    .filter(w -> w.source == com.arc4io.archud.client.map.Waypoint.Source.DEATH).count();
            var nav = com.arc4io.archud.client.map.Navigator.target();
            ArcHud.LOGGER.info("[Compat] death waypoints={} navTarget={}", deaths, nav == null ? "none" : nav.source + ":" + nav.name);
        }, "c60_after_death");
        // обмен меткой: уведомление «Принять / Отклонить»
        add(5, mc -> {
            var w = new com.arc4io.archud.client.map.Waypoint("Дом Notch", 120, 64, -40, 0xFF4FC3F7, "minecraft:overworld",
                    com.arc4io.archud.client.map.Waypoint.Source.SHARED);
            w.sharedBy = "Notch";
            com.arc4io.archud.client.map.ShareInbox.offer(w);
        }, null);
        add(15, mc -> {}, "c61_share_offer");
        add(5, mc -> mc.setScreen(new net.minecraft.client.gui.screens.ChatScreen("")), null);
        add(10, mc -> {}, "c62_share_offer_chat");
        add(5, mc -> {
            var o = com.arc4io.archud.client.map.ShareInbox.pending();
            if (!o.isEmpty()) com.arc4io.archud.client.map.ShareInbox.accept(o.get(0).id());
            boolean saved = com.arc4io.archud.client.map.WaypointManager.user().stream().anyMatch(w -> w.name.equals("Дом Notch"));
            ArcHud.LOGGER.info("[Compat] shared waypoint accepted -> saved={} pending={}", saved, o.size());
            mc.setScreen(null);
        }, null);
        // кнопка «Сохранить мир»
        add(5, mc -> com.arc4io.archud.server.WorldSaver.request(true), null);
        add(30, mc -> ArcHud.LOGGER.info("[Compat] save button label: {}", com.arc4io.archud.client.WorldSaveClient.buttonLabel(mc).getString()), null);
        // глобальная метка администратора
        add(5, mc -> com.arc4io.archud.client.ArcClientNet.globalAdd(new com.arc4io.archud.client.map.Waypoint("Спавн сервера",
                (int) mc.player.getX() + 6, (int) mc.player.getY(), (int) mc.player.getZ() + 6, 0xFFFFD54F, "minecraft:overworld",
                com.arc4io.archud.client.map.Waypoint.Source.USER)), null);
        add(20, mc -> {
            var all = com.arc4io.archud.client.map.WaypointManager.all();
            long g = all.stream().filter(w -> w.source == com.arc4io.archud.client.map.Waypoint.Source.GLOBAL).count();
            ArcHud.LOGGER.info("[Compat] global waypoints={} canEdit={}", g, com.arc4io.archud.client.map.WaypointManager.globalCanEdit());
            mc.setScreen(new com.arc4io.archud.client.map.WaypointListScreen(null));
        }, null);
        add(15, mc -> {}, "c63_waypoints_global");
        // скрыть метку у себя
        add(5, mc -> {
            for (var w : com.arc4io.archud.client.map.WaypointManager.all()) {
                if (w.source == com.arc4io.archud.client.map.Waypoint.Source.GLOBAL) com.arc4io.archud.client.map.WaypointManager.setVisible(w, false);
            }
            long shown = com.arc4io.archud.client.map.WaypointManager.inDimension().stream()
                    .filter(w -> w.source == com.arc4io.archud.client.map.Waypoint.Source.GLOBAL).count();
            ArcHud.LOGGER.info("[Compat] global waypoints shown after hide={}", shown);
            for (var w : com.arc4io.archud.client.map.WaypointManager.all()) {
                if (w.source == com.arc4io.archud.client.map.Waypoint.Source.GLOBAL) com.arc4io.archud.client.map.WaypointManager.setVisible(w, true);
            }
            mc.setScreen(null);
        }, null);
        // кровать: метка есть, кровать сломана — метки нет
        add(5, mc -> {
            int bx = (int) Math.floor(mc.player.getX()) + 2, by = (int) Math.floor(mc.player.getY()), bz = (int) Math.floor(mc.player.getZ());
            cmd(mc, "setblock " + bx + " " + by + " " + bz + " minecraft:red_bed[facing=east,part=foot]");
            cmd(mc, "setblock " + (bx + 1) + " " + by + " " + bz + " minecraft:red_bed[facing=east,part=head]");
            cmd(mc, "spawnpoint @s " + bx + " " + by + " " + bz);
            bed = new int[]{bx, by, bz};
        }, null);
        add(60, mc -> ArcHud.LOGGER.info("[Compat] bed waypoint before break={} respawn={} block={}", com.arc4io.archud.client.map.WaypointManager.all().stream()
                .anyMatch(w -> w.source == com.arc4io.archud.client.map.Waypoint.Source.SPAWN), com.arc4io.archud.client.map.WaypointManager.respawnInfo(),
                mc.level.getBlockState(new net.minecraft.core.BlockPos(bed[0], bed[1], bed[2]))), null);
        add(5, mc -> {
            cmd(mc, "setblock " + bed[0] + " " + bed[1] + " " + bed[2] + " minecraft:air");
            cmd(mc, "setblock " + (bed[0] + 1) + " " + bed[1] + " " + bed[2] + " minecraft:air");
        }, null);
        add(60, mc -> ArcHud.LOGGER.info("[Compat] bed waypoint after break={} respawn={} block={}", com.arc4io.archud.client.map.WaypointManager.all().stream()
                .anyMatch(w -> w.source == com.arc4io.archud.client.map.Waypoint.Source.SPAWN), com.arc4io.archud.client.map.WaypointManager.respawnInfo(),
                mc.level.getBlockState(new net.minecraft.core.BlockPos(bed[0], bed[1], bed[2]))), null);
        // консоль «~»
        add(5, mc -> mc.setScreen(new com.arc4io.archud.client.console.ConsoleScreen()), null);
        add(5, mc -> {
            if (mc.screen instanceof com.arc4io.archud.client.console.ConsoleScreen cs) {
                for (char c : "time set 1000".toCharArray()) cs.charTyped(c, 0);
                cs.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0);
                for (char c : "help".toCharArray()) cs.charTyped(c, 0);
                cs.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0);
            }
        }, null);
        add(20, mc -> ArcHud.LOGGER.info("[Compat] console: daytime after command={}", mc.level.getDayTime() % 24000), "c64_console");
        add(5, mc -> mc.setScreen(null), null);
        // меню модов: кнопка «Выключить»
        add(5, mc -> mc.setScreen(new com.arc4io.archud.client.mods.ArcModListScreen(null)), null);
        add(20, mc -> {}, "c65_mods");
        add(5, mc -> mc.setScreen(null), null);
        // карта от другого мира убирается
        add(5, mc -> {
            String key = com.arc4io.archud.client.map.WaypointManager.worldKey();
            var dir = ArcHud.platform().configDir().resolve("archud").resolve("map").resolve(key);
            boolean before = java.nio.file.Files.isDirectory(dir);
            com.arc4io.archud.client.map.MapCache.checkWorldId(key, "compat-other-world");
            com.arc4io.archud.client.map.MapCache.checkWorldId(key, "compat-new-world");
            ArcHud.LOGGER.info("[Compat] map wipe: before={} oldMapStillThere={}", before, java.nio.file.Files.isDirectory(dir.resolve("minecraft~overworld")));
        }, null);
        add(10, mc -> {}, "c66_map_wiped_toast");
        // шейдерпаки своим загрузчиком: тестовый пак + скачанные (BSL, Complementary)
        add(5, mc -> {
            writeTestShaderPack(mc);
            cmd(mc, "time set 1000");
            mc.player.setXRot(10F);
        }, null);
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            add(5, mc -> {
                var packs = com.arc4io.archud.client.fx.pack.PackPipeline.packs();
                ArcHud.LOGGER.info("[Compat] shader packs available: {}", packs);
                com.arc4io.archud.config.ArcConfig.get().shaderPack = idx < packs.size() ? packs.get(idx) : "";
                com.arc4io.archud.client.fx.pack.PackPipeline.reload();
            }, null);
            add(60, mc -> ArcHud.LOGGER.info("[Compat] shader pack {} status: {}", com.arc4io.archud.config.ArcConfig.get().shaderPack,
                    com.arc4io.archud.client.fx.pack.PackPipeline.describe()), "c7" + idx + "_shaderpack");
        }
        add(5, mc -> {
            com.arc4io.archud.config.ArcConfig.get().shaderPack = "";
            com.arc4io.archud.client.fx.pack.PackPipeline.reload();
        }, null);

        add(20, mc -> {
            ArcHud.LOGGER.info("[Compat] done");
            mc.stop();
        }, null);
    }

    /** Закрыть экран как игрок (Esc): контейнер закрывается и на сервере. */
    private static void closeScreen(Minecraft mc) {
        if (mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> s) s.onClose();
        else mc.setScreen(null);
    }

    public static void init() {
        if (!enabled()) return;
        ArcHud.LOGGER.info("[Compat] enabled");
        build();
    }

    public static void tick(Minecraft mc) {
        if (!enabled() || STEPS.isEmpty()) return;
        if (index < 0) {
            next(mc);
            return;
        }
        if (index >= STEPS.size()) return;
        if (++guard > 20 * 900) {
            ArcHud.LOGGER.error("[Compat] timeout");
            mc.stop();
            return;
        }
        Step step = STEPS.get(index);
        if (step.delay() < 0) {
            if (mc.level != null && mc.player != null && mc.screen == null) next(mc);
            else if (mc.level != null && mc.player != null && mc.screen != null && mc.screen.isPauseScreen()) closeScreen(mc);
            return;
        }
        if (held != null) held.setDown(true);
        if (--timer > 0) return;
        if (step.shot() != null) {
            mc.getToasts().clear();
            Screenshot.grab(mc.gameDirectory, step.shot() + ".png", mc.getMainRenderTarget(), msg -> {});
            ArcHud.LOGGER.info("[Compat] screenshot {}", step.shot());
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
            ArcHud.LOGGER.error("[Compat] step {} failed", index, t);
        }
        timer = Math.max(1, s.delay());
    }
}
