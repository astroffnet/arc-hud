<!-- ============================================================
     Arc HUD — описание для Modrinth
     1) «Summary» (краткое описание, до 256 символов) — ниже, в разделе SUMMARY.
     2) «Description» — скопируйте блок между линиями ===== (русский + английский).
     ============================================================ -->

## SUMMARY

**RU:** Новый HUD, мини-карта и навигатор с метками в мире, общая карта для сервера, панель хоста LAN, переработанные меню и оптимизация FPS. Forge и Fabric 1.20.1.

**EN:** A new HUD, minimap and navigator with in-world waypoints, a shared server map, a LAN host panel, redesigned menus and FPS optimizations. Forge & Fabric 1.20.1.

---

## DESCRIPTION

=====

![Arc HUD](https://raw.githubusercontent.com/astroffnet/arc-hud/main/docs/banner.png)

# 🇷🇺 Arc HUD

**Arc HUD** — единый стиль для всего интерфейса Minecraft: аккуратный HUD, своя карта с навигатором, переработанные меню и десятки мелочей, которые делают игру удобнее. Каждая функция отключается в настройках (клавиша **J**).

## ✨ HUD
- Плавные полосы здоровья, голода, брони, воздуха и опыта: поглощение, «след» урона, вспышка лечения, цвет от яда, иссушения и заморозки.
- Инфо-панель: координаты, биом, измерение и мод, FPS, пинг, время, погода, скорость, освещённость.
- Компас-лента с метками, информация о цели (моб, здоровье, мод), прочность брони и инструментов, счётчики стрел и тотемов, эффекты с таймерами.
- Каждый блок HUD можно привязать к любому краю экрана.
- Предпросмотр еды в руке и полоса истощения — с поддержкой **AppleSkin**.

## 🗺️ Карта и навигация
- Мини-карта (квадратная или круглая) и карта мира (**M**) — отдельно для каждого измерения, с пещерным режимом. Карта сохраняется на диск.
- Метки: свои, места смерти, спавн, Waystones, Tempad и метки от других игроков. Меню меток (**Y**) с поиском и сортировкой; **DEL** удаляет метку.
- **Метки в мире** — аккуратные маркеры без наложений. Видны, только когда вы смотрите в их сторону; у прицела раскрывается карточка с расстоянием, высотой и координатами.
- **Навигатор**: стрелка, расстояние, разница высот, время в пути, пересчёт координат Незер ↔ Верхний мир.
- Экспорт карты в PNG, сетка чанков, слизневые чанки, след игрока.

## 🌐 Мультиплеер
- **Общая карта**: каждый видит места, которые исследовали другие игроки, если Arc HUD стоит на сервере или у хоста.
- Игроки за пределами прорисовки на карте и зоны их прогрузки; кнопка «скрыть себя с карты» в меню паузы.
- Обмен метками: с конкретным игроком или со всеми.
- **Панель хоста LAN**: кик, бан, оператор, режим игры, телепорт, лечение, время, погода, правила игры, белый список, сообщение всем — и **закрытие игры для сети**. При открытии для сети показывается полный IP-адрес.
- Безопасный телепорт с карты: сервер сам находит твёрдую поверхность, не над пустотой и не в лаве.

## 🖥️ Новые меню
Главное меню, «Одиночная игра» (карточки миров, поиск), «Сетевая игра» (пинг, онлайн, LAN, **запоминание последнего сервера**), создание мира со всеми вкладками и подменю, меню паузы, настройки, управление (поиск и конфликты клавиш), достижения, статистика, ресурспаки, инвентарь и чат — всё в едином стиле.

## ⚡ Оптимизация
- Мобы, сундуки, таблички и баннеры за стенами не рисуются (occlusion culling).
- Рамки, картины и стойки для брони не рисуются дальше заданного расстояния.
- В фоне игра работает на пониженном FPS, свёрнутая — на 1 кадре.
- Карта снижает нагрузку при низком FPS.

## 🎒 Ещё
- Рюкзаки трёх размеров со слотом на спине (Curios / Trinkets) и своей клавишей.
- BunnyHop в стиле Source — включается и выключается клавишей **N** (на серверах с античитом лучше выключить).

## 🔌 Интеграции
Waystones · Tempad (локации как метки, дверь Tempad к любой метке, точке карты или игроку, без перезарядки, Tempad остаётся в браслете после смерти) · Ad Astra · Regeneration (Doctor Who) · Legends Core · Simple Voice Chat · Plasmo Voice · AppleSkin · SkinsRestorer · Curios / Trinkets · Embeddium / Sodium · Oculus / Iris · OptiFine · Mod Menu.

## 📦 Установка
- **Forge 47+** или **Fabric Loader 0.14.21+ с Fabric API**, Minecraft **1.20.1**.
- Мод клиентский. Если поставить его ещё и на сервер, работают общая карта, игроки на карте, обмен метками, безопасный телепорт и двери Tempad. Без мода на сервере к нему можно подключиться как обычно.
- Рекомендуется Curios (Forge) или Trinkets (Fabric) — для слотов рюкзака и браслета.

---

# 🇬🇧 Arc HUD

**Arc HUD** gives the whole Minecraft interface one clean style: a polished HUD, a built-in map with a navigator, redesigned menus and dozens of quality-of-life touches. Every feature can be turned off in the settings (**J**).

## ✨ HUD
- Smooth health, hunger, armor, air and XP bars, with absorption, a damage "trail", a heal flash and colors for poison, wither and freezing.
- Info panel: coordinates, biome, dimension and mod, FPS, ping, time, weather, speed and light level.
- A compass strip with waypoints, target info (mob, health, mod), armor and tool durability, arrow and totem counters, and effects with timers.
- Anchor any HUD block to any edge of the screen.
- Held-food preview and an exhaustion bar, with **AppleSkin** support.

## 🗺️ Map & navigation
- Minimap (square or round) and world map (**M**), kept separately for every dimension, with a cave mode. The map is saved to disk.
- Waypoints: your own, deaths, spawn, Waystones, Tempad and waypoints shared by other players. A waypoint menu (**Y**) with search and sorting; **DEL** deletes a waypoint.
- **In-world waypoints**: clean markers that never overlap. They only show when you look their way, and the one at your crosshair expands into a card with distance, height and coordinates.
- **Navigator**: an arrow, distance, height difference, ETA, and Nether ↔ Overworld coordinate conversion.
- Map PNG export, chunk grid, slime chunks and your own trail.

## 🌐 Multiplayer
- **Shared map**: everyone sees what other players have explored, when Arc HUD is on the server or the LAN host.
- Players beyond render distance appear on the map along with their loaded areas, plus a "hide me from the map" button in the pause menu.
- Share waypoints with one player or with everyone.
- **LAN host panel**: kick, ban, op, game mode, teleport, heal, time, weather, game rules, whitelist, broadcast — and **close the LAN game**. Opening to LAN shows your full IP address.
- Safe map teleport: the server finds solid ground, never over the void or in lava.

## 🖥️ Redesigned menus
Title screen, Singleplayer (world cards, search), Multiplayer (ping, players, LAN, **remembers your last server**), world creation with every tab and sub-menu, pause menu, options, controls (search and key conflicts), advancements, statistics, resource packs, inventory and chat — all in one style.

## ⚡ Performance
- Mobs, chests, signs and banners hidden behind walls are not rendered (occlusion culling).
- Item frames, paintings and armor stands are not rendered beyond a set distance.
- Lower FPS while the game is in the background, and 1 FPS while minimized.
- The map scans less often when FPS is low.

## 🎒 Also
- Backpacks in three sizes with a back slot (Curios / Trinkets) and their own key.
- Source-style BunnyHop, toggled with **N** (better turned off on servers with an anti-cheat).

## 🔌 Integrations
Waystones · Tempad (locations as waypoints, Tempad doors to any waypoint, map point or player, no cooldown, the Tempad stays in your bracelet slot on death) · Ad Astra · Regeneration (Doctor Who) · Legends Core · Simple Voice Chat · Plasmo Voice · AppleSkin · SkinsRestorer · Curios / Trinkets · Embeddium / Sodium · Oculus / Iris · OptiFine · Mod Menu.

## 📦 Installation
- **Forge 47+** or **Fabric Loader 0.14.21+ with Fabric API**, Minecraft **1.20.1**.
- Arc HUD is a client-side mod. Installing it on the server as well enables the shared map, players on the map, waypoint sharing, safe teleports and Tempad doors. Servers without it can still be joined as usual.
- Curios (Forge) or Trinkets (Fabric) is recommended for the backpack and bracelet slots.

**Author:** arc4io · **License:** MIT · **Source:** https://github.com/astroffnet/arc-hud

=====

---

## Настройки страницы Modrinth (подсказка)

- **Categories:** Utility, Transportation, Optimization, Social, Storage
- **Environment:** Client — required, Server — optional
- **Loaders:** Forge, Fabric · **Game version:** 1.20.1
- **Icon:** `docs/icon.png` (512×512) · **Gallery:** `docs/banner.png`
- **License:** MIT · **Source code:** https://github.com/astroffnet/arc-hud · **Issues:** https://github.com/astroffnet/arc-hud/issues
