package com.arc4io.archud.client.screen;

import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Theme;
import net.minecraft.client.Options;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * Универсальный экран настроек в стиле Arc: список OptionInstance в две колонки с прокруткой.
 * Используется для звука, чата, специальных возможностей.
 */
public class ArcOptionListScreen extends ArcScreen {
    protected final Options options;
    private final List<OptionInstance<?>> optionList;
    protected final List<AbstractWidget> items = new ArrayList<>();
    protected double scroll;
    protected int columns = 2;
    protected int listX, listY, listW, listH;

    public ArcOptionListScreen(Screen parent, Component title, Options options, List<OptionInstance<?>> optionList) {
        super(title, parent);
        this.options = options;
        this.optionList = optionList;
    }

    // ---- готовые наборы ----
    public static ArcOptionListScreen sound(Screen parent, Options o) {
        List<OptionInstance<?>> list = new ArrayList<>();
        for (SoundSource s : SoundSource.values()) list.add(o.getSoundSourceOptionInstance(s));
        list.add(o.soundDevice());
        list.add(o.showSubtitles());
        list.add(o.directionalAudio());
        return new ArcOptionListScreen(parent, Component.translatable("options.sounds.title"), o, list);
    }

    public static ArcOptionListScreen chat(Screen parent, Options o) {
        return new ArcOptionListScreen(parent, Component.translatable("options.chat.title"), o, List.of(
                o.chatVisibility(), o.chatColors(), o.chatLinks(), o.chatLinksPrompt(), o.chatOpacity(), o.textBackgroundOpacity(),
                o.chatScale(), o.chatLineSpacing(), o.chatDelay(), o.chatWidth(), o.chatHeightFocused(), o.chatHeightUnfocused(),
                o.narrator(), o.autoSuggestions(), o.hideMatchedNames(), o.reducedDebugInfo(), o.onlyShowSecureChat()));
    }

    public static ArcOptionListScreen accessibility(Screen parent, Options o) {
        return new ArcOptionListScreen(parent, Component.translatable("options.accessibility.title"), o, List.of(
                o.narrator(), o.showSubtitles(), o.highContrast(), o.autoJump(), o.textBackgroundOpacity(), o.backgroundForChatOnly(),
                o.chatOpacity(), o.chatLineSpacing(), o.chatDelay(), o.notificationDisplayTime(), o.toggleCrouch(), o.toggleSprint(),
                o.screenEffectScale(), o.fovEffectScale(), o.darknessEffectScale(), o.damageTiltStrength(), o.glintSpeed(),
                o.glintStrength(), o.hideLightningFlash(), o.darkMojangStudiosBackground(), o.panoramaSpeed()));
    }

    /** Дополнительные виджеты (переопределяется в подклассах). */
    protected void addExtraItems(List<AbstractWidget> out, int colW) {}

    protected int leftInset() {
        return 0;
    }

    @Override
    protected void init() {
        layout(460, height - 20);
        listX = px + 10 + leftInset();
        listY = py + 28;
        listW = pw - 20 - leftInset();
        listH = ph - 28 - 32;
        items.clear();
        int colW = (listW - (columns - 1) * 8) / columns;
        for (OptionInstance<?> o : optionList) {
            items.add(addRenderableWidget(o.createButton(options, 0, 0, colW)));
        }
        List<AbstractWidget> extra = new ArrayList<>();
        addExtraItems(extra, colW);
        for (AbstractWidget w : extra) items.add(addRenderableWidget(w));
        addDone();
        reposition();
    }

    protected void reposition() {
        int colW = (listW - (columns - 1) * 8) / columns;
        int rows = (items.size() + columns - 1) / columns;
        double max = Math.max(0, rows * 24 - listH);
        scroll = Mth.clamp(scroll, 0, max);
        for (int i = 0; i < items.size(); i++) {
            AbstractWidget w = items.get(i);
            int col = i % columns, row = i / columns;
            int y = listY + row * 24 - (int) scroll;
            w.setX(listX + col * (colW + 8));
            w.setY(y);
            w.visible = y >= listY - 2 && y + 20 <= listY + listH + 2;
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        reposition();
        frame(g);
        int rows = (items.size() + columns - 1) / columns;
        int content = rows * 24;
        if (content > listH) {
            int accent = Theme.accent();
            int barH = Math.max(14, listH * listH / content);
            int barY = listY + (int) ((listH - barH) * (scroll / (content - listH)));
            g.fill(px + pw - 6, listY, px + pw - 4, listY + listH, 0x30FFFFFF);
            g.fill(px + pw - 6, barY, px + pw - 4, barY + barH, Draw.alpha(accent, 0xCC));
        }
        renderExtra(g, mouseX, mouseY, partialTick);
        super.render(g, mouseX, mouseY, partialTick);
    }

    protected void renderExtra(GuiGraphics g, int mouseX, int mouseY, float partialTick) {}

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (super.mouseScrolled(mx, my, delta)) return true;
        scroll -= delta * 24;
        return true;
    }

    @Override
    public void removed() {
        options.save();
    }
}
