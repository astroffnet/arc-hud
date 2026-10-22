package com.arc4io.archud.config;

import com.arc4io.archud.ArcHud;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Настройки Arc HUD. Файл: config/archud.json
 * Все boolean-поля автоматически появляются в экране настроек мода.
 */
public class ArcConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ArcConfig instance = new ArcConfig();

    // ---- Общее ----
    public boolean enabled = true;
    public boolean dimensionThemes = true;
    public boolean smoothAnimations = true;

    // ---- Полосы ----
    public boolean healthBar = true;
    public boolean absorption = true;
    public boolean healthNumbers = true;
    public boolean damageTrail = true;
    public boolean healGlow = true;
    public boolean statusColors = true;
    public boolean lowHealthWarning = true;
    public boolean hungerBar = true;
    public boolean saturation = true;
    public boolean hungerEffectColor = true;
    public boolean armorBar = true;
    public boolean toughness = true;
    public boolean airBar = true;
    public boolean xpBar = true;
    public boolean mountHealth = true;
    public boolean mountJump = true;

    // ---- Хотбар ----
    public boolean customHotbar = true;
    public boolean offhandSlot = true;
    public boolean slotNumbers = true;
    public boolean attackCooldown = true;

    // ---- Эффекты ----
    public boolean effectsPanel = true;
    public boolean effectTimers = true;

    // ---- Информационная панель ----
    public boolean infoPanel = true;
    public boolean coordinates = true;
    public boolean facing = true;
    public boolean biome = true;
    public boolean dimension = true;
    public boolean fps = true;
    public boolean ping = true;
    public boolean gameTime = true;
    public boolean realTime = true;
    public boolean dayCounter = true;
    public boolean weather = true;
    public boolean speed = true;
    public boolean lightLevel = true;

    // ---- Верх экрана ----
    public boolean compass = true;
    public boolean targetInfo = true;
    public boolean blockInfo = true;
    public boolean modNames = true;

    // ---- Снаряжение ----
    public boolean armorDurability = true;
    public boolean heldDurability = true;
    public boolean lowDurabilityWarning = true;
    public boolean ammoCounter = true;
    public boolean totemCounter = true;
    public boolean itemEnergy = true;

    // ---- События ----
    public boolean fallWarning = true;
    public boolean damageNumbers = true;
    public boolean pickupNotifications = true;
    public boolean movementStatus = true;
    public boolean addonBars = true;

    // ---- Расположение блоков HUD ----
    public HudAnchor infoPanelAnchor = HudAnchor.TOP_LEFT;
    public HudAnchor compassAnchor = HudAnchor.TOP_CENTER;
    public HudAnchor effectsAnchor = HudAnchor.TOP_RIGHT;
    public HudAnchor gearAnchor = HudAnchor.LEFT;
    public HudAnchor pickupsAnchor = HudAnchor.BOTTOM_RIGHT;
    public HudAnchor statusAnchor = HudAnchor.BOTTOM_LEFT;
    public HudAnchor addonPanelsAnchor = HudAnchor.RIGHT;
    public HudAnchor minimapAnchor = HudAnchor.TOP_RIGHT;

    // ---- Мини-карта ----
    public boolean minimap = true;
    public boolean minimapRotate = false;
    public boolean minimapEntities = true;
    public boolean minimapCaveMode = true;
    public boolean minimapWaypoints = true;
    public boolean compassWaypoints = true;
    public boolean deathWaypoint = true;
    public int minimapSize = 110;
    public double minimapZoom = 1.5;

    // ---- Новые экраны ----
    public boolean arcWidgets = true;
    public boolean customPauseScreen = true;
    public boolean customOptionsScreens = true;
    public boolean customAdvancements = true;
    public boolean customStats = true;
    public boolean customLanScreen = true;
    public boolean customModList = true;
    public boolean customInventory = true;

    // ---- Интеграции ----
    public boolean waystonesIntegration = true;
    public boolean tempadIntegration = true;
    public boolean adAstraIntegration = true;
    public boolean regenerationIntegration = true;
    public boolean shaderInfo = true;
    public boolean rendererInfo = false;

    // ---- Совместимость ----
    /** Не заменять главное меню, если его уже меняет другой мод (Aether, FancyMenu и т.п.). */
    public boolean yieldTitleScreenToMods = true;
    /** Скрывать полосы HP/брони других модов, которые дублируют Arc HUD (например Legends). */
    public boolean hideDuplicateModBars = true;

    // ---- Крафт ----
    public boolean recipeChooser = true;

    // ---- Чат ----
    public boolean chatMessagesStyle = true;

    // ---- Экраны ----
    public boolean customTitleScreen = true;
    public boolean customControlsScreen = true;
    public boolean chatStyle = true;
    public boolean chatAnimation = true;
    public boolean inventoryStats = true;
    public boolean inventoryButtons = true;

    // ---- BunnyHop ----
    public boolean bunnyHop = true;
    public boolean bhopHud = true;
    public boolean bhopSingleplayerOnly = false;
    public double bhopAirAccel = 0.04;
    public double bhopAirWishSpeed = 0.06;
    public double bhopMaxSpeed = 0.9;
    public double bhopLandingKeep = 0.99;

    /** Forge: id оверлеев других модов, которые надо скрыть (например "divinerpg:arcana"). */
    public List<String> hiddenOverlays = new ArrayList<>();

    public static ArcConfig get() {
        return instance;
    }

    private static Path file() {
        return ArcHud.platform().configDir().resolve("archud.json");
    }

    public static void load() {
        try {
            Path f = file();
            if (Files.exists(f)) {
                try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    ArcConfig loaded = GSON.fromJson(r, ArcConfig.class);
                    if (loaded != null) instance = loaded;
                }
            }
            if (instance.hiddenOverlays == null) instance.hiddenOverlays = new ArrayList<>();
            if (instance.infoPanelAnchor == null) instance.infoPanelAnchor = HudAnchor.TOP_LEFT;
            if (instance.compassAnchor == null) instance.compassAnchor = HudAnchor.TOP_CENTER;
            if (instance.effectsAnchor == null) instance.effectsAnchor = HudAnchor.TOP_RIGHT;
            if (instance.gearAnchor == null) instance.gearAnchor = HudAnchor.LEFT;
            if (instance.pickupsAnchor == null) instance.pickupsAnchor = HudAnchor.BOTTOM_RIGHT;
            if (instance.statusAnchor == null) instance.statusAnchor = HudAnchor.BOTTOM_LEFT;
            if (instance.minimapAnchor == null) instance.minimapAnchor = HudAnchor.TOP_RIGHT;
            if (instance.addonPanelsAnchor == null) instance.addonPanelsAnchor = HudAnchor.RIGHT;
            instance.minimapSize = Math.max(64, Math.min(200, instance.minimapSize));
            instance.minimapZoom = Math.max(0.25, Math.min(6.0, instance.minimapZoom));
            if (instance.addonPanelsAnchor == null) instance.addonPanelsAnchor = HudAnchor.RIGHT;
            save();
        } catch (Exception e) {
            ArcHud.LOGGER.error("Arc HUD: не удалось загрузить конфиг, используются значения по умолчанию", e);
            instance = new ArcConfig();
        }
    }

    public static void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
                GSON.toJson(instance, w);
            }
        } catch (Exception e) {
            ArcHud.LOGGER.error("Arc HUD: не удалось сохранить конфиг", e);
        }
    }
}
