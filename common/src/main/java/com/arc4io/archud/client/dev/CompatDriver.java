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
                m.setScreen(null);
            }, null);
            add(10, m -> m.setScreen(new InventoryScreen(m.player)), null);
            add(30, m -> ArcHud.LOGGER.info("[Compat] inventory screen at scale {}: {}", s, (m.screen == null ? "none" : m.screen.getClass().getName()) + " menu=" + m.player.containerMenu.getClass().getSimpleName()),
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
            mc.setScreen(null);
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
            }
        }, "c02_inv_equipped");
        // надеть аксессуары как игрок: выбрать в хотбаре и нажать ПКМ (у Aether/Curios это надевает в слот)
        add(10, mc -> mc.setScreen(null), null);
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
        add(10, mc -> mc.setScreen(null), null);
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
        add(20, mc -> {
            ArcHud.LOGGER.info("[Compat] done");
            mc.stop();
        }, null);
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
            else if (mc.level != null && mc.player != null && mc.screen != null && mc.screen.isPauseScreen()) mc.setScreen(null);
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
