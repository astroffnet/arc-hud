package com.arc4io.archud.client.screen;

import com.arc4io.archud.client.hud.Draw;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.HttpUtil;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;

/**
 * «Открыть для сети» с расширенными настройками: режим, читы, порт, PvP, полёт,
 * лицензия (вход без лицензии для друзей), сложность и описание сервера (MOTD).
 */
public class ArcLanScreen extends ArcScreen {
    private GameType mode = GameType.SURVIVAL;
    private boolean cheats, pvp = true, flight, onlineMode = true;
    private Difficulty difficulty;
    private EditBox port, motd;
    private String error = "";

    public ArcLanScreen(Screen parent) {
        super(Component.translatable("lanServer.title"), parent);
    }

    @Override
    protected void init() {
        layout(360, 250);
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (difficulty == null && server != null) {
            difficulty = server.getWorldData().getDifficulty();
            mode = server.getDefaultGameType();
            cheats = server.getWorldData().getAllowCommands();
        }
        int x = px + 10, w = pw - 20, half = (w - 6) / 2, y = py + 28;
        addRenderableWidget(CycleButton.builder(GameType::getShortDisplayName)
                .withValues(GameType.SURVIVAL, GameType.CREATIVE, GameType.ADVENTURE, GameType.SPECTATOR)
                .withInitialValue(mode).create(x, y, half, 20, Component.translatable("selectWorld.gameMode"), (b, v) -> mode = v));
        addRenderableWidget(CycleButton.onOffBuilder(cheats).create(x + half + 6, y, half, 20,
                Component.translatable("selectWorld.allowCommands"), (b, v) -> cheats = v));
        y += 24;
        addRenderableWidget(CycleButton.onOffBuilder(pvp).create(x, y, half, 20, Component.translatable("archud.lan.pvp"), (b, v) -> pvp = v));
        addRenderableWidget(CycleButton.onOffBuilder(flight).create(x + half + 6, y, half, 20, Component.translatable("archud.lan.flight"), (b, v) -> flight = v));
        y += 24;
        addRenderableWidget(CycleButton.onOffBuilder(onlineMode).create(x, y, half, 20, Component.translatable("archud.lan.online"), (b, v) -> onlineMode = v));
        addRenderableWidget(CycleButton.builder(Difficulty::getDisplayName).withValues(Difficulty.values())
                .withInitialValue(difficulty == null ? Difficulty.NORMAL : difficulty)
                .create(x + half + 6, y, half, 20, Component.translatable("options.difficulty"), (b, v) -> difficulty = v));
        y += 34;
        port = addRenderableWidget(new EditBox(font, x + 90, y, half - 90 + half + 6, 16, Component.translatable("lanServer.port")));
        port.setValue(Integer.toString(HttpUtil.isPortAvailable(25565) ? 25565 : HttpUtil.getAvailablePort()));
        port.setFilter(s -> s.isEmpty() || s.matches("\\d{1,5}"));
        y += 22;
        motd = addRenderableWidget(new EditBox(font, x + 90, y, half - 90 + half + 6, 16, Component.translatable("archud.lan.motd")));
        motd.setMaxLength(59);
        motd.setValue(server != null ? server.getWorldData().getLevelName() : "");
        addBack();
        addRenderableWidget(new ArcButton(px + pw - 168, py + ph - 24, 160, 18, Component.translatable("lanServer.start"), b -> start()));
    }

    private void start() {
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null) return;
        int p;
        try {
            p = Integer.parseInt(port.getValue());
            if (p < 1024 || p > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            error = Component.translatable("lanServer.port.invalid.new", 1024, 65535).getString();
            return;
        }
        server.setPvpAllowed(pvp);
        server.setFlightAllowed(flight);
        server.setUsesAuthentication(onlineMode);
        if (!motd.getValue().isBlank()) server.setMotd(motd.getValue());
        if (difficulty != null) server.execute(() -> server.setDifficulty(difficulty, true));
        boolean ok = server.publishServer(mode, cheats, p);
        Component msg = ok ? Component.translatable("commands.publish.started", p) : Component.translatable("commands.publish.failed");
        minecraft.gui.getChat().addMessage(msg);
        minecraft.updateTitle();
        minecraft.setScreen(null);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        frame(g);
        g.drawString(font, Component.translatable("lanServer.port"), px + 10, port.getY() + 4, Draw.TEXT_DIM, true);
        g.drawString(font, Component.translatable("archud.lan.motd"), px + 10, motd.getY() + 4, Draw.TEXT_DIM, true);
        if (!error.isEmpty()) g.drawCenteredString(font, error, px + pw / 2, motd.getY() + 22, 0xFFFF5252);
        Draw.smallCentered(g, font, Component.translatable("archud.lan.hint").getString(), px + pw / 2F, motd.getY() + 34, Draw.TEXT_DIM, 0.6F);
        super.render(g, mouseX, mouseY, partialTick);
    }
}
