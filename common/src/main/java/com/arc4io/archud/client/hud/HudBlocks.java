package com.arc4io.archud.client.hud;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.config.ArcConfig;
import com.arc4io.archud.config.HudAnchor;
import com.arc4io.archud.client.map.MapScanner;
import com.arc4io.archud.client.map.MapView;
import com.arc4io.archud.client.map.Navigator;
import com.arc4io.archud.client.map.TrailManager;
import com.arc4io.archud.client.map.Waypoint;
import com.arc4io.archud.client.map.WaypointManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.lang.reflect.Field;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Блоки HUD, которые можно прикрепить к любому краю экрана (см. {@link HudAnchor}).
 * Каждый блок сначала измеряется, затем раскладывается по своему краю стопкой.
 */
final class HudBlocks {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static Field bossMapField;
    private static boolean bossFieldSearched;

    interface Block {
        int w();

        int h();

        /** right — блок прижат к правому краю (для выравнивания содержимого и анимаций). */
        void draw(GuiGraphics g, int x, int y, boolean right);
    }

    private record InfoLine(int icon, Component text, int color) {}

    private HudBlocks() {}

    static void renderAnchored(GuiGraphics g, Minecraft mc, LocalPlayer p, Font font, int w, int h, int accent, long now,
                               boolean debug, boolean spectator) {
        ArcConfig cfg = ArcConfig.get();
        // своё F3 не прячет HUD: его карточки встают в стопки слева и справа, под блоки HUD
        boolean arcF3 = debug && ArcDebugScreen.enabled();
        Map<HudAnchor, List<Block>> map = new EnumMap<>(HudAnchor.class);
        if (!debug || arcF3) {
            if (cfg.minimap) add(map, cfg.minimapAnchor, "minimap", minimap(mc, p, font, accent));
            if (cfg.infoPanel) add(map, cfg.infoPanelAnchor, "info", info(mc, p, font, accent));
            if (cfg.compass) add(map, cfg.compassAnchor, "compass", compass(p, font, accent));
            add(map, cfg.compassAnchor, "compass", navigator(mc, p, font, accent));
            add(map, cfg.compassAnchor, "compass", target(mc, font, accent));
            if (cfg.effectsPanel) add(map, cfg.effectsAnchor, "effects", effects(mc, p, font, accent));
        }
        if (!spectator && cfg.addonBars) {
            for (ArcHudApi.Panel panel : ArcHudApi.panels()) add(map, cfg.addonPanelsAnchor, "addons", panel(panel, font, accent));
        }
        if (!spectator) {
            add(map, cfg.gearAnchor, "gear", gear(p, font, accent));
            if (cfg.pickupNotifications) add(map, cfg.pickupsAnchor, "pickups", pickups(font, accent, now));
            if (cfg.movementStatus) add(map, cfg.statusAnchor, "status", status(p, font, accent));
        }
        if (arcF3) {
            List<Block> l = new ArrayList<>(), r = new ArrayList<>();
            ArcDebugScreen.build(mc, font, accent, w, l, r);
            // карточки F3 — в самом верху углов, блоки HUD этих углов уходят под них
            map.computeIfAbsent(HudAnchor.TOP_LEFT, k -> new ArrayList<>()).addAll(0, l);
            map.computeIfAbsent(HudAnchor.TOP_RIGHT, k -> new ArrayList<>()).addAll(0, r);
            ArcDebugScreen.markLaidOut();
        }
        int boss = bossCount(mc) * 19;
        Map<HudAnchor, Integer> start = new EnumMap<>(HudAnchor.class);
        for (Map.Entry<HudAnchor, List<Block>> e : map.entrySet()) {
            HudAnchor a = e.getKey();
            int total = total(e.getValue());
            start.put(a, switch (a.row) {
                case 0 -> 2 + (a.col == 1 ? boss : 0);
                case 1 -> (h - total) / 2;
                default -> h - 2 - total;
            });
        }
        // стопки одного края не налезают друг на друга: середина сдвигается под верх, а если места
        // не хватает — у верхней стопки отбрасываются нижние карточки F3
        for (int col = 0; col <= 2; col += 2) {
            HudAnchor top = HudAnchor.at(col, 0), mid = HudAnchor.at(col, 1), bot = HudAnchor.at(col, 2);
            List<Block> tl = map.get(top);
            if (tl == null) continue;
            while (true) {
                int topEnd = start.get(top) + total(tl) + 3;
                int floor = map.containsKey(bot) ? start.get(bot) - 3 : h;
                // карточки F3 не заходят на хотбар и полосы здоровья, если столбец доходит до центра экрана
                int maxW = 0;
                boolean hasCards = false;
                for (Block b : tl) {
                    maxW = Math.max(maxW, b.w());
                    hasCards |= b instanceof ArcDebugScreen.CardBlock;
                }
                boolean reachesCenter = col == 0 ? 2 + maxW > w / 2 - 100 : w - 2 - maxW < w / 2 + 140;
                if (hasCards && reachesCenter) floor = Math.min(floor, h - 52);
                List<Block> ml = map.get(mid);
                if (ml != null) {
                    int my = Math.max(start.get(mid), topEnd);
                    start.put(mid, my);
                    topEnd = my + total(ml) + 3;
                }
                if (topEnd <= floor + 3) break;
                // не влезает — убираем нижние карточки F3 (первая, «Позиция» / «Производительность», остаётся всегда)
                int drop = -1, cards = 0;
                for (int i = 0; i < tl.size(); i++) {
                    if (tl.get(i) instanceof ArcDebugScreen.CardBlock) {
                        if (cards++ > 0) drop = i;
                    }
                }
                if (drop < 0) break;
                tl.remove(drop);
                if (ml != null) start.put(mid, (h - total(ml)) / 2);
            }
        }
        for (Map.Entry<HudAnchor, List<Block>> e : map.entrySet()) {
            HudAnchor a = e.getKey();
            List<Block> list = e.getValue();
            int y = start.get(a);
            for (Block b : list) {
                int x = a.col == 0 ? 2 : a.col == 2 ? w - 2 - b.w() : (w - b.w()) / 2;
                b.draw(g, x, y, a.col == 2);
                y += b.h() + 3;
            }
        }
    }

