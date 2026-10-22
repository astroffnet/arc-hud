package com.arc4io.archud.client.screen;

import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Icons;
import com.arc4io.archud.client.hud.Theme;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.PlayerModelPart;

import java.util.List;

/**
 * «Внешний вид»: части модели скина, ведущая рука, превью персонажа и смена скина (SkinRestorer).
 */
public class ArcAppearanceScreen extends ArcOptionListScreen {
    public ArcAppearanceScreen(Screen parent, Options options) {
        super(parent, Component.translatable("options.skinCustomisation.title"), options, List.of(options.mainHand()));
        this.columns = 1;
    }

    @Override
    protected int leftInset() {
        return 110;
    }

    @Override
    protected void addExtraItems(List<AbstractWidget> out, int colW) {
        for (PlayerModelPart part : PlayerModelPart.values()) {
            out.add(CycleButton.onOffBuilder(options.isModelPartEnabled(part))
                    .create(0, 0, colW, 20, part.getName(), (b, v) -> options.toggleModelPart(part, v)));
        }
        if (SkinRestorerScreen.available()) {
            out.add(new ArcButton(0, 0, colW, 20, Component.translatable("archud.skin.open"),
                    b -> minecraft.setScreen(new SkinRestorerScreen(this))).icon(Icons.STAR));
        }
    }

    @Override
    protected void renderExtra(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int bx = px + 10, by = py + 28, bw = 100, bh = ph - 60;
        Draw.panel(g, bx, by, bw, bh, 0x60101419, Draw.alpha(Theme.accent(), 0x50));
        if (minecraft.player != null) {
            int cx = bx + bw / 2, cy = by + bh - 16;
            InventoryScreen.renderEntityInInventoryFollowsMouse(g, cx, cy, Math.min(50, bh / 3), cx - mouseX, cy - bh / 2F - mouseY, minecraft.player);
        } else {
            g.drawCenteredString(font, Component.translatable("archud.skin.preview_in_world"), bx + bw / 2, by + bh / 2, Draw.TEXT_DIM);
        }
    }
}
