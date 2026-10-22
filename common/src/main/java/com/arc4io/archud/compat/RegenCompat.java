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
public final class RegenCompat {
    private static Method get, regens, state, stateManager, progress, glowing, curTrait, nextTrait, traitActive, traitTitle, traitDesc, traitColor;
    private static boolean ready;

    private RegenCompat() {}

    static void init() throws Exception {
        Class<?> data = Integrations.cls("mc.craig.software.regen.common.regen.RegenerationData");
        get = data.getMethod("get", LivingEntity.class);
        regens = data.getMethod("regens");
        state = data.getMethod("regenState");
        glowing = data.getMethod("glowing");
        stateManager = data.getMethod("stateManager");
        progress = stateManager.getReturnType().getMethod("stateProgress");
        try {
            curTrait = data.getMethod("getCurrentTrait");
            nextTrait = data.getMethod("getNextTrait");
            traitActive = data.getMethod("isTraitActive");
            Class<?> tb = Integrations.cls("mc.craig.software.regen.common.traits.trait.TraitBase");
            traitTitle = tb.getMethod("getTitle");
            traitDesc = tb.getMethod("getDescription");
            traitColor = tb.getMethod("getPotionColor");
        } catch (Throwable t) {
            curTrait = null;
        }
        ready = true;
        ArcHudApi.registerPanel(ArcHud.id("regen"), Component.translatable("archud.regen.title"), 0xFFFFB74D, RegenCompat::rows);
        ArcHud.LOGGER.info("Arc HUD: интеграция с Regeneration включена");
    }

    /** Особенность (трейт) регенерации: название, описание бафа, активна ли. null — «просто человек». */
    public record Trait(String id, String title, String description, boolean active, int color) {}

    private static Trait trait(Object data, Method which) {
        if (which == null) return null;
        try {
            Object t = which.invoke(data);
            if (t == null) return null;
            Component title = (Component) traitTitle.invoke(t);
            String key = title.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tc ? tc.getKey() : "";
            String id = key.startsWith("trait.") ? key.substring(6, key.lastIndexOf('.')) : key;
            if (id.equals("human")) return null;
            boolean active = which == curTrait && (boolean) traitActive.invoke(data);
            int color = ((Number) traitColor.invoke(t)).intValue() & 0xFFFFFF;
            return new Trait(id, title.getString(), ((Component) traitDesc.invoke(t)).getString(), active, color == 0 ? 0xFFB74D : color);
        } catch (Throwable e) {
            return null;
        }
    }

    /** Карточка «Особенность» поверх экранов Regeneration (настройки, инкарнация, цвета). */
    public static void renderScreenOverlay(net.minecraft.client.gui.screens.Screen screen, net.minecraft.client.gui.GuiGraphics g) {
        if (!ready || !ArcConfig.get().regenerationIntegration) return;
        if (!screen.getClass().getName().startsWith("mc.craig.software.regen.client.screen.")) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        try {
            Optional<?> opt = (Optional<?>) get.invoke(null, mc.player);
            if (opt.isEmpty()) return;
            Object d = opt.get();
            Trait cur = trait(d, curTrait);
            Trait next = trait(d, nextTrait);
            var font = mc.font;
            int accent = com.arc4io.archud.client.hud.Theme.accent();
            int w = 170, x = 6, y = 6;
            List<net.minecraft.util.FormattedCharSequence> desc = cur == null
                    ? font.split(Component.translatable("archud.regen.trait.none"), w - 12)
                    : font.split(Component.literal(cur.description()), w - 12);
            int h = 30 + desc.size() * 10 + (next != null ? 22 : 0) + 12;
            g.pose().pushPose();
            g.pose().translate(0, 0, 300);
            com.arc4io.archud.client.hud.Draw.panel(g, x, y, w, h, 0xE00B0F14,
                    com.arc4io.archud.client.hud.Draw.alpha(cur != null ? cur.color() : accent, 0xC0));
            g.drawString(font, Component.translatable("archud.regen.trait"), x + 6, y + 5, com.arc4io.archud.client.hud.Draw.TEXT_DIM, true);
            int ty = y + 17;
            if (cur != null) {
                g.drawString(font, "✧ " + cur.title(), x + 6, ty, 0xFF000000 | cur.color(), true);
                ty += 12;
            } else ty += 2;
            for (var line : desc) {
                g.drawString(font, line, x + 6, ty, com.arc4io.archud.client.hud.Draw.TEXT, true);
                ty += 10;
            }
            if (cur != null) {
                Component st = Component.translatable(cur.active() ? "archud.regen.trait.active" : "archud.regen.trait.inactive");
                com.arc4io.archud.client.hud.Draw.small(g, font, st.getString(), x + 6, ty + 1, cur.active() ? 0xFF9CFF5A : 0xFFFF8A65, 0.7F);
            }
            ty += 12;
            if (next != null) {
                com.arc4io.archud.client.hud.Draw.small(g, font, Component.translatable("archud.regen.trait.next").getString(), x + 6, ty, 
                        com.arc4io.archud.client.hud.Draw.TEXT_DIM, 0.7F);
                com.arc4io.archud.client.hud.Draw.small(g, font, next.title() + " — " + next.description(), x + 6, ty + 9, 0xFF000000 | next.color(), 0.7F);
            }
            g.pose().popPose();
        } catch (Throwable ignored) {
        }
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
            Trait trait = trait(d, curTrait);
            if (left <= 0 && "ALIVE".equals(st) && trait == null) return null;
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
            if (trait != null) {
                rows.add(new ArcHudApi.PanelRow("✧ " + trait.description(), trait.active() ? "" : "✕", -1F,
                        trait.active() ? 0xFF000000 | trait.color() : 0xFF8C9AAB, false));
            }
            if (glow) rows.add(new ArcHudApi.PanelRow(Component.translatable("archud.regen.glowing").getString(), "✦", -1F, 0xFFFFE082, true));
            return rows;
        } catch (Throwable t) {
            return null;
        }
    }
}
