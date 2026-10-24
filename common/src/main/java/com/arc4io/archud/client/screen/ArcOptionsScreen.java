package com.arc4io.archud.client.screen;

import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Icons;
import com.arc4io.archud.client.hud.Theme;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.VideoSettingsScreen;
import net.minecraft.client.gui.screens.controls.ControlsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundChangeDifficultyPacket;
import net.minecraft.world.Difficulty;

/**
 * Новое меню «Настройки»: FOV и сложность сверху, плитки разделов. Без «Титров и атрибуции».
 */
public class ArcOptionsScreen extends ArcScreen {
    private final Options options;

    public ArcOptionsScreen(Screen parent, Options options) {
        super(Component.translatable("options.title"), parent);
        this.options = options;
    }

    @Override
    protected void init() {
        layout(420, 300);
        int x = px + 10, w = pw - 20;
        int half = (w - 6) / 2;
        addRenderableWidget(options.fov().createButton(options, x, py + 28, minecraft.level != null ? half : w));
        if (minecraft.level != null) {
            boolean canChange = minecraft.isSingleplayer() && !minecraft.level.getLevelData().isHardcore();
            CycleButton<Difficulty> diff = CycleButton.builder(Difficulty::getDisplayName)
                    .withValues(Difficulty.values())
                    .withInitialValue(minecraft.level.getDifficulty())
                    .create(x + half + 6, py + 28, half, 20, Component.translatable("options.difficulty"),
                            (b, d) -> minecraft.getConnection().send(new ServerboundChangeDifficultyPacket(d)));
            diff.active = canChange;
            addRenderableWidget(diff);
        }

        record Tile(Component name, int icon, Runnable action) {}
        java.util.List<Tile> extra = new java.util.ArrayList<>();
        if (com.arc4io.archud.compat.RenderCompat.shadersAvailable()) {
            extra.add(new Tile(Component.translatable("archud.options.shaders"), Icons.STAR, () -> {
                Screen sh = com.arc4io.archud.compat.RenderCompat.shaderScreen(this, options);
                if (sh != null) minecraft.setScreen(sh);
            }));
        }
        Tile[] base = {
                new Tile(Component.translatable("options.video"), Icons.SUN, () -> minecraft.setScreen(com.arc4io.archud.compat.RenderCompat.videoSettings(this, options))),
                new Tile(Component.translatable("options.sounds"), Icons.SIGNAL, () -> minecraft.setScreen(ArcOptionListScreen.sound(this, options))),
                new Tile(Component.translatable("options.controls"), Icons.COMPASS, () -> minecraft.setScreen(new ControlsScreen(this, options))),
                new Tile(Component.translatable("options.language"), Icons.GLOBE, () -> minecraft.setScreen(new ArcLanguageScreen(this, options))),
                new Tile(Component.translatable("options.chat.title"), Icons.CHIP, () -> minecraft.setScreen(ArcOptionListScreen.chat(this, options))),
                new Tile(Component.translatable("options.skinCustomisation"), Icons.HEART, () -> minecraft.setScreen(new ArcAppearanceScreen(this, options))),
                new Tile(Component.translatable("options.resourcepack"), Icons.BACKPACK, () -> minecraft.setScreen(new ArcPackScreen(this, options))),
                new Tile(Component.translatable("options.accessibility.title"), Icons.LIGHT, () -> minecraft.setScreen(ArcOptionListScreen.accessibility(this, options))),
                new Tile(Component.translatable("archud.menu.arc_settings"), Icons.STAR, () -> minecraft.setScreen(new ArcConfigScreen(this))),
        };
        java.util.List<Tile> all = new java.util.ArrayList<>(java.util.List.of(base));
        all.addAll(1, extra);
        Tile[] tiles = all.toArray(new Tile[0]);
        int cols = 3;
        int tw = (w - (cols - 1) * 6) / cols;
        int ty = py + 58;
        for (int i = 0; i < tiles.length; i++) {
            Tile t = tiles[i];
            int col = i % cols, row = i / cols;
            addRenderableWidget(new ArcButton(x + col * (tw + 6), ty + row * 30, tw, 26, t.name(), b -> t.action().run()).icon(t.icon()));
        }
        if (SkinRestorerScreen.available()) {
            addRenderableWidget(new ArcButton(x, ty + ((tiles.length + cols - 1) / cols) * 30 + 4, w, 20, Component.translatable("archud.skin.open"),
                    b -> minecraft.setScreen(new SkinRestorerScreen(this))).icon(Icons.STAR));
        }
        addDone();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        frame(g);
        Draw.small(g, font, "Arc HUD", px + pw - 40, py + 9, Draw.alpha(Theme.accent(), 0xFF), 0.75F);
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void removed() {
        options.save();
    }
}
