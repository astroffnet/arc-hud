package com.arc4io.archud.client.hud;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.config.ArcConfig;
import com.mojang.blaze3d.platform.GlUtil;
import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Своё меню F3: вместо двух столбцов белого текста — карточки «Позиция», «Мир», «Производительность»
 * (с графиком времени кадра и полосой памяти), «Система» и «Цель» (блок / жидкость / существо под
 * прицелом со свойствами и тегами). Графики Shift+F3 / Alt+F3 — ванильные.
 */
public final class ArcDebugScreen {
    private static final long[] FRAMES = new long[120];
    private static int frameIdx;
    private static long lastNs;

    record Card(String title, List<String[]> rows, int extraH, int kind) {}

    private ArcDebugScreen() {}

    public static boolean enabled() {
        Minecraft mc = Minecraft.getInstance();
        return ArcConfig.get().customF3 && !mc.options.renderDebugCharts && !mc.options.renderFpsChart;
    }

    /** Когда Arc HUD последний раз разложил карточки F3 вместе со своими блоками (тогда отдельно их не рисуем). */
    private static long hudLaidOutNs;

    /** F3 открыто и показывается наше меню. */
    public static boolean active() {
        return Minecraft.getInstance().options.renderDebug && enabled();
    }

    /**
     * Вызывается вместо ванильного текста F3. Обычно карточки уже нарисовал HUD (они стоят в его стопках
     * у левого и правого края, ниже мини-карты и инфо-панели), здесь только замер кадра. Если HUD
     * не рисуется (выключен, наблюдатель без HUD и т.п.) — рисуем карточки сами в углах.
     */
    public static void render(GuiGraphics g) {
        long now = Util.getNanos();
        if (lastNs != 0) {
            FRAMES[frameIdx] = now - lastNs;
            frameIdx = (frameIdx + 1) % FRAMES.length;
        }
        lastNs = now;
        if (now - hudLaidOutNs < 250_000_000L) return;
        Minecraft mc = Minecraft.getInstance();
        List<HudBlocks.Block> left = new ArrayList<>(), right = new ArrayList<>();
        build(mc, mc.font, Theme.accent(), g.guiWidth(), left, right);
        int y = 4;
        for (HudBlocks.Block b : left) {
            if (y + b.h() > g.guiHeight() - 2) break;
            b.draw(g, 4, y, false);
            y += b.h() + 4;
        }
        y = 4;
        for (HudBlocks.Block b : right) {
            if (y + b.h() > g.guiHeight() - 2) break;
            b.draw(g, g.guiWidth() - 4 - b.w(), y, true);
            y += b.h() + 4;
        }
    }

    static void markLaidOut() {
        hudLaidOutNs = Util.getNanos();
    }

    /** Карточка F3 как блок HUD — раскладывается вместе с остальными, ничего не перекрывая. */
    record CardBlock(Card card, int w, float s, int accent) implements HudBlocks.Block {
        @Override
        public int h() {
            return cardH(card, s);
        }

        @Override
        public void draw(GuiGraphics g, int x, int y, boolean right) {
            drawCard(g, Minecraft.getInstance().font, card, x, y, w, s, accent);
        }
    }

