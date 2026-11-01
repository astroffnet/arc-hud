package com.arc4io.archud.client.screen;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.config.ArcConfig;
import com.arc4io.archud.mixin.MinecraftTargetAccessor;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

/**
 * Свой вывод окон и чата: окно рисуется не каждый кадр, а в отдельный буфер — заново только когда в нём что-то
 * меняется (движение мыши, клик, клавиши, прокрутка, новое сообщение, смена окна) и не чаще заданной частоты;
 * в остальные кадры на экран кладётся готовая картинка одним прямоугольником. Мир за окном рисуется как обычно,
 * поэтому открытые инвентарь, чат, рецепты и меню почти не отнимают FPS.
 * <p>
 * Прозрачность в буфере собирается правильно (альфа накапливается — режим смешивания на время записи
 * подменяется в GlStateManager), картинка накладывается как «premultiplied alpha».
 */
public final class GuiCache {
    /** Слой кэша: окно или чат. */
    public static final class Layer {
        RenderTarget target;
        long last, ownerSince;
        Object owner;
        boolean valid;
        int w, h;
        double scale, mx = Double.NaN, my = Double.NaN;
    }

    public static final Layer SCREEN = new Layer(), CHAT = new Layer();
    /** Идёт запись в буфер кэша (для подмены смешивания и защиты от повторного входа). */
    public static boolean capturing;
    private static long lastInput;
    private static boolean failed;

    private GuiCache() {}

    /** Ввод (мышь, клавиатура, колесо): ближайшие кадры окно обновляется с «активной» частотой. */
    public static void poke() {
        lastInput = Util.getMillis();
    }

    public static void invalidate(Layer l) {
        l.valid = false;
    }

    private static boolean enabled() {
        if (failed || !ArcConfig.get().guiCache || capturing) return false;
        Minecraft mc = Minecraft.getInstance();
        // без мира (главное меню с панорамой, загрузка) экономить нечего, а анимации там важнее
        return mc.level != null && !com.arc4io.archud.client.gfx.GlContext.vulkan();
    }

    /** Окна, которые рисуются сами по себе каждый кадр (карта с перетаскиванием и т. п.) — без кэша. */
    private static boolean cacheable(Screen s) {
        String n = s.getClass().getName();
        if (n.startsWith("com.arc4io.archud.client.map.")) return false;
        if (n.contains("Crash") || n.contains("Loading") || n.contains("ReceivingLevel") || n.contains("Progress")) return false;
        return true;
    }

    /** Окно: true — нарисовано из кэша (исходную отрисовку надо пропустить). */
    public static boolean screen(Screen s, GuiGraphics g, int mx, int my, float pt) {
        if (!enabled() || !cacheable(s)) return false;
        return draw(SCREEN, s, g, mx, my, () -> s.renderWithTooltip(g, mx, my, pt));
    }

    /** Чат (пока открыт ввод сообщения): true — нарисован из кэша. */
    public static boolean chat(Object chat, GuiGraphics g, int mx, int my, Runnable paint) {
        if (!enabled() || !(Minecraft.getInstance().screen instanceof net.minecraft.client.gui.screens.ChatScreen)) return false;
        return draw(CHAT, chat, g, mx, my, paint);
    }

    private static boolean draw(Layer l, Object owner, GuiGraphics g, double mx, double my, Runnable paint) {
        Minecraft mc = Minecraft.getInstance();
        var win = mc.getWindow();
        int fw = win.getWidth(), fh = win.getHeight();
        if (fw <= 0 || fh <= 0) return false;
        long now = Util.getMillis();
        try {
            if (l.target == null) {
                l.target = new TextureTarget(fw, fh, true, Minecraft.ON_OSX);
                l.target.setClearColor(0F, 0F, 0F, 0F);
            } else if (l.target.width != fw || l.target.height != fh) {
                l.target.resize(fw, fh, Minecraft.ON_OSX);
                l.valid = false;
            }
            if (owner != l.owner) {
                l.owner = owner;
                l.ownerSince = now;
                l.valid = false;
            }
            if (l.scale != win.getGuiScale()) {
                l.scale = win.getGuiScale();
                l.valid = false;
            }
            boolean moved = mx != l.mx || my != l.my;
            boolean active = moved || now - lastInput < 500 || now - l.ownerSince < 700; // открытие окна — плавная анимация
            ArcConfig c = ArcConfig.get();
            long interval = 1000L / Math.max(1, active ? Math.max(5, c.guiActiveFps) : Math.max(1, c.guiIdleFps));
            if (!l.valid || now - l.last >= interval) {
                capture(l, g, paint);
                l.last = now;
                l.valid = true;
                l.mx = mx;
                l.my = my;
            }
            composite(l);
            return true;
        } catch (Throwable t) {
            failed = true;
            capturing = false;
            ArcHud.LOGGER.warn("Arc HUD: cached window output disabled", t);
            return false;
        }
    }

    private static void capture(Layer l, GuiGraphics g, Runnable paint) {
        Minecraft mc = Minecraft.getInstance();
        g.flush();
        RenderTarget main = mc.getMainRenderTarget();
        l.target.clear(Minecraft.ON_OSX);
        // окна, которые сами переключают буфер на «основной», должны попадать в наш
        ((MinecraftTargetAccessor) mc).archud$setMainRenderTarget(l.target);
        l.target.bindWrite(true);
        capturing = true;
        try {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            paint.run();
            g.flush();
        } finally {
            capturing = false;
            ((MinecraftTargetAccessor) mc).archud$setMainRenderTarget(main);
            main.bindWrite(true);
            RenderSystem.defaultBlendFunc();
        }
    }

    /** Наложить готовую картинку на экран (premultiplied alpha). */
    private static void composite(Layer l) {
        Minecraft mc = Minecraft.getInstance();
        float gw = (float) mc.getWindow().getGuiScaledWidth(), gh = (float) mc.getWindow().getGuiScaledHeight();
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
        RenderSystem.setShaderTexture(0, l.target.getColorTextureId());
        Matrix4f m = new Matrix4f();
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        b.vertex(m, 0F, gh, 0F).uv(0F, 0F).endVertex();
        b.vertex(m, gw, gh, 0F).uv(1F, 0F).endVertex();
        b.vertex(m, gw, 0F, 0F).uv(1F, 1F).endVertex();
        b.vertex(m, 0F, 0F, 0F).uv(0F, 1F).endVertex();
        BufferUploader.drawWithShader(b.end());
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
    }

    // ------------------------------------------------------------------ подмена смешивания на время записи

    private static final int ONE = 1, ONE_MINUS_SRC_ALPHA = 0x0303;

    /** Режим смешивания для альфа-канала при записи в кэш: альфа накапливается («over»), а не перезаписывается. */
    public static int[] blendOverride(int srcRgb, int dstRgb, int srcA, int dstA) {
        if (!capturing) return null;
        if (srcA == ONE && dstA == ONE_MINUS_SRC_ALPHA) return null;
        return new int[]{srcRgb, dstRgb, ONE, ONE_MINUS_SRC_ALPHA};
    }
}