    private static int total(List<Block> list) {
        int total = -3;
        for (Block b : list) total += b.h() + 3;
        return total;
    }

    private static void add(Map<HudAnchor, List<Block>> map, HudAnchor anchor, String id, Block b) {
        if (b == null || b.h() <= 0 || anchor == null) return;
        float sc = HudScale.get(id);
        if (Math.abs(sc - 1F) > 0.001F) b = new Scaled(b, sc);
        map.computeIfAbsent(anchor, k -> new ArrayList<>()).add(b);
    }

    /** Блок с другим размером (настройка «размер» в расположении HUD). */
    private record Scaled(Block inner, float scale) implements Block {
        @Override
        public int w() {
            return Math.round(inner.w() * scale);
        }

        @Override
        public int h() {
            return Math.round(inner.h() * scale);
        }

        @Override
        public void draw(GuiGraphics g, int x, int y, boolean right) {
            HudScale.begin(g, x, y, scale);
            inner.draw(g, 0, 0, right);
            HudScale.end(g);
        }
    }

    // ------------------------------------------------------------ инфо-панель
    private static Block info(Minecraft mc, LocalPlayer p, Font font, int accent) {
        ArcConfig cfg = ArcConfig.get();
        List<InfoLine> lines = new ArrayList<>();
        BlockPos pos = p.blockPosition();
        Level level = mc.level;

        if (cfg.coordinates) lines.add(new InfoLine(Icons.PIN, Component.literal(pos.getX() + "  " + pos.getY() + "  " + pos.getZ()), Draw.TEXT));
        if (cfg.facing) {
            Direction d = p.getDirection();
            int heading = Math.round(Mth.wrapDegrees(p.getYRot() + 180F) + 180F) % 360;
            lines.add(new InfoLine(Icons.COMPASS, Component.translatable("archud.dir." + d.getName()).append(" " + heading + "°"), Draw.TEXT));
        }
        if (cfg.biome) {
            Optional<ResourceKey<Biome>> key = level.getBiome(pos).unwrapKey();
            if (key.isPresent()) {
                ResourceLocation id = key.get().location();
                lines.add(new InfoLine(Icons.LEAF, Component.translatableWithFallback("biome." + id.getNamespace() + "." + id.getPath(),
                        HudRenderer.pretty(id.getPath())), 0xFFA5D6A7));
            }
        }
        if (cfg.dimension) {
            ResourceLocation dim = level.dimension().location();
            Component name = Component.translatableWithFallback("archud.dim." + dim.getNamespace() + "." + dim.getPath(), HudRenderer.pretty(dim.getPath()));
            if (!dim.getNamespace().equals("minecraft") && cfg.modNames) {
                name = name.copy().append(Component.literal("  " + ArcHud.platform().modName(dim.getNamespace())).withStyle(s -> s.withColor(0x8C9AAB)));
            }
            lines.add(new InfoLine(Icons.GLOBE, name, Draw.brighten(accent, 0.3F)));
        }
        StringBuilder perf = new StringBuilder();
        if (cfg.fps) perf.append(HudRenderer.fps).append(" FPS");
        if (cfg.ping && mc.getConnection() != null && !mc.isLocalServer()) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(p.getUUID());
            if (info != null) {
                if (perf.length() > 0) perf.append("  ");
                perf.append(info.getLatency()).append(" ms");
            }
        }
        if (cfg.shaderInfo) {
            String pack = com.arc4io.archud.compat.RenderCompat.shaderPack();
            if (!pack.isEmpty()) {
                lines.add(new InfoLine(Icons.SUN, Component.translatable("archud.info.shader", pack), 0xFFFFD54F));
            }
        }
        if (cfg.rendererInfo && cfg.fps) perf.append("  ·  ").append(com.arc4io.archud.compat.RenderCompat.renderer());
        if (perf.length() > 0) lines.add(new InfoLine(Icons.CHIP, Component.literal(perf.toString()), cfg.fps && HudRenderer.fps < 30 ? 0xFFFF8A65 : Draw.TEXT));

