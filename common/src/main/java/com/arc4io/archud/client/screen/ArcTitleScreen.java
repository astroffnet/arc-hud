package com.arc4io.archud.client.screen;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.client.ArcClient;
import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Icons;
import com.arc4io.archud.client.hud.Theme;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LanguageSelectScreen;
import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.renderer.PanoramaRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Новый главный экран: панорама, тёмная боковая панель, анимированное меню слева,
 * логотип Arc HUD, частицы, сменяющиеся подсказки.
 */
public class ArcTitleScreen extends Screen {
    private static final ResourceLocation LOGO = ArcHud.id("textures/gui/logo.png");
    private static final int TIPS = 8;

    private final PanoramaRenderer panorama = new PanoramaRenderer(TitleScreen.CUBE_MAP);
    private final List<float[]> particles = new ArrayList<>();
    private final RandomSource random = RandomSource.create();
    private long openTime;
    private int panelW;

    public ArcTitleScreen() {
        super(Component.translatable("narrator.screen.title"));
    }

    @Override
    protected void init() {
        if (openTime == 0) openTime = Util.getMillis();
        panelW = Math.min(240, Math.max(190, width / 3));
        int bx = 26;
        int bw = panelW - 40;
        int by = Math.max(height / 2 - 52, 86);
        int step = 22;
        int i = 0;

        addRenderableWidget(new ArcButton(bx, by + step * i, bw, 20, Component.translatable("menu.singleplayer"),
                b -> minecraft.setScreen(new SelectWorldScreen(this))).menu(80 * i++).icon(Icons.HEART));
        addRenderableWidget(new ArcButton(bx, by + step * i, bw, 20, Component.translatable("menu.multiplayer"),
                b -> minecraft.setScreen(new JoinMultiplayerScreen(this))).menu(80 * i++).icon(Icons.GLOBE));

        Screen mods = ArcClient.platform().createModListScreen(this);
        ArcButton modsBtn = new ArcButton(bx, by + step * i, bw, 20, Component.translatable("archud.menu.mods"), b -> {
            Screen s = ArcClient.platform().createModListScreen(this);
            if (s != null) minecraft.setScreen(s);
        }).menu(80 * i++).icon(Icons.CHIP);
        modsBtn.active = mods != null;
        addRenderableWidget(modsBtn);

        addRenderableWidget(new ArcButton(bx, by + step * i, bw, 20, Component.translatable("menu.options"),
                b -> minecraft.setScreen(new OptionsScreen(this, minecraft.options))).menu(80 * i++).icon(Icons.COMPASS));
        addRenderableWidget(new ArcButton(bx, by + step * i, bw, 20, Component.translatable("archud.menu.arc_settings"),
                b -> minecraft.setScreen(new ArcConfigScreen(this))).menu(80 * i++).icon(Icons.STAR));
        addRenderableWidget(new ArcButton(bx, by + step * i, bw, 20, Component.translatable("options.language"),
                b -> minecraft.setScreen(new LanguageSelectScreen(this, minecraft.options, minecraft.getLanguageManager()))).menu(80 * i++).icon(Icons.SIGNAL));
        i++;
        addRenderableWidget(new ArcButton(bx, by + step * (i - 1) + 10, bw, 20, Component.translatable("menu.quit"),
                b -> minecraft.stop()).menu(80 * i).icon(Icons.WARNING).danger());
    }

    @Override
    public void tick() {
        super.tick();
        if (particles.size() < 60 && random.nextFloat() < 0.6F) {
            particles.add(new float[]{
                    random.nextFloat() * width,          // x
                    height + 4,                          // y
                    (random.nextFloat() - 0.5F) * 0.3F, // vx
                    -(0.3F + random.nextFloat() * 0.8F), // vy
                    1 + random.nextInt(3),               // size
                    0                                    // age
            });
        }
        particles.removeIf(p -> {
            p[0] += p[2];
            p[1] += p[3];
            p[5] += 1;
            return p[1] < -5;
        });
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int accent = Theme.accent();
        long now = Util.getMillis();
        float intro = Mth.clamp((now - openTime) / 900F, 0, 1);

        panorama.render(partialTick, 1.0F);

        // боковая панель и затемнения
        g.fill(0, 0, panelW, height, 0xA0060A10);
        Draw.hGradient(g, panelW, 0, panelW + 90, height, 0xA0060A10, 0x00060A10);
        g.fillGradient(0, height - 90, width, height, 0x00000000, 0xB0000000);
        g.fillGradient(0, 0, width, 40, 0x70000000, 0x00000000);
        g.fill(panelW - 1, 0, panelW, height, Draw.alpha(accent, 0x50));

        // частицы
        for (float[] p : particles) {
            int a = (int) Mth.clamp(p[1] / height * 160F, 0, 160);
            int s = (int) p[4];
            g.fill((int) p[0], (int) p[1], (int) p[0] + s, (int) p[1] + s, Draw.alpha(accent, a));
        }

        // логотип
        RenderSystem.enableBlend();
        float bob = (float) Math.sin(now / 900.0) * 2F;
        int logoW = panelW - 40, logoH = logoW / 4;
        int lx = 20;
        int ly = Math.max(10, height / 2 - 52 - logoH - 20);
        g.pose().pushPose();
        g.pose().translate(0, bob, 0);
        g.setColor(1F, 1F, 1F, Draw.easeOut(intro));
        g.blit(LOGO, lx, ly, logoW, logoH, 0, 0, 512, 128, 512, 128);
        g.setColor(1F, 1F, 1F, 1F);
        g.pose().popPose();
        Draw.small(g, font, "by arc4io", lx + logoW - font.width("by arc4io") * 0.75F - 4, ly + logoH + 2, Draw.alpha(accent, 0xFF), 0.75F);

        // подсказки
        int tip = (int) ((now / 6000L) % TIPS);
        float phase = (now % 6000L) / 6000F;
        float tipAlpha = phase < 0.1F ? phase / 0.1F : phase > 0.9F ? (1F - phase) / 0.1F : 1F;
        Component tipText = Component.translatable("archud.title.tip." + tip);
        int tipY = height - 34;
        g.drawString(font, tipText, 26, tipY, Draw.alpha(0xB0BEC5, Math.max(8, (int) (220 * tipAlpha))), true);

        // подвал
        String left = "Arc HUD " + "· Minecraft " + SharedConstants.getCurrentVersion().getName();
        g.drawString(font, left, 26, height - 20, 0xFF8C9AAB, true);
        String right = ArcHud.platform().loaderName() + " · " + ArcHud.platform().modCount() + " mods";
        g.drawString(font, right, width - font.width(right) - 6, height - 20, 0xFF8C9AAB, true);
        String copy = "Copyright Mojang AB. Do not distribute!";
        Draw.small(g, font, copy, width - font.width(copy) * 0.5F - 6, height - 9, 0x80FFFFFF, 0.5F);

        super.render(g, mouseX, mouseY, partialTick);

        // плавное появление
        if (intro < 1F) g.fill(0, 0, width, height, Draw.alpha(0x000000, (int) (255 * (1F - intro))));
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
