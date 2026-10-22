package com.arc4io.archud.forge.compat;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.client.hud.ArcHudApi;
import com.arc4io.archud.client.hud.Icons;
import com.arc4io.archud.config.ArcConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.capabilities.Capability;

import java.lang.reflect.Field;
import java.util.Optional;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Интеграция Legends Mod (legendsmod): выносливость, мана и панель способностей
 * рисуются в стиле Arc HUD, а родные оверлеи Legends скрываются.
 * Всё через рефлексию — Arc HUD не требует Legends для сборки и запуска.
 */
public final class LegendsCompat {
    private static final String DATA = "com.tihyo.legends.data.attributes.client.ClientAttributesData";
    private static final String UTIL = "com.tihyo.legends.utils.client.LegendsClientUtil";
    private static final String ABILITIES = "com.tihyo.legends.client.gui.AbilitiesOverlay";

    /** HP и броню Legends Arc HUD и так показывает — их дубли скрываются. */
    public static final List<String> DUPLICATE_OVERLAYS = List.of("legendsmod:health", "legendsmod:legends_armor");

    private static Method shouldRender, stamina, maxStamina, mana, maxMana, getSuit, walkSpeed;
    private static Capability<?> livingCap;
    private static Method rows, rowLabel, rowCooldown, rowProgress, rowActive, rowCooling;

    private LegendsCompat() {}

    public static void init() {
        try {
            Class<?> data = Class.forName(DATA);
            stamina = data.getMethod("getPlayerStamina");
            maxStamina = data.getMethod("getPlayerMaxStamina");
            mana = data.getMethod("getPlayerMana");
            maxMana = data.getMethod("getPlayerMaxMana");
            shouldRender = Class.forName(UTIL).getMethod("shouldRenderAttributeHUD", Player.class);

            // мана — слева над HP, выносливость — справа над едой (как у самого Legends)
            ArcHudApi.registerMainBar(ArcHud.id("legends_mana"), Component.translatable("archud.legends.mana"), 0xFF2F9BE0,
                    Icons.STAR, ArcHudApi.Side.LEFT, () -> values(mana, maxMana));
            ArcHudApi.registerMainBar(ArcHud.id("legends_stamina"), Component.translatable("archud.legends.stamina"), 0xFF35C83A,
                    Icons.SPEED, ArcHudApi.Side.RIGHT, () -> values(stamina, maxStamina));
            ArcHudApi.markReplaced("legendsmod:stamina");
            ArcHudApi.markReplaced("legendsmod:mana");
            ArcHud.LOGGER.info("Arc HUD: Legends — выносливость и мана подключены");
        } catch (Throwable t) {
            ArcHud.LOGGER.warn("Arc HUD: не удалось подключить полосы Legends: {}", t.toString());
        }
        try {
            // скорость костюма (0..10) — тонкая полоса
            Class<?> suit = Class.forName("com.tihyo.legends.armors.LegendsSuit");
            getSuit = suit.getMethod("getSuit", LivingEntity.class);
            Field capField = Class.forName("com.tihyo.legends.data.living.LegendsLivingEntityProvider").getField("CAPABILITY");
            livingCap = (Capability<?>) capField.get(null);
            walkSpeed = Class.forName("com.tihyo.legends.data.living.ILegendsLiving").getMethod("getWalkSpeed");
            ArcHudApi.registerBar(ArcHud.id("legends_speed"), Component.translatable("archud.legends.speed"), 0xFFFFC107, LegendsCompat::speed);
            ArcHudApi.markReplaced("legendsmod:speed");
        } catch (Throwable t) {
            ArcHud.LOGGER.warn("Arc HUD: скорость костюма Legends недоступна: {}", t.toString());
        }
        try {
            Class<?> overlay = Class.forName(ABILITIES);
            rows = overlay.getDeclaredMethod("rows", Player.class, boolean.class);
            rows.setAccessible(true);
            Class<?> row = Class.forName(ABILITIES + "$Row");
            rowLabel = accessor(row, "label");
            rowCooldown = accessor(row, "cooldownText");
            rowProgress = accessor(row, "progress");
            rowActive = accessor(row, "active");
            rowCooling = accessor(row, "cooling");

            ArcHudApi.registerPanel(ArcHud.id("legends_abilities"), Component.translatable("archud.legends.abilities"), 0xFFFFB74D,
                    LegendsCompat::abilityRows);
            ArcHudApi.markReplaced("legendsmod:abilities");
            ArcHud.LOGGER.info("Arc HUD: Legends — панель способностей подключена");
        } catch (Throwable t) {
            ArcHud.LOGGER.warn("Arc HUD: не удалось подключить способности Legends: {}", t.toString());
        }
    }

    private static Method accessor(Class<?> cls, String name) throws NoSuchMethodException {
        Method m = cls.getDeclaredMethod(name);
        m.setAccessible(true);
        return m;
    }

    private static boolean visible(Player p) {
        try {
            return p != null && (boolean) shouldRender.invoke(null, p);
        } catch (Throwable t) {
            return false;
        }
    }

    private static float[] values(Method value, Method max) {
        Player p = Minecraft.getInstance().player;
        if (!visible(p)) return null;
        try {
            float m = ((Number) max.invoke(null)).floatValue();
            if (m <= 0) return null;
            return new float[]{((Number) value.invoke(null)).floatValue(), m};
        } catch (Throwable t) {
            return null;
        }
    }

    private static float[] speed() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (!visible(p) || livingCap == null) return null;
        try {
            if (getSuit.invoke(null, p) == null) return null;
            Optional<?> living = p.getCapability(livingCap).resolve();
            if (living.isEmpty()) return null;
            return new float[]{((Number) walkSpeed.invoke(living.get())).floatValue(), 10F};
        } catch (Throwable t) {
            return null;
        }
    }

    private static List<ArcHudApi.PanelRow> abilityRows() {
        Player p = Minecraft.getInstance().player;
        if (p == null || !ArcConfig.get().enabled) return null;
        try {
            List<?> list = (List<?>) rows.invoke(null, p, true);
            if (list == null || list.isEmpty()) return null;
            List<ArcHudApi.PanelRow> out = new ArrayList<>();
            for (Object r : list) {
                String label = String.valueOf(rowLabel.invoke(r));
                String cd = String.valueOf(rowCooldown.invoke(r));
                float progress = ((Number) rowProgress.invoke(r)).floatValue();
                boolean active = (boolean) rowActive.invoke(r);
                boolean cooling = (boolean) rowCooling.invoke(r);
                int color = active ? 0xFF6BFF7A : cooling ? 0xFFFF8A65 : 0xFF4FC3F7;
                out.add(new ArcHudApi.PanelRow(label, cd, cooling || active ? progress : -1F, color, active));
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }
}
