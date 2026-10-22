package com.arc4io.archud.client.screen;

import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;

/**
 * GUI для SkinsRestorer (Suiranoil/SkinRestorer): сменить скин по нику Mojang / Ely.by или по ссылке,
 * сбросить, обновить. Работает, если на сервере (или в одиночной игре) стоит SkinRestorer —
 * отправляет его команды /skin.
 */
public class SkinRestorerScreen extends ArcScreen {
    private enum Source {MOJANG, ELY, WEB}

    private Source source = Source.MOJANG;
    private boolean slim;
    private EditBox input;
    private String status = "";
    private int statusColor = Draw.TEXT_DIM;

    public SkinRestorerScreen(Screen parent) {
        super(Component.translatable("archud.skin.title"), parent);
    }

    /** Есть ли команда /skin на сервере. */
    public static boolean available() {
        Minecraft mc = Minecraft.getInstance();
        return mc.getConnection() != null && mc.getConnection().getCommands().getRoot().getChild("skin") != null;
    }

    @Override
    protected void init() {
        layout(380, 230);
        int x = px + 110, w = pw - 120;
        int y = py + 30;
        addRenderableWidget(CycleButton.<Source>builder(s -> Component.translatable("archud.skin.source." + s.name().toLowerCase()))
                .withValues(Source.values()).withInitialValue(source)
                .create(x, y, w, 20, Component.translatable("archud.skin.source"), (b, v) -> {
                    source = v;
                    rebuildHint();
                }));
        y += 26;
        input = addRenderableWidget(new EditBox(font, x, y, w, 18, Component.translatable("archud.skin.value")));
        input.setMaxLength(512);
        rebuildHint();
        y += 24;
        addRenderableWidget(CycleButton.<Boolean>builder(v -> Component.translatable(v ? "archud.skin.slim" : "archud.skin.classic"))
                .withValues(false, true).withInitialValue(slim)
                .create(x, y, w, 20, Component.translatable("archud.skin.model"), (b, v) -> slim = v));
        y += 26;
        addRenderableWidget(new ArcButton(x, y, w, 20, Component.translatable("archud.skin.apply"), b -> apply()));
        y += 24;
        int third = (w - 8) / 3;
        addRenderableWidget(new ArcButton(x, y, third, 18, Component.translatable("archud.skin.refresh"), b -> run("skin refresh")));
        addRenderableWidget(new ArcButton(x + third + 4, y, third, 18, Component.translatable("archud.skin.clear"), b -> run("skin clear")));
        addRenderableWidget(new ArcButton(x + 2 * (third + 4), y, third, 18, Component.translatable("archud.skin.reset"), b -> run("skin reset")).danger());
        addDone();
    }

    private void rebuildHint() {
        if (input == null) return;
        input.setHint(Component.translatable(source == Source.WEB ? "archud.skin.hint_url" : "archud.skin.hint_name")
                .withStyle(s -> s.withColor(0x6A7380)));
    }

    private void apply() {
        String v = input.getValue().trim();
        if (v.isEmpty()) {
            setStatus(Component.translatable("archud.skin.empty").getString(), 0xFFFF8A80);
            return;
        }
        if (source == Source.WEB) {
            run("skin set web " + (slim ? "slim" : "classic") + " \"" + v.replace("\"", "") + "\"");
        } else {
            if (!v.matches("[A-Za-z0-9_]{1,16}")) {
                setStatus(Component.translatable("archud.skin.bad_name").getString(), 0xFFFF8A80);
                return;
            }
            run("skin set " + (source == Source.ELY ? "ely.by" : "mojang") + " " + v);
        }
    }

    private void run(String command) {
        if (minecraft.getConnection() == null) return;
        if (!available()) {
            setStatus(Component.translatable("archud.skin.unavailable").getString(), 0xFFFF8A80);
            return;
        }
        minecraft.getConnection().sendCommand(command);
        setStatus(Component.translatable("archud.skin.sent", "/" + command).getString(), 0xFF9CFF5A);
    }

    private void setStatus(String s, int color) {
        status = s;
        statusColor = color;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        frame(g);
        int bx = px + 10, by = py + 28, bw = 92, bh = ph - 60;
        Draw.panel(g, bx, by, bw, bh, 0x60101419, Draw.alpha(Theme.accent(), 0x50));
        if (minecraft.player != null) {
            int cx = bx + bw / 2, cy = by + bh - 12;
            InventoryScreen.renderEntityInInventoryFollowsMouse(g, cx, cy, Math.min(48, bh / 3), cx - mouseX, cy - bh / 2F - mouseY, minecraft.player);
        }
        if (!status.isEmpty()) {
            String s = font.plainSubstrByWidth(status, pw - 130);
            g.drawString(font, s, px + 110, py + ph - 40, statusColor, true);
        }
        if (!available()) {
            g.drawString(font, Component.translatable("archud.skin.unavailable"), px + 110, py + ph - 52, 0xFFFFB74D, true);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }
}