    /** Собирает карточки: левый столбец («Позиция», «Мир») и правый («Производительность», «Система», «Цель»). */
    static void build(Minecraft mc, Font font, int accent, int screenW, List<HudBlocks.Block> outLeft, List<HudBlocks.Block> outRight) {
        Entity cam = mc.getCameraEntity();
        ClientLevel level = mc.level;
        if (cam == null || level == null) return;
        boolean reduced = mc.showOnlyReducedInfo();

        List<Card> left = new ArrayList<>(), right = new ArrayList<>();
        BlockPos pos = cam.blockPosition();

        // ---- позиция
        List<String[]> p = new ArrayList<>();
        if (!reduced) {
            p.add(row("XYZ", String.format(Locale.ROOT, "%.3f / %.3f / %.3f", cam.getX(), cam.getY(), cam.getZ())));
            p.add(row("Block", pos.getX() + " " + pos.getY() + " " + pos.getZ() + "   [" + (pos.getX() & 15) + " " + (pos.getY() & 15) + " " + (pos.getZ() & 15) + "]"));
            p.add(row("Chunk", (pos.getX() >> 4) + " " + (pos.getY() >> 4) + " " + (pos.getZ() >> 4)
                    + "   r." + (pos.getX() >> 9) + "." + (pos.getZ() >> 9)));
        }
        Direction d = cam.getDirection();
        String axis = switch (d) {
            case NORTH -> "-Z";
            case SOUTH -> "+Z";
            case WEST -> "-X";
            case EAST -> "+X";
            default -> "";
        };
        p.add(row("Facing", d.getName() + " (" + axis + ")   " + String.format(Locale.ROOT, "%.1f / %.1f", Mth.wrapDegrees(cam.getYRot()), cam.getXRot())));
        left.add(new Card("Position", p, 0, 0));

        // ---- мир
        List<String[]> w = new ArrayList<>();
        w.add(row("Dimension", level.dimension().location().toString()));
        if (!reduced) {
            Optional<ResourceKey<Biome>> b = level.getBiome(pos).unwrapKey();
            w.add(row("Biome", b.map(k -> k.location().toString()).orElse("?")));
            int sky = level.getBrightness(LightLayer.SKY, pos), block = level.getBrightness(LightLayer.BLOCK, pos);
            w.add(row("Light", level.getChunkSource().getLightEngine().getRawBrightness(pos, 0) + "   sky " + sky + " · block " + block));
            var server = mc.getSingleplayerServer();
            if (server != null) {
                var sl = server.getLevel(level.dimension());
                if (sl != null) {
                    var diff = sl.getCurrentDifficultyAt(pos);
                    w.add(row("Difficulty", String.format(Locale.ROOT, "%.2f / %.2f", diff.getEffectiveDifficulty(), diff.getSpecialMultiplier())));
                }
            }
        }
        long t = level.getDayTime();
        w.add(row("Day / time", (t / 24000L + 1) + "   " + String.format(Locale.ROOT, "%02d:%02d", ((t + 6000) % 24000) / 1000, ((t + 6000) % 1000) * 60 / 1000)));
        w.add(row("Entities", mc.levelRenderer.getEntityStatistics().replace("E: ", "")));
        w.add(row("Chunks", mc.levelRenderer.getChunkStatistics().replace("C: ", "")));
        w.add(row("Particles", mc.particleEngine.countParticles()));
        left.add(new Card("World", w, 0, 0));

        // ---- производительность
        List<String[]> perf = new ArrayList<>();
        long sum = 0, worst = 0;
        int n = 0;
        for (long f : FRAMES) {
            if (f <= 0) continue;
            sum += f;
            worst = Math.max(worst, f);
            n++;
        }
        perf.add(row("FPS", mc.getFps() + "   " + (n == 0 ? "" : String.format(Locale.ROOT, "%.1f ms avg · %.1f max", sum / (double) n / 1e6, worst / 1e6))));
        var server = mc.getSingleplayerServer();
        if (server != null) {
            float mspt = server.getAverageTickTime();
            perf.add(row("Server", String.format(Locale.ROOT, "%.1f mspt · %.1f TPS", mspt, Math.min(20.0, 1000.0 / Math.max(mspt, 1e-3)))));
        } else if (mc.player != null && mc.player.getServerBrand() != null) {
            perf.add(row("Server", mc.player.getServerBrand()));
        }
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory(), used = rt.totalMemory() - rt.freeMemory();
        perf.add(row("Memory", (used >> 20) + " / " + (max >> 20) + " MB  (" + (used * 100 / Math.max(1, max)) + "%)"));
        right.add(new Card("Performance", perf, 30, 1));

        // ---- система
        List<String[]> sys = new ArrayList<>();
        sys.add(row("Game", "Minecraft " + SharedConstants.getCurrentVersion().getName() + " · " + ArcHud.platform().loaderName()));
        sys.add(row("Java", System.getProperty("java.version") + (mc.is64Bit() ? " 64bit" : " 32bit")));
        sys.add(row("CPU", GlUtil.getCpuInfo()));
        sys.add(row("GPU", GlUtil.getRenderer()));
        sys.add(row("OpenGL", GlUtil.getOpenGLVersion()));
        sys.add(row("Display", mc.getWindow().getWidth() + "×" + mc.getWindow().getHeight() + "   " + GlUtil.getVendor()));
        right.add(new Card("System", sys, 0, 0));

        // ---- цель
        if (!reduced) {
            HitResult hit = mc.hitResult;
            List<String[]> tgt = new ArrayList<>();
            ItemStack icon = ItemStack.EMPTY;
            if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
                BlockPos bp = bh.getBlockPos();
                BlockState st = level.getBlockState(bp);
                icon = new ItemStack(st.getBlock().asItem());
                tgt.add(row("Block", String.valueOf(BuiltInRegistries.BLOCK.getKey(st.getBlock()))));
                tgt.add(row("At", bp.getX() + " " + bp.getY() + " " + bp.getZ() + "   " + bh.getDirection().getName()));
                int k = 0;
                for (Map.Entry<Property<?>, Comparable<?>> e : st.getValues().entrySet()) {
                    if (k++ >= 6) break;
                    tgt.add(row("  " + e.getKey().getName(), String.valueOf(e.getValue())));
                }
                List<String> tags = new ArrayList<>();
                st.getTags().limit(4).forEach(tk -> tags.add("#" + tk.location()));
                if (!tags.isEmpty()) tgt.add(row("Tags", String.join(" ", tags)));
                FluidState fl = level.getFluidState(bp);
                if (!fl.isEmpty()) tgt.add(row("Fluid", String.valueOf(BuiltInRegistries.FLUID.getKey(fl.getType()))));
            } else if (hit instanceof EntityHitResult eh) {
                Entity e = eh.getEntity();
                tgt.add(row("Entity", String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()))));
                tgt.add(row("Name", e.getDisplayName().getString()));
                tgt.add(row("At", String.format(Locale.ROOT, "%.1f %.1f %.1f", e.getX(), e.getY(), e.getZ())));
                tgt.add(row("UUID", e.getStringUUID()));
            }
            if (!tgt.isEmpty()) right.add(new Card("Target", tgt, 0, icon.isEmpty() ? 2 : 3));
            if (!icon.isEmpty()) targetIcon = icon;
        }

        float s = 0.75F;
        int lw = Math.max(120, Math.min(screenW / 2 - 100, 230));
        for (Card c : left) outLeft.add(new CardBlock(c, lw, s, accent));
        for (Card c : right) outRight.add(new CardBlock(c, lw, s, accent));
    }

    private static ItemStack targetIcon = ItemStack.EMPTY;

    private static String[] row(String k, String v) {
        return new String[]{k, v};
    }

    private static int cardH(Card c, float s) {
        int lineH = Math.round(10 * s) + 1;
        return 14 + c.rows().size() * lineH + c.extraH() + 4;
    }

    private static void drawCard(GuiGraphics g, Font font, Card c, int x, int y, int w, float s, int accent) {
        {
            int lineH = Math.round(10 * s) + 1;
            int h = cardH(c, s);
            Draw.panel(g, x, y, w, h, 0xC00A0E13, Draw.alpha(accent, 0x70));
            g.fill(x + 2, y, x + w - 2, y + 1, accent);
            Draw.small(g, font, c.title().toUpperCase(Locale.ROOT), x + 5, y + 4, Draw.alpha(accent, 0xFF), 0.7F);
            int ty = y + 14;
            if (c.kind() == 3) {
                g.pose().pushPose();
                g.pose().translate(x + w - 22, y + 2, 0);
                g.pose().scale(0.75F, 0.75F, 1F);
                g.renderItem(targetIcon, 0, 0);
                g.pose().popPose();
            }
            int keyW = (int) (64 * s / 0.75F);
            for (String[] r : c.rows()) {
                Draw.small(g, font, r[0], x + 5, ty, Draw.TEXT_DIM, s);
                String v = r[1];
                int maxV = (int) ((w - 10 - keyW) / s);
                if (font.width(v) > maxV) v = font.plainSubstrByWidth(v, maxV - font.width("…")) + "…";
                Draw.small(g, font, v, x + 5 + keyW, ty, Draw.TEXT, s);
                ty += lineH;
            }
            if (c.kind() == 1) {
                // память и график кадров
                Runtime rt = Runtime.getRuntime();
                float mem = (rt.totalMemory() - rt.freeMemory()) / (float) rt.maxMemory();
                g.fill(x + 5, ty + 1, x + w - 5, ty + 4, 0x40FFFFFF);
                g.fill(x + 5, ty + 1, x + 5 + Math.round((w - 10) * mem), ty + 4, mem > 0.85F ? 0xFFFF6E6E : mem > 0.65F ? 0xFFFFD54F : 0xFF9CFF5A);
                int gy = ty + 8, gh = 18, gx = x + 5, gw = w - 10;
                g.fill(gx, gy, gx + gw, gy + gh, 0x30000000);
                int bars = Math.min(FRAMES.length, gw / 2);
                for (int i = 0; i < bars; i++) {
                    long f = FRAMES[Math.floorMod(frameIdx - bars + i, FRAMES.length)];
                    if (f <= 0) continue;
                    float ms = f / 1e6F;
                    int bh = Math.min(gh, Math.round(ms / 50F * gh)); // 50 мс = вся высота
                    int col = ms > 33 ? 0xFFFF6E6E : ms > 16.7F ? 0xFFFFD54F : 0xFF9CFF5A;
                    g.fill(gx + i * 2, gy + gh - bh, gx + i * 2 + 1, gy + gh, col);
                }
                // линия 60 FPS
                int l60 = gy + gh - Math.round(16.7F / 50F * gh);
                g.fill(gx, l60, gx + gw, l60 + 1, 0x40FFFFFF);
            }
        }
    }
}