        long dayTime = level.getDayTime();
        StringBuilder time = new StringBuilder();
        if (cfg.gameTime) {
            long t = (dayTime + 6000L) % 24000L;
            time.append(String.format("%02d:%02d", t / 1000L, (t % 1000L) * 60L / 1000L));
        }
        if (cfg.realTime) {
            if (time.length() > 0) time.append("  |  ");
            time.append(LocalTime.now().format(CLOCK));
        }
        if (time.length() > 0) lines.add(new InfoLine(Icons.CLOCK, Component.literal(time.toString()), Draw.TEXT));
        if (cfg.dayCounter || cfg.weather) {
            long tod = dayTime % 24000L;
            int icon = tod >= 13000L && tod < 23000L ? Icons.MOON : Icons.SUN;
            Component c = cfg.dayCounter ? Component.translatable("archud.info.day", dayTime / 24000L + 1) : Component.empty();
            if (cfg.weather && level.isThundering()) {
                icon = Icons.STORM;
                c = c.copy().append("  ").append(Component.translatable("archud.info.storm"));
            } else if (cfg.weather && level.isRaining()) {
                icon = Icons.RAIN;
                c = c.copy().append("  ").append(Component.translatable("archud.info.rain"));
            }
            if (!c.getString().isBlank()) lines.add(new InfoLine(icon, c, Draw.TEXT));
        }
        if (cfg.speed) lines.add(new InfoLine(Icons.SPEED, Component.literal(String.format("%.2f", HudRenderer.speedBps())).append(Component.translatable("archud.info.bps")), Draw.TEXT));
        if (cfg.lightLevel) {
            int block = level.getBrightness(LightLayer.BLOCK, pos);
            int sky = level.getBrightness(LightLayer.SKY, pos);
            int color = block <= 0 ? 0xFFFF7A7A : block < 8 ? 0xFFFFD54F : Draw.TEXT;
            lines.add(new InfoLine(Icons.LIGHT, Component.translatable("archud.info.light", block, sky), color));
        }
        for (com.arc4io.archud.client.extras.InfoExtras.Line l : com.arc4io.archud.client.extras.InfoExtras.infoLines(mc, p)) {
            lines.add(new InfoLine(l.icon(), l.text(), l.color()));
        }
        if (lines.isEmpty()) return null;
        int width = 0;
        for (InfoLine l : lines) width = Math.max(width, font.width(l.text()));
        final int bw = width + 18, bh = lines.size() * 11 + 4;
        return new Block() {
            public int w() { return bw; }

            public int h() { return bh; }

            public void draw(GuiGraphics g, int x, int y, boolean right) {
                Draw.accentPanel(g, x, y, bw, bh, accent);
                int ly = y + 2;
                for (InfoLine l : lines) {
                    Draw.icon(g, l.icon(), x + 3, ly);
                    int tx = right ? x + bw - 4 - font.width(l.text()) : x + 14;
                    g.drawString(font, l.text(), tx, ly + 1, l.color(), true);
                    ly += 11;
                }
            }
        };
    }

    // -------------------------------------------------------------- мини-карта
    private static Block minimap(Minecraft mc, LocalPlayer p, Font font, int accent) {
        ArcConfig cfg = ArcConfig.get();
        int size = cfg.minimapSize;
        float pt = mc.getFrameTime();
        return new Block() {
            public int w() { return size; }

            public int h() { return size + 11; }

            public void draw(GuiGraphics g, int x, int y, boolean right) {
                boolean circle = cfg.minimapCircle;
                if (!circle) Draw.panel(g, x, y, size, size, 0xFF080B10, Draw.alpha(accent, 0xA0));
                int ix = x + 2, iy = y + 2, is = size - 4;
                double wx = Mth.lerp(pt, p.xo, p.getX()), wz = Mth.lerp(pt, p.zo, p.getZ());
                float yaw = p.getViewYRot(pt);
                float rot = cfg.minimapRotate ? 180F - yaw : 0F;
                float zoom = (float) cfg.minimapZoom;
                float cx = ix + is / 2F, cy = iy + is / 2F, half = is / 2F;
                String dim = mc.level.dimension().location().toString();
                Runnable layer = () -> {
                    g.fill(ix, iy, ix + is, iy + is, 0xFF080B10);
                    com.mojang.blaze3d.systems.RenderSystem.enableBlend();
                    MapView.drawTerrain(g, cx, cy, wx, wz, zoom, rot, half);
                    if (cfg.minimapChunkGrid && zoom >= 1F) MapView.drawChunkGrid(g, cx, cy, wx, wz, zoom, rot, half);
                    if (cfg.minimapSlimeChunks) MapView.drawSlimeChunks(g, mc, cx, cy, wx, wz, zoom, rot, half);
                    if (cfg.minimapTrail) MapView.drawTrail(g, TrailManager.trail(dim), cx, cy, wx, wz, zoom, rot, half, accent);
                    if (cfg.minimapNightTint && mc.level.dimensionType().hasSkyLight()) {
                        long t = mc.level.getDayTime() % 24000L;
                        if (t > 12500 && t < 23500) g.fill(ix, iy, ix + is, iy + is, 0x70000818);
                    }
                    if (cfg.minimapEntities) MapView.drawEntities(g, mc, cx, cy, wx, wz, zoom, rot, half, cfg.minimapPlayerNames);
                    MapView.drawRemotePlayers(g, mc, dim, cx, cy, wx, wz, zoom, rot, half, cfg.minimapPlayerNames, cfg.showLoadZones);
                    MapView.drawNavigatorLine(g, mc, cx, cy, wx, wz, zoom, rot, half);
                };
                HudScale.scissor(g, ix, iy, ix + is, iy + is);
                if (com.arc4io.archud.client.perf.Perf.on("a_minimap_30fps")) MapView.masked(g, ix, iy, is, circle, layer, 33);
                else if (circle) MapView.circleBands(g, ix, iy, is, layer);
                else layer.run();
                if (cfg.minimapWaypoints) MapView.drawWaypoints(g, font, cx, cy, wx, wz, zoom, rot, circle ? half * 0.86F : half, true, false);
                MapView.drawPlayerArrow(g, cx, cy, cfg.minimapRotate ? 0F : yaw + 180F, 0xFFFFFFFF);
                g.disableScissor();
                if (circle) {
                    MapView.circleRing(g, cx, cy, half + 1, Draw.alpha(accent, 0xE0));
                } else {
                    int ac = Draw.alpha(accent, 0xFF);
                    g.fill(x, y, x + 8, y + 1, ac);
                    g.fill(x, y, x + 1, y + 8, ac);
                    g.fill(x + size - 8, y + size - 1, x + size, y + size, ac);
                    g.fill(x + size - 1, y + size - 8, x + size, y + size, ac);
                }
                // север
                float[] n = MapView.toScreen(0, -1, 0, 0, 1F, rot);
                float len = (float) Math.sqrt(n[0] * n[0] + n[1] * n[1]);
                float edge = half - 5;
                float k = circle ? edge : edge / Math.max(Math.abs(n[0] / len), Math.abs(n[1] / len));
                float nx = cx + n[0] / len * k, ny = cy + n[1] / len * k;
                g.fill((int) nx - 4, (int) ny - 4, (int) nx + 5, (int) ny + 5, 0xC0000000);
                Draw.smallCentered(g, font, Component.translatable("archud.compass.N").getString(), nx + 0.5F, ny - 2.5F, 0xFFFF5252, 0.7F);
                if (MapScanner.caveMode()) {
                    Draw.small(g, font, Component.translatable("archud.map.cave").getString() + " " + p.getBlockY(), ix + 3, iy + 3, 0xFFFFB74D, 0.5F);
                }
                Draw.small(g, font, "×" + (zoom >= 1 ? String.format("%.1f", zoom) : String.format("%.2f", zoom)), ix + is - 16, iy + is - 6, 0x90FFFFFF, 0.5F);
                // подпись
                Draw.panel(g, x, y + size + 1, size, 10, Draw.PANEL, Draw.alpha(accent, 0x50));
                String coords = p.getBlockX() + "  " + p.getBlockY() + "  " + p.getBlockZ();
                if (cfg.minimapFacing) {
                    String[] dirs = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
                    int idx = Math.floorMod(Math.round(yaw / 45F), 8);
                    coords += "  " + Component.translatable("archud.compass." + dirs[idx]).getString();
                }
                Draw.smallCentered(g, font, coords, x + size / 2F, y + size + 3, Draw.TEXT, 0.65F);
            }
        };
    }

    // -------------------------------------------------------------- навигатор
    private static Block navigator(Minecraft mc, LocalPlayer p, Font font, int accent) {
        Waypoint t = Navigator.target();
        if (t == null || !ArcConfig.get().navigator) return null;
        String dim = mc.level.dimension().location().toString();
        double[] target = Navigator.convertedTarget(dim);
        boolean other = !t.dimension.equals(dim);
        String title = t.name;
        String line2;
        double dist = -1;
        float arrow = 0F;
        if (target != null) {
            double dx = target[0] - p.getX(), dz = target[1] - p.getZ();
            dist = Math.sqrt(dx * dx + dz * dz);
            float deg = (float) Math.toDegrees(Math.atan2(dx, -dz));
            float heading = Mth.wrapDegrees(p.getYRot() + 180F);
            arrow = Mth.wrapDegrees(deg - heading);
            String dy = other ? "" : (t.y - p.getBlockY() > 2 ? "  ↑" + (t.y - p.getBlockY()) : t.y - p.getBlockY() < -2 ? "  ↓" + (p.getBlockY() - t.y) : "");
            double speed = HudRenderer.speedBps();
            String eta = speed > 1.5 ? "  ~" + fmtTime((long) (dist / speed)) : "";
            line2 = Math.round(dist) + " m" + dy + eta;
            if (other) line2 = Component.translatable("archud.nav.converted", (int) target[0], (int) target[1]).getString() + "  " + line2;
        } else {
            line2 = Component.translatable("archud.nav.other_dim", HudRenderer.pretty(t.dimension.substring(t.dimension.indexOf(':') + 1))).getString();
        }
        final String l2 = line2;
        final float arrowDeg = arrow;
        final boolean hasDir = target != null;
        int bw = Math.max(110, Math.max(font.width(title), Math.round(font.width(l2) * 0.7F)) + 34);
        return new Block() {
            public int w() { return bw; }

            public int h() { return 26; }

            public void draw(GuiGraphics g, int x, int y, boolean right) {
                Draw.panel(g, x, y, bw, 26, Draw.PANEL, Draw.alpha(t.color, 0xC0));
                g.fill(x + 1, y + 1, x + 25, y + 25, 0x40000000);
                if (hasDir) MapView.drawPlayerArrow(g, x + 13, y + 13, arrowDeg, 0xFF000000 | t.color);
                else Draw.smallCentered(g, font, "⇄", x + 13, y + 9, 0xFF000000 | t.color, 1F);
                g.drawString(font, title, x + 28, y + 4, 0xFF000000 | t.color, true);
                Draw.small(g, font, l2, x + 28, y + 15, Draw.TEXT, 0.7F);
                double prog = Navigator.progress(p);
                g.fill(x + 1, y + 24, x + 1 + (int) ((bw - 2) * prog), y + 25, 0xFF000000 | t.color);
            }
        };
    }

    private static String fmtTime(long sec) {
        if (sec >= 3600) return (sec / 3600) + ":" + String.format("%02d", (sec % 3600) / 60) + ":" + String.format("%02d", sec % 60);
        return (sec / 60) + ":" + String.format("%02d", sec % 60);
    }

    // ----------------------------------------------------------------- компас
    private static Block compass(LocalPlayer p, Font font, int accent) {
        return new Block() {
            public int w() { return 182; }

            public int h() { return 13; }

            public void draw(GuiGraphics g, int x, int y, boolean right) {
                int cw = 182, cx = x + cw / 2;
                Draw.accentPanel(g, x, y, cw, 13, accent);
                float heading = Mth.wrapDegrees(p.getYRot() + 180F);
                HudScale.scissor(g, x + 2, y, x + cw - 2, y + 13);
                String[] names = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
                for (int deg = 0; deg < 360; deg += 15) {
                    float diff = Mth.wrapDegrees(deg - heading);
                    if (Math.abs(diff) > 95) continue;
                    int px = cx + Math.round(diff * (cw / 2F) / 90F);
                    if (deg % 45 == 0) {
                        String n = Component.translatable("archud.compass." + names[deg / 45]).getString();
                        int col = deg == 0 ? 0xFFFF5252 : deg % 90 == 0 ? 0xFFFFFFFF : 0xFFB0BEC5;
                        float sc = deg % 90 == 0 ? 0.85F : 0.6F;
                        Draw.smallCentered(g, font, n, px, y + (deg % 90 == 0 ? 3 : 4), col, sc);
                    } else {
                        g.fill(px, y + 9, px + 1, y + 12, 0x80FFFFFF);
                    }
                }
                Waypoint focus = null;
                float focusDiff = 999F;
                if (ArcConfig.get().compassWaypoints) {
                    for (Waypoint wp : WaypointManager.inDimension()) {
                        double dx = wp.x + 0.5 - p.getX(), dz = wp.z + 0.5 - p.getZ();
                        float deg = (float) Math.toDegrees(Math.atan2(dx, -dz));
                        float diff = Mth.wrapDegrees(deg - heading);
                        if (Math.abs(diff) > 90) continue;
                        int px = cx + Math.round(diff * (cw / 2F) / 90F);
                        MapView.diamond(g, px, y + 7, 3, wp.color);
                        if (Math.abs(diff) < focusDiff) {
                            focusDiff = Math.abs(diff);
                            focus = wp;
                        }
                    }
                }
                g.disableScissor();
                if (focus != null && focusDiff < 8F) {
                    String label = focus.name + "  " + Math.round(focus.distanceTo(p.getX(), p.getY(), p.getZ())) + " m";
                    Draw.smallCentered(g, font, label, cx, y + 14.5F, focus.color, 0.6F);
                }
                g.fill(cx, y + 1, cx + 1, y + 12, Draw.alpha(accent, 0xFF));
                g.fill(cx - 1, y + 11, cx + 2, y + 13, Draw.alpha(accent, 0xFF));
            }
        };
    }

    // ------------------------------------------------------------ цель / блок
    private static Block target(Minecraft mc, Font font, int accent) {
        ArcConfig cfg = ArcConfig.get();
        Entity target = mc.crosshairPickEntity;
        if (cfg.targetInfo && target instanceof LivingEntity living && !living.isInvisible()) {
            Component name = living.getDisplayName();
            String mod = BuiltInRegistries.ENTITY_TYPE.getKey(living.getType()).getNamespace();
            String mn = cfg.modNames && !mod.equals("minecraft") ? ArcHud.platform().modName(mod) : "";
            float hp = living.getHealth(), max = Math.max(1, living.getMaxHealth());
            String extra = com.arc4io.archud.client.extras.InfoExtras.mobDetail(living);
            int pw = Math.max(Math.max(120, font.width(name) + 24 + Math.round(font.width(mn) * 0.5F)), Math.round(font.width(extra) * 0.6F) + 10);
            int ph = extra.isEmpty() ? 26 : 34;
            return new Block() {
                public int w() { return pw; }

                public int h() { return ph; }

                public void draw(GuiGraphics g, int x, int y, boolean right) {
                    Draw.accentPanel(g, x, y, pw, ph, accent);
                    if (!extra.isEmpty()) Draw.small(g, font, extra, x + 5, y + 26, 0xFFFFE082, 0.6F);
                    Draw.icon(g, Icons.SKULL, x + 3, y + 3);
                    g.drawString(font, name, x + 14, y + 3, Draw.TEXT, true);
                    Draw.bar(g, x + 4, y + 14, pw - 8, 5, hp / max, 0xFFFF6B6B, 0xFFB71C1C);
                    Draw.smallCentered(g, font, HudRenderer.fmt(hp) + " / " + HudRenderer.fmt(max)
                            + (living.getArmorValue() > 0 ? "   ◈ " + living.getArmorValue() : ""), x + pw / 2F, y + 20, 0xFFE0E0E0, 0.5F);
                    if (!mn.isEmpty()) Draw.small(g, font, mn, x + pw - 4 - font.width(mn) * 0.5F, y + 4, 0xFF7FA7C9, 0.5F);
                }
            };
        }
        if (cfg.blockInfo && mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK && mc.level != null) {
            BlockState state = mc.level.getBlockState(bhr.getBlockPos());
            if (state.isAir()) return null;
            Component name = state.getBlock().getName();
            String mod = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace();
            String mn = cfg.modNames ? ArcHud.platform().modName(mod) : "";
            String detail = com.arc4io.archud.client.extras.InfoExtras.blockDetail(state);
            int pw = Math.max(Math.max(font.width(name), Math.round(font.width(mn) * 0.6F)), Math.round(font.width(detail) * 0.6F)) + 28;
            int bh = detail.isEmpty() ? 20 : 27;
            ItemStack icon = new ItemStack(state.getBlock().asItem());
            return new Block() {
                public int w() { return pw; }

                public int h() { return bh; }

                public void draw(GuiGraphics g, int x, int y, boolean right) {
                    Draw.accentPanel(g, x, y, pw, bh, accent);
                    if (!detail.isEmpty()) Draw.small(g, font, detail, x + 21, y + 19.5F, 0xFFFFE082, 0.6F);
                    if (!icon.isEmpty()) g.renderItem(icon, x + 2, y + 2);
                    g.drawString(font, name, x + 21, y + 3, Draw.TEXT, true);
                    if (!mn.isEmpty()) Draw.small(g, font, mn, x + 21, y + 12.5F, 0xFF7FA7C9, 0.6F);
                }
            };
        }
        return null;
    }

    // ----------------------------------------------------------------- эффекты
    private static Block effects(Minecraft mc, LocalPlayer p, Font font, int accent) {
        ArcConfig cfg = ArcConfig.get();
        Collection<MobEffectInstance> active = p.getActiveEffects();
        HudRenderer.EFFECT_MAX.keySet().removeIf(m -> !p.hasEffect(m));
        if (active.isEmpty()) return null;
        List<MobEffectInstance> list = new ArrayList<>();
        for (MobEffectInstance e : active) if (e.showIcon()) list.add(e);
        if (list.isEmpty()) return null;
        list.sort((a, b) -> Boolean.compare(!a.getEffect().isBeneficial(), !b.getEffect().isBeneficial()));

        boolean compact = list.size() > 7;
        List<Component> names = new ArrayList<>();
        List<String> times = new ArrayList<>();
        int entryW = 0;
        for (MobEffectInstance e : list) {
            Component name = e.getEffect().getDisplayName().copy();
            if (e.getAmplifier() > 0) name = name.copy().append(" ").append(Component.translatable("enchantment.level." + (e.getAmplifier() + 1)));
            String t = e.isInfiniteDuration() ? "∞" : MobEffectUtil.formatDuration(e, 1.0F).getString();
            names.add(name);
            times.add(t);
            entryW = Math.max(entryW, Math.max(font.width(name), font.width(t)) + 28);
        }
        int cols = compact ? Math.min(6, list.size()) : 1;
        int rows = compact ? (list.size() + 5) / 6 : list.size();
        final int bw = compact ? cols * 24 - 2 : entryW;
        final int bh = rows * 24 - 2;
        return new Block() {
            public int w() { return bw; }

            public int h() { return bh; }

            public void draw(GuiGraphics g, int x, int y, boolean right) {
                for (int i = 0; i < list.size(); i++) {
                    MobEffectInstance e = list.get(i);
                    MobEffect effect = e.getEffect();
                    int dur = e.getDuration();
                    int maxDur = HudRenderer.EFFECT_MAX.merge(effect, dur, Math::max);
                    boolean blinking = !e.isInfiniteDuration() && dur < 200 && (dur / 5) % 2 == 0;
                    int color = 0xFF000000 | effect.getColor();
                    TextureAtlasSprite sprite = mc.getMobEffectTextures().get(effect);
                    float f = Mth.clamp(dur / (float) Math.max(1, maxDur), 0, 1);
                    if (compact) {
                        int ex = x + (i % 6) * 24, ey = y + (i / 6) * 24;
                        Draw.panel(g, ex, ey, 22, 22, Draw.PANEL, Draw.alpha(color, 0xA0));
                        if (!blinking) g.blit(ex + 2, ey + 2, 0, 18, 18, sprite);
                        if (cfg.effectTimers && !e.isInfiniteDuration()) g.fill(ex + 2, ey + 20, ex + 2 + Math.round(18 * f), ey + 21, color);
                        continue;
                    }
                    int ey = y + i * 24;
                    Draw.panel(g, x, ey, bw, 22, Draw.PANEL, Draw.alpha(color, 0x90));
                    int stripe = right ? x + bw - 2 : x;
                    g.fill(stripe, ey + 2, stripe + 2, ey + 20, effect.isBeneficial() ? Draw.alpha(accent, 0xFF) : 0xFFFF5252);
                    if (!blinking) g.blit(x + 4, ey + 2, 0, 18, 18, sprite);
                    g.drawString(font, names.get(i), x + 24, ey + 3, effect.isBeneficial() ? Draw.TEXT : 0xFFFF8A80, true);
                    if (cfg.effectTimers) {
                        Draw.small(g, font, times.get(i), x + 24, ey + 13, Draw.TEXT_DIM, 0.75F);
                        if (!e.isInfiniteDuration()) {
                            g.fill(x + 24, ey + 19, x + bw - 3, ey + 20, 0x60000000);
                            g.fill(x + 24, ey + 19, x + 24 + Math.round((bw - 27) * f), ey + 20, color);
                        }
                    }
                }
            }
        };
    }

    // ------------------------------------------------- панели аддонов (Legends и др.)
    private static Block panel(ArcHudApi.Panel panel, Font font, int accent) {
        List<ArcHudApi.PanelRow> rows;
        try {
            rows = panel.rows().get();
        } catch (Exception e) {
            rows = null;
        }
        if (rows == null || rows.isEmpty()) return null;
        final List<ArcHudApi.PanelRow> list = rows;
        int maxW = Math.round(font.width(panel.title()) * 0.75F) + 12;
        for (ArcHudApi.PanelRow r : list) {
            maxW = Math.max(maxW, font.width(r.text()) + (r.right().isEmpty() ? 0 : font.width(r.right()) + 10) + 14);
        }
        final int bw = Math.min(220, maxW), bh = 12 + list.size() * 14 + 2;
        return new Block() {
            public int w() { return bw; }

            public int h() { return bh; }

            public void draw(GuiGraphics g, int x, int y, boolean right) {
                Draw.panel(g, x, y, bw, bh, Draw.PANEL, Draw.alpha(panel.color(), 0x70));
                g.fill(x + 2, y, x + bw - 2, y + 1, Draw.alpha(panel.color(), 0xFF));
                Draw.small(g, font, panel.title().getString().toUpperCase(), x + 5, y + 3, Draw.alpha(panel.color(), 0xFF), 0.75F);
                int ry = y + 12;
                for (ArcHudApi.PanelRow r : list) {
                    if (r.highlight()) g.fill(x + 2, ry, x + bw - 2, ry + 13, Draw.alpha(r.color(), 0x30));
                    g.fill(x + 2, ry + 1, x + 4, ry + 12, Draw.alpha(r.color(), 0xFF));
                    String text = r.text();
                    int rw = r.right().isEmpty() ? 0 : font.width(r.right()) + 8;
                    if (font.width(text) > bw - 12 - rw) text = font.plainSubstrByWidth(text, bw - 16 - rw) + "…";
                    g.drawString(font, text, x + 7, ry + 2, r.highlight() ? 0xFFFFFFFF : Draw.TEXT, true);
                    if (!r.right().isEmpty()) g.drawString(font, r.right(), x + bw - 5 - font.width(r.right()), ry + 2, r.color(), true);
                    if (r.progress() >= 0) {
                        g.fill(x + 7, ry + 11, x + bw - 5, ry + 12, 0x50000000);
                        g.fill(x + 7, ry + 11, x + 7 + Math.round((bw - 12) * Mth.clamp(r.progress(), 0, 1)), ry + 12, r.color());
                    }
                    ry += 14;
                }
            }
        };
    }

    // -------------------------------------------------------------- снаряжение
    private static Block gear(LocalPlayer p, Font font, int accent) {
        ArcConfig cfg = ArcConfig.get();
        List<ItemStack> list = new ArrayList<>();
        if (cfg.armorDurability) {
            for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                ItemStack s = p.getItemBySlot(slot);
                if (s.isDamageableItem()) list.add(s);
            }
        }
        if (cfg.heldDurability) {
            if (p.getMainHandItem().isDamageableItem()) list.add(p.getMainHandItem());
            if (p.getOffhandItem().isDamageableItem()) list.add(p.getOffhandItem());
        }
        if (list.isEmpty()) return null;
        final int bh = list.size() * 20 - 1;
        return new Block() {
            public int w() { return 54; }

            public int h() { return bh; }

            public void draw(GuiGraphics g, int x, int y, boolean right) {
                for (ItemStack s : list) {
                    int max = Math.max(1, s.getMaxDamage());
                    float frac = 1F - s.getDamageValue() / (float) max;
                    boolean low = frac < 0.1F;
                    int bg = low && cfg.lowDurabilityWarning ? Draw.mix(Draw.PANEL, 0xC0FF2020, Draw.pulse(700)) : Draw.PANEL;
                    Draw.panel(g, x, y, 54, 19, bg, Draw.alpha(accent, 0x40));
                    g.renderItem(s, x + 2, y + 1);
                    int color = Draw.mix(0xFFFF4040, 0xFF6BFF7A, frac);
                    Draw.small(g, font, Math.round(frac * 100) + "%", x + 20, y + 3, color, 0.75F);
                    Draw.small(g, font, Integer.toString(max - s.getDamageValue()), x + 20, y + 10, Draw.TEXT_DIM, 0.5F);
                    g.fill(x + 36, y + 14, x + 52, y + 16, 0xC0000000);
                    g.fill(x + 36, y + 14, x + 36 + Math.round(16 * frac), y + 16, color);
                    y += 20;
                }
            }
        };
    }

    // ---------------------------------------------------------------- подбор
    private static Block pickups(Font font, int accent, long now) {
        List<HudRenderer.Pickup> list = HudRenderer.PICKUPS;
        if (list.isEmpty()) return null;
        List<String> texts = new ArrayList<>();
        int maxW = 0;
        for (HudRenderer.Pickup pk : list) {
            String txt = "+" + pk.count + " " + pk.stack.getHoverName().getString();
            if (font.width(txt) > 110) txt = font.plainSubstrByWidth(txt, 104) + "…";
            texts.add(txt);
            maxW = Math.max(maxW, font.width(txt) + 26);
        }
        final int bw = maxW, bh = list.size() * 20 - 2;
        return new Block() {
            public int w() { return bw; }

            public int h() { return bh; }

            public void draw(GuiGraphics g, int x, int y, boolean right) {
                for (int i = list.size() - 1; i >= 0; i--) {
                    HudRenderer.Pickup pk = list.get(i);
                    String txt = texts.get(i);
                    float age = (now - pk.time) / 3500F;
                    float slide = Draw.easeOut(Math.min(1F, (now - pk.time) / 220F));
                    float fade = age > 0.8F ? 1F - (age - 0.8F) / 0.2F : 1F;
                    int pw = font.width(txt) + 26;
                    int px = right ? x + bw - pw : x;
                    px += Math.round((1F - slide) * (right ? 40F : -40F));
                    int a = Math.max(8, (int) (255 * fade));
                    Draw.panel(g, px, y, pw, 18, Draw.scaleAlpha(Draw.PANEL, fade), Draw.alpha(accent, (int) (0x70 * fade)));
                    g.fill(right ? px + pw - 2 : px, y + 2, right ? px + pw : px + 2, y + 16, Draw.alpha(accent, a));
                    if (fade > 0.3F) g.renderItem(pk.stack, px + 4, y + 1);
                    g.drawString(font, txt, px + 22, y + 5, Draw.alpha(0xEAF2FA, a), true);
                    y += 20;
                }
            }
        };
    }

    // ------------------------------------------------------------- статус
    private static Block status(LocalPlayer p, Font font, int accent) {
        List<String> tags = new ArrayList<>();
        if (p.isFallFlying()) tags.add(Component.translatable("archud.status.glide").getString());
        else if (p.getAbilities().flying) tags.add(Component.translatable("archud.status.fly").getString());
        if (p.isSwimming()) tags.add(Component.translatable("archud.status.swim").getString());
        else if (p.isSprinting()) tags.add(Component.translatable("archud.status.sprint").getString());
        if (p.isShiftKeyDown()) tags.add(Component.translatable("archud.status.sneak").getString());
        if (tags.isEmpty()) return null;
        int maxW = 0;
        for (String t : tags) maxW = Math.max(maxW, Math.round(font.width(t) * 0.75F) + 8);
        final int bw = maxW, bh = tags.size() * 13 - 2;
        return new Block() {
            public int w() { return bw; }

            public int h() { return bh; }

            public void draw(GuiGraphics g, int x, int y, boolean right) {
                for (String t : tags) {
                    int tw = Math.round(font.width(t) * 0.75F) + 8;
                    int tx = right ? x + bw - tw : x;
                    Draw.panel(g, tx, y, tw, 11, Draw.PANEL, Draw.alpha(accent, 0x80));
                    Draw.small(g, font, t, tx + 4, y + 3, Draw.brighten(accent, 0.4F), 0.75F);
                    y += 13;
                }
            }
        };
    }

    // ------------------------------------------------------- боссбары (отступ)
    static int bossCount(Minecraft mc) {
        try {
            Object overlay = mc.gui.getBossOverlay();
            if (!bossFieldSearched) {
                bossFieldSearched = true;
                for (Field f : overlay.getClass().getDeclaredFields()) {
                    if (Map.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        bossMapField = f;
                        break;
                    }
                }
            }
            if (bossMapField != null) return Math.min(4, ((Map<?, ?>) bossMapField.get(overlay)).size());
        } catch (Exception ignored) {
        }
        return 0;
    }
}
