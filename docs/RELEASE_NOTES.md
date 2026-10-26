<p align="center"><img src="https://raw.githubusercontent.com/astroffnet/arc-hud/main/docs/banner.png" width="720"></p>

## Arc HUD 1.2.0 — Minecraft 1.20.1 (Forge / NeoForge 1.20.1 / Fabric) + серверная часть

**Файлы / Files**
- `archud-forge-1.20.1-1.2.0.jar` — клиент (и LAN-хост) для Forge 47+ и NeoForge 1.20.1
- `archud-fabric-1.20.1-1.2.0.jar` — клиент для Fabric Loader 0.14.21+ и Fabric API
- `archud-server-forge-1.20.1-1.2.0.jar`, `archud-server-fabric-1.20.1-1.2.0.jar` — только для выделенного сервера (без ресурспака и клиентских библиотек)
- `archud-server-bukkit-1.2.0.jar` — плагин для Bukkit / Spigot / Paper 1.20.1–1.21.x

### 🇷🇺 Новое в 1.2.0
- **Метки на сервере:** сервер хранит метки каждого игрока по нику (с учётом регистра) и отдаёт их при входе.
- **Авторизация:** на серверах с /login и /register метки, карта и позиции игроков отдаются только после входа.
- **Данные игроков по нику:** инвентарь (playerdata), достижения и статистика хранятся по нику, а не по UUID.
- **Админ-панель:** более 60 действий для операторов (игроки, мир, правила игры, сервер, списки банов, журнал).
- **AntiESP:** сервер не отправляет сущности, скрытые за блоками; невидимые игроки не видны на карте.
- **Новая карта мира:** вкладки «Метки» и «Игроки», панель инструментов, линейка масштаба; список игроков в меню паузы.
- **Исправления:** FPS круглой мини-карты, дыры на карте, F3 вместе с HUD, камера Bodycam у стен, панель сети.


## Arc HUD 1.0.0 — Minecraft 1.20.1 (Forge + Fabric)

**Файлы / Files**
- `archud-forge-1.20.1-1.0.0.jar` — Forge 47+
- `archud-fabric-1.20.1-1.0.0.jar` — Fabric Loader 0.14.21+ и Fabric API

### 🇷🇺 Что внутри
- **HUD:** плавные полосы здоровья, голода, брони и опыта, инфо-панель, компас с метками, цель, прочность, эффекты; любой блок можно прикрепить к любому краю экрана.
- **Карта:** мини-карта и карта мира для каждого измерения, пещерный режим, метки (свои, смерти, спавн, Waystones, Tempad, от игроков), меню меток, метки в мире, навигатор.
- **Мультиплеер:** общая карта, игроки на карте, обмен метками, панель хоста LAN, безопасный телепорт.
- **Новые меню:** главное, одиночная и сетевая игра (запоминает последний сервер), создание мира и игровые правила, пауза, настройки, управление, достижения, статистика, ресурспаки. Автомасштаб для мониторов 4:3 и маленьких окон.
- **Оптимизация:** не рисуются мобы и сундуки за стенами; FPS снижается, пока игра в фоне.
- **Интеграции:** Waystones, Tempad, Ad Astra, Regeneration, Legends Core, Simple Voice Chat, Plasmo Voice, AppleSkin, SkinsRestorer, Curios/Trinkets, Embeddium/Sodium, Oculus/Iris.

Мод клиентский. Если поставить его и на сервер, заработают общая карта, обмен метками, безопасный телепорт и двери Tempad.

### 🇬🇧 Highlights
- **HUD:** smooth bars, an info panel, a compass with waypoints, target info, durability and effects; anchor any block to any screen edge.
- **Map:** a minimap and world map for every dimension, a cave mode, waypoints (deaths, spawn, Waystones, Tempad, shared), a waypoint menu, in-world markers and a navigator.
- **Multiplayer:** a shared map, players on the map, waypoint sharing, a LAN host panel and safe teleports.
- **Redesigned menus:** title, Singleplayer, Multiplayer (remembers your last server), world creation and game rules, pause, options, controls, advancements, stats and resource packs, with auto-scaling for 4:3 screens and small windows.
- **Performance:** mobs and chests behind walls are not rendered, and FPS is lowered while the game is in the background.
- **Integrations:** Waystones, Tempad, Ad Astra, Regeneration, Legends Core, Simple Voice Chat, Plasmo Voice, AppleSkin, SkinsRestorer, Curios/Trinkets, Embeddium/Sodium and Oculus/Iris.

Client-side mod. Installing it on the server as well enables the shared map, waypoint sharing, safe teleports and Tempad doors.
