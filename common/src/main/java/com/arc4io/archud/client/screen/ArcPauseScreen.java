package com.arc4io.archud.client.screen;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.client.ArcClient;
import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Icons;
import com.arc4io.archud.client.hud.Theme;
import com.arc4io.archud.client.map.WorldMapScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.achievement.StatsScreen;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.ShareToLanScreen;
import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.network.chat.Component;

/**
 * Новое меню паузы: без «Оставить отзыв» и «Нашли ошибку?», с картой, модами, скином и Arc HUD.
 */
public class ArcPauseScreen extends Screen {
    private int px, py, pw, ph;

    public ArcPauseScreen() {
        super(Component.translatable("menu.game"));
    }

    @Override
    protected void init() {
        pw = 220;
        int bw = pw - 20;
        int half = (bw - 6) / 2;
        int rows = 7;
        ph = 34 + rows * 24 + 6;
        px = (width - pw) / 2;
        py = Math.max(8, (height - ph) / 2);
        int x = px + 10, y = py + 30;

        addRenderableWidget(new ArcButton(x, y, bw, 20, Component.translatable("menu.returnToGame"), b -> {
            minecraft.setScreen(null);
            minecraft.mouseHandler.grabMouse();
        }).icon(Icons.JUMP));
        y += 24;
        addRenderableWidget(new ArcButton(x, y, half, 20, Component.translatable("gui.advancements"),
                b -> minecraft.setScreen(new AdvancementsScreen(minecraft.player.connection.getAdvancements()))).icon(Icons.STAR));
        addRenderableWidget(new ArcButton(x + half + 6, y, half, 20, Component.translatable("gui.stats"),
                b -> minecraft.setScreen(new StatsScreen(this, minecraft.player.getStats()))).icon(Icons.CALENDAR));
        y += 24;
        addRenderableWidget(new ArcButton(x, y, half, 20, Component.translatable("archud.map.title"),
                b -> minecraft.setScreen(new WorldMapScreen(this))).icon(Icons.PIN));
        Screen mods = ArcClient.platform().createModListScreen(this);
        ArcButton modsBtn = new ArcButton(x + half + 6, y, half, 20, Component.translatable("archud.menu.mods"), b -> {
            Screen s = ArcClient.platform().createModListScreen(this);
            if (s != null) minecraft.setScreen(s);
        }).icon(Icons.CHIP);
        modsBtn.active = mods != null;
        addRenderableWidget(modsBtn);
        y += 24;
        addRenderableWidget(new ArcButton(x, y, half, 20, Component.translatable("menu.options"),
                b -> minecraft.setScreen(new OptionsScreen(this, minecraft.options))).icon(Icons.COMPASS));
        boolean published = minecraft.hasSingleplayerServer() && minecraft.getSingleplayerServer().isPublished();
        ArcButton lan = new ArcButton(x + half + 6, y, half, 20, Component.translatable(published ? "archud.admin.title" : "menu.shareToLan"),
                b -> minecraft.setScreen(published ? new HostPanelScreen(this) : new ShareToLanScreen(this))).icon(Icons.SIGNAL);
        lan.active = minecraft.hasSingleplayerServer();
        addRenderableWidget(lan);
        y += 24;
        addRenderableWidget(new ArcButton(x, y, half, 20, Component.translatable("archud.menu.arc_settings"),
                b -> minecraft.setScreen(new ArcConfigScreen(this))).icon(Icons.STAR));
        ArcButton skin = new ArcButton(x + half + 6, y, half, 20, Component.translatable("archud.skin.short"),
                b -> minecraft.setScreen(new SkinRestorerScreen(this))).icon(Icons.HEART);
        skin.active = SkinRestorerScreen.available();
        addRenderableWidget(skin);
        y += 30;
        Component quit = minecraft.isLocalServer() ? Component.translatable("menu.returnToMenu") : Component.translatable("menu.disconnect");
        addRenderableWidget(new ArcButton(x, y, bw, 20, quit, b -> {
            b.active = false;
            disconnect();
        }).icon(Icons.WARNING).danger());
    }

    private void disconnect() {
        boolean local = minecraft.isLocalServer();
        minecraft.level.disconnect();
        if (local) minecraft.clearLevel(new GenericDirtMessageScreen(Component.translatable("menu.savingLevel")));
        else minecraft.clearLevel();
        TitleScreen title = new TitleScreen();
        if (local) minecraft.setScreen(title);
        else minecraft.setScreen(new JoinMultiplayerScreen(title));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        int accent = Theme.accent();
        Draw.accentPanel(g, px, py, pw, ph, accent);
        g.drawCenteredString(font, title, px + pw / 2, py + 8, Draw.TEXT);
        String world = minecraft.isLocalServer() && minecraft.getSingleplayerServer() != null
                ? minecraft.getSingleplayerServer().getWorldData().getLevelName()
                : minecraft.getCurrentServer() != null ? minecraft.getCurrentServer().name : "";
        Draw.smallCentered(g, font, world, px + pw / 2F, py + 19, Draw.TEXT_DIM, 0.75F);
        Draw.small(g, font, ArcHud.NAME, px + 6, py + ph + 3, Draw.alpha(accent, 0xA0), 0.6F);
        super.render(g, mouseX, mouseY, partialTick);
    }
}
