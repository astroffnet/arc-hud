package com.arc4io.archud.client.map;

import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.screen.ArcButton;
import com.arc4io.archud.client.screen.ArcScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Создание / редактирование метки: имя, координаты, цвет, видимость, телепорт (если есть права), удаление.
 */
public class WaypointEditScreen extends ArcScreen {
    private final Waypoint wp;
    private final boolean isNew;
    private EditBox name, x, y, z;
    private int colorIndex;

    public WaypointEditScreen(Screen parent, Waypoint wp, boolean isNew) {
        super(Component.translatable(isNew ? "archud.wp.new" : "archud.wp.edit"), parent);
        this.wp = wp;
        this.isNew = isNew;
        for (int i = 0; i < Waypoint.PALETTE.length; i++) if (Waypoint.PALETTE[i] == wp.color) colorIndex = i;
    }

    @Override
    protected void init() {
        layout(300, 224);
        int lx = px + 10, w = pw - 20;
        boolean editable = wp.editable();
        name = addRenderableWidget(new EditBox(font, lx + 60, py + 28, w - 60, 16, Component.translatable("archud.wp.name")));
        name.setMaxLength(48);
        name.setValue(wp.name);
        int third = (w - 60 - 8) / 3;
        x = addRenderableWidget(new EditBox(font, lx + 60, py + 50, third, 16, Component.literal("X")));
        y = addRenderableWidget(new EditBox(font, lx + 64 + third, py + 50, third, 16, Component.literal("Y")));
        z = addRenderableWidget(new EditBox(font, lx + 68 + third * 2, py + 50, third, 16, Component.literal("Z")));
        x.setValue(Integer.toString(wp.x));
        y.setValue(Integer.toString(wp.y));
        z.setValue(Integer.toString(wp.z));
        for (EditBox b : new EditBox[]{x, y, z}) b.setFilter(s -> s.isEmpty() || s.matches("-?\\d{0,8}"));
        for (EditBox b : new EditBox[]{name, x, y, z}) b.setEditable(editable);
        addRenderableWidget(new ArcButton(lx + 60, py + 72, w - 60, 18, Component.translatable("archud.wp.color"), b -> {
            colorIndex = (colorIndex + 1) % Waypoint.PALETTE.length;
        })).active = editable;
        addRenderableWidget(CycleButton.onOffBuilder(wp.visible).create(lx, py + 96, w, 20, Component.translatable("archud.wp.visible"),
                (b, v) -> wp.visible = v));
        int bw = (w - 8) / 3;
        addRenderableWidget(new ArcButton(lx, py + 120, bw, 18,
                Component.translatable(Navigator.isTarget(wp) ? "archud.map.menu.nav_stop" : "archud.map.menu.nav"), b -> {
            if (Navigator.isTarget(wp)) Navigator.clear();
            else Navigator.set(wp);
            onClose();
        }));
        addRenderableWidget(new ArcButton(lx + bw + 4, py + 120, bw, 18, Component.translatable("archud.map.menu.share"), b -> minecraft.setScreen(new ShareWaypointScreen(this, wp))));
        ArcButton tp = addRenderableWidget(new ArcButton(lx + (bw + 4) * 2, py + 120, w - (bw + 4) * 2, 18, Component.translatable("archud.wp.teleport"), b -> teleport()));
        if (com.arc4io.archud.compat.TempadCompat.canOpenDoor(wp)) {
            addRenderableWidget(new ArcButton(lx, py + 142, w, 18, Component.translatable("archud.map.menu.tempad_door"), b -> {
                com.arc4io.archud.compat.TempadCompat.openDoor(wp);
                minecraft.setScreen(null);
            }));
        }
        tp.active = minecraft.player != null && minecraft.player.hasPermissions(2) && wp.dimension.equals(WaypointManager.currentDimension());
        ArcButton del = addRenderableWidget(new ArcButton(lx, py + ph - 24, 90, 18, Component.translatable("archud.wp.delete"), b -> {
            WaypointManager.remove(wp);
            onClose();
        }).danger());
        del.active = !isNew && editable;
        addRenderableWidget(new ArcButton(px + pw - 108, py + ph - 24, 100, 18, Component.translatable("gui.done"), b -> save()));
    }

    private int parse(EditBox b, int def) {
        try {
            return Integer.parseInt(b.getValue());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void save() {
        if (wp.editable()) {
            wp.name = name.getValue().isBlank() ? wp.name : name.getValue();
            wp.x = parse(x, wp.x);
            wp.y = parse(y, wp.y);
            wp.z = parse(z, wp.z);
            wp.color = Waypoint.PALETTE[colorIndex];
            WaypointManager.add(wp);
        }
        onClose();
    }

    private void teleport() {
        if (minecraft.getConnection() == null) return;
        minecraft.getConnection().sendCommand("tp @s " + parse(x, wp.x) + " " + parse(y, wp.y) + " " + parse(z, wp.z));
        minecraft.setScreen(null);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        frame(g);
        int lx = px + 10;
        g.drawString(font, Component.translatable("archud.wp.name"), lx, py + 32, Draw.TEXT_DIM, true);
        g.drawString(font, "X / Y / Z", lx, py + 54, Draw.TEXT_DIM, true);
        MapView.diamond(g, lx + 20, py + 81, 5, Waypoint.PALETTE[colorIndex]);
        String src = Component.translatable("archud.wp.src." + wp.source.name().toLowerCase()).getString();
        if (wp.source == Waypoint.Source.SHARED && wp.sharedBy != null) {
            src += " · " + Component.translatable("archud.share.from", wp.sharedBy).getString() + " · "
                    + Component.translatable("archud.share.save_hint").getString();
        }
        g.drawString(font, src, lx, py + 166, Draw.TEXT_DIM, true);
        super.render(g, mouseX, mouseY, partialTick);
    }
}
