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
    public boolean minimapCircle = false;
    public boolean minimapNightTint = true;
    public boolean minimapChunkGrid = false;
    public boolean minimapSlimeChunks = false;
    public boolean minimapTrail = true;
    public boolean minimapPlayerNames = true;
    public boolean minimapFacing = true;
    public boolean worldWaypoints = true;
    public boolean waypointBeams = true;
    /** Метки за краем экрана — стрелками у края (по умолчанию выключено: видны только метки в поле зрения). */
    public boolean waypointEdgeArrows = false;
    public boolean waypointLabels = true;
    public boolean navigator = true;
    public boolean navigateToDeath = false;
    public boolean spawnWaypoint = true;
    public boolean waystoneToasts = true;
    public boolean netherCoordinates = true;
    public int waypointMaxDistance = 0;
    // ---- Сеть (общая карта, игроки, метки) ----
    public boolean mapSharing = true;
    public boolean showRemotePlayers = true;
    public boolean showLoadZones = false;
    public boolean acceptSharedWaypoints = true;
    /** Серверные настройки (действуют, если этот клиент — хост или это выделенный сервер). */
    public boolean serverMapSync = true;
    public boolean serverPlayerPositions = true;
    public boolean serverWaypointSharing = true;
    public boolean serverTempadToPlayers = true;
    /** Скрыть себя с карт других игроков. */
    public boolean hideFromMap = false;
    /** Tempad без перезарядки и сохраняется в браслете после смерти. */
    public boolean tempadNoCooldown = true;
    public boolean tempadKeepOnDeath = true;
    public List<String> hiddenWaypointSources = new ArrayList<>();
    /** Размер блоков HUD: id -> масштаб (0.5–2.0). */
    public java.util.Map<String, Float> hudScales = new java.util.HashMap<>();
    /** Запомненные точки возрождения (кровать) по мирам: worldKey -> "dim|x|y|z|anchor". */
    public java.util.Map<String, String> respawnPoints = new java.util.HashMap<>();
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
    public boolean customEnchanting = true;

    // ---------------- сетевая игра
    /** Избранные серверы (адреса). */
    public java.util.List<String> favoriteServers = new java.util.ArrayList<>();
    /** Заметки к серверам: адрес → текст. */
    public java.util.Map<String, String> serverNotes = new java.util.HashMap<>();
    /** Статистика серверов: адрес → время, входы, последний вход. */
    public java.util.Map<String, ServerStat> serverStats = new java.util.HashMap<>();
    /** Автопереподключение при обрыве связи. */
    public boolean autoReconnect = false;

    public static class ServerStat {
        public String name = "";
        public long playSeconds;
        public long lastJoin;
        public int joins;
    }

    // ---------------- главный экран
    /** Свой ник (только одиночная игра и серверы без лицензии); пусто — ник аккаунта. */
    public String customNickname = "";

    // ---------------- 40 фишек
    public boolean infoNetherCoords = true;
    public boolean infoMoonPhase = true;
    public boolean infoDayNightTimer = true;
    public boolean infoSlimeChunk = true;
    public boolean infoWeatherForecast = true;
    public boolean infoSessionTime = true;
    public boolean targetBlockDetails = true;
    public boolean targetMobStats = true;
    /** Прицел: 0 — ванильный, 1 — точка, 2 — крест, 3 — круг. */
    public int crosshairStyle = 0;
    public boolean hitMarker = true;
    public boolean damageNumbers = true;
    public boolean arrowCounter = true;
    public boolean lowDurabilityCrosshair = true;
    public boolean lowDurabilityWarning = true;
    public boolean fallWarning = true;
    public boolean elytraHud = true;
    public boolean zoomEnabled = true;
    public boolean freelookEnabled = true;
    public boolean alwaysSprint = false;
    public boolean fullbright = false;
    public boolean chatTimestamps = true;
    public boolean chatCollapse = true;
    public boolean chatMentions = true;
    public boolean deathCoordsChat = true;
    public boolean containerSearch = true;
    public boolean containerButtons = true;
    public boolean tooltipDurability = true;
    public boolean tooltipModName = true;
    public boolean tooltipFood = true;
    public boolean tooltipRepairCost = true;
    public boolean biomeTitle = true;
    public boolean hideRain = false;
    public boolean afkOverlay = true;
    /** Напоминание сделать перерыв, минут (0 — выкл.). */
    public int breakReminder = 120;
    public boolean fullInventoryWarning = true;
    /** Новое креатив-меню Arc HUD. */
    public boolean customCreative = true;
    /** Избранные предметы креатив-меню (NBT стака одной строкой). */
    public java.util.List<String> creativeFavorites = new java.util.ArrayList<>();
    /** Ники, для которых брали головы в креатив-меню. */
    public java.util.List<String> creativeHeads = new java.util.ArrayList<>();
    /** Выборочное зачарование (решает хост / сервер). */
    public boolean enchantSelective = true;
    /** Переброс вариантов зачарования за 1 лазурит (решает хост / сервер). */
    public boolean enchantReroll = true;
    public boolean customWorldSelect = true;
    public boolean customMultiplayer = true;
    public boolean customGameRules = true;
    public boolean customTabList = true;
    public boolean weaponInspect = true;
    // ---- Камера от третьего лица (F6) ----
    public boolean cameraFreeLook = true;
    /** F5: от первого лица → камера Arc → обычная от третьего → от первого. */
    public boolean cameraInF5Cycle = true;
    public boolean cameraFrontView = false;
    public boolean cameraSmoothing = true;
    public boolean cameraLeftShoulder = false;
    public boolean cameraCrosshair = true;
    public float cameraDistance = 4.0F;
    /** Текущий вид от первого лица: 0 — обычный, 1 — улучшенный, 2 — бодикам (меняется по F5). */
    public int firstPersonStyle = 0;
    /** Улучшенный вид от первого лица в цикле F5. */
    public boolean fpEnhancedInF5Cycle = true;
    /** Бодикам в цикле F5. */
    public boolean fpBodycamInF5Cycle = true;
    public int bodycamFov = 100;
    public boolean bodycamLens = true;
    public boolean bodycamHideCrosshair = true;
    public float cameraShoulder = 0.75F;
    public float cameraHeight = 0.25F;
    public boolean inspectCard = true;
    /** Автомасштаб экранов, если им не хватает места (мониторы 4:3, маленькие окна, крупный масштаб интерфейса). */
    public boolean autoGuiScale = true;
    /** Последний сервер, на который заходили (для «Продолжить» в сетевой игре). */
    public String lastServerIp = "";
    public String lastServerName = "";

    // ---- Интеграции ----
    public boolean voiceIntegration = true;
    // ---- Оптимизация ----
    public boolean entityCulling = true;
    public boolean blockEntityCulling = true;
    public boolean cullPlayers = false;
    public int decorCullDistance = 48;
    public boolean dynamicFps = true;
    public int backgroundFps = 15;
    public boolean adaptiveMapScan = true;
    public boolean foodPreview = true;
    public boolean exhaustionBar = true;
    public boolean tempadPanel = true;
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
            if (instance.hiddenWaypointSources == null) instance.hiddenWaypointSources = new ArrayList<>();
            if (instance.hudScales == null) instance.hudScales = new java.util.HashMap<>();
            if (instance.respawnPoints == null) instance.respawnPoints = new java.util.HashMap<>();
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
