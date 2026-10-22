package com.arc4io.archud.compat;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.client.hud.ArcHudApi;
import com.arc4io.archud.config.ArcConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Regeneration (Doctor Who): панель Arc HUD — сколько регенераций осталось, текущее состояние
 * (жив / отсрочка / критическая фаза / регенерация / после регенерации) с прогрессом, светящиеся руки.
 */
final class RegenCompat {
    private static Method get, regens, state, stateManager, progress, glowing;

    private RegenCompat() {}

    static void init() throws Exception {
        Class<?> data = Integrations.cls("mc.craig.software.regen.common.regen.RegenerationData");
        get = data.getMethod("get", LivingEntity.class);
        regens = data.getMethod("regens");
        state = data.getMethod("regenState");
        glowing = data.getMethod("glowing");
        stateManager = data.getMethod("stateManager");
        progress = stateManager.getReturnType().getMethod("stateProgress");
        ArcHudApi.registerPanel(ArcHud.id("regen"), Component.translatable("archud.regen.title"), 0xFFFFB74D, RegenCompat::rows);
        ArcHud.LOGGER.info("Arc HUD: интеграция с Regeneration включена");
    }

    private static List<ArcHudApi.PanelRow> rows() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !ArcConfig.get().regenerationIntegration) return null;
        try {
            Optional<?> opt = (Optional<?>) get.invoke(null, mc.player);
            if (opt.isEmpty()) return null;
            Object d = opt.get();
            int left = ((Number) regens.invoke(d)).intValue();
            String st = String.valueOf(state.invoke(d));
            boolean glow = (boolean) glowing.invoke(d);
            if (left <= 0 && "ALIVE".equals(st)) return null;
            List<ArcHudApi.PanelRow> rows = new ArrayList<>();
            rows.add(new ArcHudApi.PanelRow(Component.translatable("archud.regen.left").getString(), Integer.toString(left), -1F,
                    left > 0 ? 0xFFFFD54F : 0xFFFF5252, false));
            float prog = -1F;
            if (!"ALIVE".equals(st)) {
                try {
                    prog = (float) ((Number) progress.invoke(stateManager.invoke(d))).doubleValue();
                } catch (Throwable ignored) {
                }
            }
            int color = switch (st) {
                case "GRACE" -> 0xFFFFB74D;
                case "GRACE_CRIT" -> 0xFFFF5252;
                case "REGENERATING" -> 0xFFFFD54F;
                case "POST" -> 0xFF4FC3F7;
                default -> 0xFF9CFF5A;
            };
            rows.add(new ArcHudApi.PanelRow(Component.translatable("archud.regen.state").getString(),
                    Component.translatable("archud.regen.state." + st.toLowerCase()).getString(), prog, color, !"ALIVE".equals(st)));
            if (glow) rows.add(new ArcHudApi.PanelRow(Component.translatable("archud.regen.glowing").getString(), "✦", -1F, 0xFFFFE082, true));
            return rows;
        } catch (Throwable t) {
            return null;
        }
    }
}
