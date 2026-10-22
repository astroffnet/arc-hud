package com.arc4io.archud.client.map;

import com.arc4io.archud.client.ArcClientNet;
import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.screen.ArcButton;
import com.arc4io.archud.client.screen.ArcScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Поделиться меткой: со всеми, с конкретным игроком или просто в чат.
 * Если на сервере есть Arc HUD — у получателя метка появляется на карте (с уведомлением), иначе уходит текстом.
 */
public class ShareWaypointScreen extends ArcScreen {
    private static final int PER_PAGE = 6;
    private final Waypoint wp;
    private final List<String> names = new ArrayList<>();
    private int page;

    public ShareWaypointScreen(Screen parent, Waypoint wp) {
        super(Component.translatable("archud.share.title"), parent);
        this.wp = wp;
    }

    @Override
    protected void init() {
        names.clear();
        if (minecraft.getConnection() != null) {
            for (PlayerInfo info : minecraft.getConnection().getOnlinePlayers()) {
                String n = info.getProfile().getName();
                if (minecraft.player != null && n.equals(minecraft.player.getGameProfile().getName())) continue;
                names.add(n);
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        layout(260, 60 + PER_PAGE * 22 + 44);
        int lx = px + 10, w = pw - 20;
        int half = (w - 4) / 2;
        addRenderableWidget(new ArcButton(lx, py + 36, half, 18, Component.translatable("archud.share.everyone"), b -> send("")));
        addRenderableWidget(new ArcButton(lx + half + 4, py + 36, w - half - 4, 18, Component.translatable("archud.map.menu.share_coords"), b -> {
            WaypointManager.share(wp);
            onClose();
        }));
        int y = py + 60;
        int pages = Math.max(1, (names.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.min(page, pages - 1);
        for (int i = page * PER_PAGE; i < Math.min(names.size(), (page + 1) * PER_PAGE); i++) {
            String n = names.get(i);
            addRenderableWidget(new ArcButton(lx, y, w, 18, Component.literal("➤ " + n), b -> send(n)));
            y += 22;
        }
        if (pages > 1) {
            addRenderableWidget(new ArcButton(px + pw - 62, py + ph - 24, 24, 18, Component.literal("<"), b -> {
                page = (page - 1 + pages) % pages;
                rebuildWidgets();
            }));
            addRenderableWidget(new ArcButton(px + pw - 34, py + ph - 24, 24, 18, Component.literal(">"), b -> {
                page = (page + 1) % pages;
                rebuildWidgets();
            }));
        }
        addBack();
    }

    private void send(String target) {
        WaypointManager.shareWith(wp, target);
        onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        frame(g);
        MapView.diamond(g, px + 16, py + 28, 4, wp.color);
        Draw.small(g, font, wp.name + "  (" + wp.x + ", " + wp.y + ", " + wp.z + ")", px + 24, py + 25, Draw.TEXT, 0.7F);
        if (names.isEmpty()) {
            g.drawCenteredString(font, Component.translatable("archud.share.no_players"), px + pw / 2, py + 64, Draw.TEXT_DIM);
        }
        if (!ArcClientNet.canShare()) {
            Draw.small(g, font, Component.translatable("archud.share.text_only").getString(), px + 10, py + ph - 36, 0xFFFFB74D, 0.6F);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }
}
