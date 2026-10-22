package com.arc4io.archud.compat;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.client.hud.ArcHudApi;
import com.arc4io.archud.client.hud.Icons;
import com.arc4io.archud.config.ArcConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Ad Astra: кислород скафандра — большая полоса над HP, заряд реактивного костюма — тонкая полоса,
 * панель «Планета» (кислород в атмосфере, температура, гравитация). Родной HUD Ad Astra
 * (баллон и батарея) скрывается миксином AdAstraOverlayMixin, ракета/посадочный модуль остаются.
 */
public final class AdAstraCompat {
    private static Class<?> spaceSuit, jetSuit;
    private static Method oxygenAmount, fluidContainer, tankCapacity, energyStorage, storedEnergy, maxEnergy;
    private static Method localData, oxygen, temperature, gravity;
    private static boolean ready;

    private AdAstraCompat() {}

    static void init() throws Exception {
        spaceSuit = Integrations.cls("earth.terrarium.adastra.common.items.armor.SpaceSuitItem");
        oxygenAmount = spaceSuit.getMethod("getOxygenAmount", Entity.class);
        fluidContainer = spaceSuit.getMethod("getFluidContainer", ItemStack.class);
        tankCapacity = fluidContainer.getReturnType().getMethod("getTankCapacity", int.class);
        try {
            jetSuit = Integrations.cls("earth.terrarium.adastra.common.items.armor.JetSuitItem");
            energyStorage = jetSuit.getMethod("getEnergyStorage", ItemStack.class);
            storedEnergy = energyStorage.getReturnType().getMethod("getStoredEnergy");
            maxEnergy = energyStorage.getReturnType().getMethod("getMaxCapacity");
        } catch (Throwable ignored) {
        }
        Class<?> client = Integrations.cls("earth.terrarium.adastra.client.utils.ClientData");
        localData = client.getMethod("getLocalData");
        Class<?> planet = Integrations.cls("earth.terrarium.adastra.api.systems.PlanetData");
        oxygen = planet.getMethod("oxygen");
        temperature = planet.getMethod("temperature");
        gravity = planet.getMethod("gravity");
        ready = true;

        ArcHudApi.registerMainBar(ArcHud.id("adastra_oxygen"), Component.translatable("archud.adastra.oxygen"), 0xFF4FC3F7,
                Icons.BUBBLE, ArcHudApi.Side.RIGHT, AdAstraCompat::oxygenValues);
        if (energyStorage != null) {
            ArcHudApi.registerBar(ArcHud.id("adastra_energy"), Component.translatable("archud.adastra.energy"), 0xFFFFEE58, AdAstraCompat::energyValues);
        }
        ArcHudApi.registerPanel(ArcHud.id("adastra_planet"), Component.translatable("archud.adastra.planet"), 0xFF90CAF9, AdAstraCompat::planetRows);
        ArcHud.LOGGER.info("Arc HUD: интеграция с Ad Astra включена");
    }

    /** Используется миксином: скрывать ли родной HUD Ad Astra. */
    public static boolean replacesOverlay() {
        Minecraft mc = Minecraft.getInstance();
        if (!ready || !ArcConfig.get().enabled || !ArcConfig.get().adAstraIntegration || mc.player == null) return false;
        Entity v = mc.player.getVehicle();
        if (v != null) {
            String n = v.getClass().getSimpleName();
            if (n.contains("Rocket") || n.contains("Lander")) return false; // отсчёт запуска и посадка — оставляем родной интерфейс
        }
        return true;
    }

    private static ItemStack chest(LocalPlayer p) {
        return p.getItemBySlot(EquipmentSlot.CHEST);
    }

    private static float[] oxygenValues() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (!ready || p == null || !ArcConfig.get().adAstraIntegration) return null;
        try {
            ItemStack chest = chest(p);
            if (!spaceSuit.isInstance(chest.getItem())) return null;
            long amount = ((Number) oxygenAmount.invoke(null, p)).longValue();
            Object container = fluidContainer.invoke(chest.getItem(), chest);
            long cap = ((Number) tankCapacity.invoke(container, 0)).longValue();
            if (cap <= 0) return null;
            // в процентах — единицы жидкости на Forge и Fabric разные
            return new float[]{Math.round(amount * 1000F / cap) / 10F, 100F};
        } catch (Throwable t) {
            return null;
        }
    }

    private static float[] energyValues() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (!ready || p == null || jetSuit == null || !ArcConfig.get().adAstraIntegration) return null;
        try {
            ItemStack chest = chest(p);
            if (!jetSuit.isInstance(chest.getItem())) return null;
            Object storage = energyStorage.invoke(chest.getItem(), chest);
            long stored = ((Number) storedEnergy.invoke(storage)).longValue();
            long max = ((Number) maxEnergy.invoke(storage)).longValue();
            if (max <= 0) return null;
            return new float[]{Math.round(stored * 1000F / max) / 10F, 100F};
        } catch (Throwable t) {
            return null;
        }
    }

    private static List<ArcHudApi.PanelRow> planetRows() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (!ready || p == null || mc.level == null || !ArcConfig.get().adAstraIntegration) return null;
        boolean space = mc.level.dimension().location().getNamespace().equals("ad_astra");
        boolean suit = spaceSuit.isInstance(chest(p).getItem());
        if (!space && !suit) return null;
        try {
            Object data = localData.invoke(null);
            if (data == null) return null;
            boolean o2 = (boolean) oxygen.invoke(data);
            int temp = ((Number) temperature.invoke(data)).intValue();
            float g = ((Number) gravity.invoke(data)).floatValue();
            List<ArcHudApi.PanelRow> rows = new ArrayList<>();
            rows.add(new ArcHudApi.PanelRow(Component.translatable("archud.adastra.atmosphere").getString(),
                    Component.translatable(o2 ? "archud.adastra.breathable" : "archud.adastra.no_oxygen").getString(),
                    -1F, o2 ? 0xFF9CFF5A : 0xFFFF5252, !o2));
            int tc = temp > 60 ? 0xFFFF7043 : temp < -40 ? 0xFF81D4FA : 0xFFEAF2FA;
            rows.add(new ArcHudApi.PanelRow(Component.translatable("archud.adastra.temperature").getString(), temp + " °C", -1F, tc, false));
            rows.add(new ArcHudApi.PanelRow(Component.translatable("archud.adastra.gravity").getString(),
                    String.format("%.2f m/s²", g), -1F, 0xFFEAF2FA, false));
            return rows;
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean isLiving(Entity e) {
        return e instanceof LivingEntity;
    }
}
