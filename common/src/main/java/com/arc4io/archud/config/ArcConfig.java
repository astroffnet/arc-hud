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
    /** Своё место для панели отдельного мода (id панели -> зона); нет записи — общая зона панелей модов. */
    public java.util.Map<String, HudAnchor> panelAnchors = new java.util.HashMap<>();
    /** Скрытые панели модов (id панели). */
    public List<String> hiddenPanels = new ArrayList<>();
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
    /** Сервер хранит метки каждого игрока в папке мира и отдаёт их при входе. */
    public boolean serverWaypointSync = true;
    /** Сервер: хранить инвентарь (playerdata), достижения и статистику игроков по нику, а не по UUID. */
    public boolean nicknameStorage = true;
    /** Сервер: AntiESP — не отправлять игрокам сущности, скрытые за непрозрачными блоками. */
    public boolean antiEsp = true;
    /** Сервер: минимальный уровень оператора для админ-панели (1–4). */
    public int adminPermissionLevel = 2;
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
    /** Быстрая смена языка и языковых пакетов (без полной перезагрузки ресурсов). */
    public boolean fastLanguageReload = true;
    // ---------------- производительность (дополнительно)
    public boolean particleCulling = true;
    /** Максимум частиц за кадр (0 — без ограничения). */
    public int maxParticles = 4000;
    /** Тени сущностей дальше N блоков не рисуются (0 — как в ванилле). */
    public int shadowDistance = 32;
    /** Текст табличек дальше N блоков не рисуется (0 — как в ванилле). */
    public int signTextDistance = 24;
    public boolean lazyTextureAnimations = true;
    /** Своё меню F3. */
    public boolean customF3 = true;
    /** Оптимизация: значения 100 переключателей (id → значение, 0 — выключено). */
    public java.util.Map<String, Integer> perf = new java.util.HashMap<>();
    /** Ванильные значения, которые вернуть при выключении переключателя оптимизации. */
    public java.util.Map<String, Integer> perfRestore = new java.util.HashMap<>();
    /** Пресет «Баланс» уже применён при первом запуске. */
    public boolean perfOffered = false;
    // ---- встроенный шейдер и масштаб рендера (настройки графики → «Эффекты»)
    public boolean shaderEnabled = false;
    public boolean shaderHdr = true;
    public int shaderHdrStrength = 55;
    public boolean shaderBloom = true;
    public int shaderVibrance = 20;
    public int shaderContrast = 8;
    public int shaderTemperature = 0;
    public int shaderSharpen = 25;
    public boolean shaderFxaa = true;
    /** Масштаб рендера мира, % (100 — выключено). */
    public int renderScale = 100;
    /** 0 — билинейно, 1 — в стиле FSR (бикубически + RCAS). */
    public int upscaler = 1;
    public int upscaleSharpness = 50;
    // ---- шевеление растений
    public boolean wavingPlants = true;
    public boolean wavingLeaves = true;
    public int wavingStrength = 50;
    public int wavingSpeed = 50;
    /** Следить за папкой mods и предлагать быстрый перезапуск с возвратом в мир. */
    public boolean modWatcher = true;
    /** Свой экран настроек графики (ванилла, Sodium / Embeddium, OptiFine). */
    public boolean customVideoSettings = true;
    /** Встроенный ресурспак «Родное слово» (русский перевод модов). */
    public boolean builtinRussianPack = true;
    /** Встроенный пакет уже был включён автоматически (дальше решает игрок). */
    public boolean builtinRussianPackOffered = false;
    /** Поддержка CIT-ресурспаков OptiFine на Forge (на Fabric работает встроенный CIT Resewn). */
    public boolean citSupport = true;

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
    public boolean infoMoonPhase = false;
    public boolean infoDayNightTimer = true;
    public boolean infoSlimeChunk = true;
    public boolean infoWeatherForecast = false;
    public boolean infoSessionTime = false;
    public boolean targetBlockDetails = true;
    public boolean targetMobStats = true;
    /** Прицел: 0 — ванильный, 1 — точка, 2 — крест, 3 — круг. */
    public int crosshairStyle = 0;
    /** Цвет прицела (ARGB), 0 — цвет темы. */
    public int crosshairColor = 0;
    public int crosshairSize = 6;
    public int crosshairThickness = 2;
    public int crosshairGap = 3;
    public boolean crosshairOutline = true;
    public boolean crosshairDot = false;
    public int crosshairOpacity = 100;
    /** Прицел краснеет, когда наведён на моба/игрока. */
    public boolean crosshairHitColor = true;
    public boolean hitMarker = true;
    public boolean mobDamageNumbers = true;
    public boolean arrowCounter = true;
    public boolean lowDurabilityCrosshair = true;
    public boolean breakSound = true;
    public boolean infoCanSleep = true;
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
    /** Автосохранение мира (одиночная игра / «Открыть для сети») по реальному времени. */
    public boolean autosave = true;
    /** Антикрашер: после краша — экран с возвратом в главное меню вместо закрытия игры. */
    public boolean antiCrash = true;
    /** Перенос длинных подсказок, чтобы они не уходили за экран. */
    public boolean tooltipWrap = true;
    /** Перезагрузка ресурсов в фоне, без экрана загрузки (смена ресурспака, F3+T). */
    public boolean backgroundReload = true;
    /** Моды без кода (только ресурсы) подгружаются сразу, без перезапуска. */
    public boolean hotLoad = true;
    /** Сервер: метки-легенды построек (деревни, аванпосты…), открытых игроками. */
    public boolean serverLegends = true;
    /** Показывать легенды на картах. */
    public boolean showLegends = true;
    /** Версия набора оптимизаций: новым переключателям при обновлении один раз ставятся значения «Баланса». */
    public int perfVersion = 0;
    public int autosaveMinutes = 3;
    /** Сообщение над хотбаром после сохранения. */
    public boolean autosaveNotify = true;
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
    /** Головы игроков у сообщений чата. */
    public boolean chatHeads = true;

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
            if (instance.panelAnchors == null) instance.panelAnchors = new java.util.HashMap<>();
            instance.panelAnchors.values().removeIf(java.util.Objects::isNull);
            if (instance.hiddenPanels == null) instance.hiddenPanels = new ArrayList<>();
            if (instance.respawnPoints == null) instance.respawnPoints = new java.util.HashMap<>();
            if (instance.infoPanelAnchor == null) instance.infoPanelAnchor = HudAnchor.TOP_LEFT;
            if (instance.compassAnchor == null) instance.compassAnchor = HudAnchor.TOP_CENTER;
            if (instance.effectsAnchor == null) instance.effectsAnchor = HudAnchor.TOP_RIGHT;
            if (instance.gearAnchor == null) instance.gearAnchor = HudAnchor.LEFT;
            if (instance.pickupsAnchor == null) instance.pickupsAnchor = HudAnchor.BOTTOM_RIGHT;
            if (instance.statusAnchor == null) instance.statusAnchor = HudAnchor.BOTTOM_LEFT;
            if (instance.minimapAnchor == null) instance.minimapAnchor = HudAnchor.TOP_RIGHT;
            if (instance.perf == null) instance.perf = new java.util.HashMap<>();
            if (instance.perfRestore == null) instance.perfRestore = new java.util.HashMap<>();
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
