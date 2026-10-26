package com.arc4io.archud.client.crash;

import com.arc4io.archud.client.screen.ArcButton;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.io.File;
import java.util.List;

/** Экран после перехваченного краша: что случилось, где отчёт, «В главное меню». */
public class CrashRecoveryScreen extends Screen {
    private final Throwable error;
    private final File report;
    private final boolean wasInWorld;
    private List<FormattedCharSequence> lines = List.of();

    public CrashRecoveryScreen(Throwable error, File report, boolean wasInWorld) {
        super(Component.translatable("archud.crash.title"));
        this.error = error;
        this.report = report;
        this.wasInWorld = wasInWorld;
    }

    @Override
    protected void init() {
        int w = Math.min(420, width - 40);
        int x = (width - w) / 2;
        String msg = error == null ? "?" : error.getClass().getSimpleName() + (error.getMessage() != null ? ": " + error.getMessage() : "");
        if (msg.length() > 400) msg = msg.substring(0, 400) + "…";
        lines = font.split(Component.translatable(wasInWorld ? "archud.crash.text_world" : "archud.crash.text")
                .append("\n\n").append(Component.literal(msg).withStyle(s -> s.withColor(0xFF8A80))), w);
        int y = Math.min(height - 54, 70 + lines.size() * 10 + 16);
        addRenderableWidget(new ArcButton(x, y, w, 20, Component.translatable("archud.crash.menu"),
                b -> minecraft.setScreen(new TitleScreen())));
        int half = (w - 6) / 2;
        ArcButton open = new ArcButton(x, y + 24, half, 20, Component.translatable("archud.crash.open_report"), b -> {
            if (report != null) Util.getPlatform().openFile(report);
        });
        open.active = report != null;
        addRenderableWidget(open);
        addRenderableWidget(new ArcButton(x + half + 6, y + 24, half, 20, Component.translatable("menu.quit"), b -> minecraft.stop()));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float pt) {
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, 40, 0xFFFF6B6B);
        int w = Math.min(420, width - 40);
        int x = (width - w) / 2, y = 64;
        for (FormattedCharSequence l : lines) {
            g.drawString(font, l, x, y, 0xFFE0E6EE, false);
            y += 10;
        }
        if (report != null) g.drawString(font, Component.literal(report.getName()).withStyle(s -> s.withColor(0x6A7380)), x, height - 14, 0xFF6A7380, false);
        super.render(g, mouseX, mouseY, pt);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
