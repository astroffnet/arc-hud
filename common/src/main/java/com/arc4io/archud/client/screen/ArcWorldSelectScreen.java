package com.arc4io.archud.client.screen;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Icons;
import com.arc4io.archud.client.hud.Theme;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.datafixers.util.Pair;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.EditWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;
import org.lwjgl.glfw.GLFW;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * «Одиночная игра» в стиле Arc: карточки миров с иконками, поиск, панель подробностей
 * (режим, читы, версия, дата) и действия — играть, создать, изменить, пересоздать, удалить.
 */
public class ArcWorldSelectScreen extends Screen implements com.arc4io.archud.client.screen.ScreenFit.Fits {
    private static final int ROW = 40;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());
    private final Screen parent;
    private List<LevelSummary> all = new ArrayList<>();
    private List<LevelSummary> shown = new ArrayList<>();
    private boolean loading = true;
    private String error;
    private LevelSummary selected;
    private double scroll;
    private EditBox search;
    private final Map<String, Optional<ResourceLocation>> icons = new HashMap<>();
    private long lastClick;
    private ArcButton play, edit, recreate, delete;
    private long opened = Util.getMillis();

    public ArcWorldSelectScreen(Screen parent) {
        super(Component.translatable("selectWorld.title"));
        this.parent = parent;
    }

    // ------------------------------------------------------------------ данные
    private void reload() {
        loading = true;
        error = null;
        try {
            LevelStorageSource.LevelCandidates c = minecraft.getLevelSource().findLevelCandidates();
            minecraft.getLevelSource().loadLevelSummaries(c).whenCompleteAsync((list, ex) -> {
                if (ex != null) {
                    error = ex.getMessage();
                    all = new ArrayList<>();
                } else {
                    List<LevelSummary> l = new ArrayList<>(list);
                    l.sort(null);
                    all = l;
                }
                loading = false;
                filter();
            }, minecraft);
        } catch (Exception e) {
            error = e.getMessage();
            loading = false;
        }
    }

    private void filter() {
        String q = search == null ? "" : search.getValue().toLowerCase(Locale.ROOT);
        List<LevelSummary> out = new ArrayList<>();
        for (LevelSummary s : all) {
            if (q.isEmpty() || s.getLevelName().toLowerCase(Locale.ROOT).contains(q) || s.getLevelId().toLowerCase(Locale.ROOT).contains(q)) out.add(s);
        }
        shown = out;
        if (selected != null && !out.contains(selected)) selected = null;
        if (selected == null && !out.isEmpty()) selected = out.get(0);
        updateButtons();
    }

    private ResourceLocation icon(LevelSummary s) {
        return icons.computeIfAbsent(s.getLevelId(), id -> {
            Path p = s.getIcon();
            if (p == null || !Files.isRegularFile(p)) return Optional.empty();
            try (InputStream in = Files.newInputStream(p)) {
                NativeImage img = NativeImage.read(in);
                ResourceLocation loc = ArcHud.id("world_icon/" + Integer.toHexString(id.hashCode()) + "_" + (opened % 100000));
                minecraft.getTextureManager().register(loc, new DynamicTexture(img));
                return Optional.of(loc);
            } catch (Exception e) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    // ------------------------------------------------------------------ разметка
    private int listX() {
        return 12;
    }

    private int listW() {
        return Math.max(200, (int) (width * 0.56F)) - 12;
    }

    private int listTop() {
        return 52;
    }

    private int listBottom() {
        return height - 40;
    }

    private int detailX() {
        return listX() + listW() + 10;
    }

    private int detailW() {
        return width - detailX() - 12;
    }

    @Override
    protected void init() {
        com.arc4io.archud.client.screen.ScreenFit.ensure(this, 480, 260);
        String old = search == null ? "" : search.getValue();
        search = addRenderableWidget(new EditBox(font, listX(), 28, listW(), 16, Component.translatable("selectWorld.search")));
        search.setHint(Component.translatable("selectWorld.search").withStyle(ChatFormatting.DARK_GRAY));
        search.setValue(old);
        search.setResponder(s -> filter());

        int dx = detailX(), dw = detailW();
        int by = height - 112;
        play = addRenderableWidget(new ArcButton(dx, by, dw, 22, Component.translatable("selectWorld.select"), b -> play()).icon(Icons.JUMP));
        int half = (dw - 4) / 2;
        edit = addRenderableWidget(new ArcButton(dx, by + 26, half, 20, Component.translatable("selectWorld.edit"), b -> edit()).icon(Icons.PICKAXE));
        recreate = addRenderableWidget(new ArcButton(dx + half + 4, by + 26, dw - half - 4, 20, Component.translatable("selectWorld.recreate"),
                b -> recreate()).icon(Icons.SORT));
        delete = addRenderableWidget(new ArcButton(dx, by + 50, dw, 20, Component.translatable("selectWorld.delete"), b -> delete())
                .icon(Icons.WARNING).danger());

        int bw = Math.min(160, (listW() - 6) / 2);
        addRenderableWidget(new ArcButton(listX(), height - 30, bw, 20, Component.translatable("selectWorld.create"),
                b -> CreateWorldScreen.openFresh(minecraft, this)).icon(Icons.STAR));
        addRenderableWidget(new ArcButton(width - 12 - 100, height - 30, 100, 20, CommonComponents.GUI_BACK, b -> onClose()));
        if (all.isEmpty() && loading) reload();
        else filter();
        updateButtons();
    }

    private void updateButtons() {
        if (play == null) return;
        boolean has = selected != null;
        play.active = has && !selected.isDisabled();
        edit.active = has && !selected.isLocked() && !selected.requiresManualConversion();
        recreate.active = has && !selected.isLocked() && !selected.requiresManualConversion();
        delete.active = has && !selected.isLocked();
        if (has && selected.requiresManualConversion()) play.setMessage(Component.translatable("selectWorld.conversion"));
        else play.setMessage(Component.translatable("selectWorld.select"));
    }

    // ------------------------------------------------------------------ действия
    private void play() {
        LevelSummary s = selected;
        if (s == null || s.isDisabled()) return;
        if (s.askToOpenWorld()) {
            minecraft.setScreen(new ConfirmScreen(ok -> {
                if (ok) load(s);
                else minecraft.setScreen(this);
            }, Component.translatable("selectWorld.versionQuestion"),
                    Component.translatable("selectWorld.versionWarning", s.getWorldVersionName()),
                    Component.translatable("selectWorld.versionJoinButton"), CommonComponents.GUI_CANCEL));
            return;
        }
        load(s);
    }

    private void load(LevelSummary s) {
        minecraft.forceSetScreen(new GenericDirtMessageScreen(Component.translatable("selectWorld.data_read")));
        if (minecraft.getLevelSource().levelExists(s.getLevelId())) {
            minecraft.createWorldOpenFlows().loadLevel(this, s.getLevelId());
        } else {
            minecraft.setScreen(this);
        }
    }

    private void edit() {
        LevelSummary s = selected;
        if (s == null) return;
        try {
            LevelStorageSource.LevelStorageAccess access = minecraft.getLevelSource().validateAndCreateAccess(s.getLevelId());
            minecraft.setScreen(new EditWorldScreen(changed -> {
                try {
                    access.close();
                } catch (Exception ignored) {
                }
                if (changed) {
                    icons.clear();
                    reload();
                }
                minecraft.setScreen(this);
            }, access));
        } catch (Exception e) {
            ArcHud.LOGGER.warn("Arc HUD: не удалось открыть мир для изменения", e);
        }
    }

    private void recreate() {
        LevelSummary s = selected;
        if (s == null) return;
        minecraft.forceSetScreen(new GenericDirtMessageScreen(Component.translatable("selectWorld.data_read")));
        try (LevelStorageSource.LevelStorageAccess access = minecraft.getLevelSource().validateAndCreateAccess(s.getLevelId())) {
            Pair<LevelSettings, WorldCreationContext> pair = minecraft.createWorldOpenFlows().recreateWorldData(access);
            Path packs = CreateWorldScreen.createTempDataPackDirFromExistingWorld(access.getLevelPath(LevelResource.DATAPACK_DIR), minecraft);
            minecraft.setScreen(CreateWorldScreen.createFromExisting(minecraft, this, pair.getFirst(), pair.getSecond(), packs));
        } catch (Exception e) {
            ArcHud.LOGGER.warn("Arc HUD: не удалось пересоздать мир", e);
            minecraft.setScreen(this);
        }
    }

    private void delete() {
        LevelSummary s = selected;
        if (s == null) return;
        minecraft.setScreen(new ConfirmScreen(ok -> {
            if (ok) {
                minecraft.forceSetScreen(new GenericDirtMessageScreen(Component.translatable("selectWorld.deleting")));
                try (LevelStorageSource.LevelStorageAccess access = minecraft.getLevelSource().createAccess(s.getLevelId())) {
                    access.deleteLevel();
                } catch (Exception e) {
                    ArcHud.LOGGER.warn("Arc HUD: не удалось удалить мир", e);
                }
                selected = null;
                reload();
            }
            minecraft.setScreen(this);
        }, Component.translatable("selectWorld.deleteQuestion"),
                Component.translatable("selectWorld.deleteWarning", s.getLevelName()),
                Component.translatable("selectWorld.deleteButton"), CommonComponents.GUI_CANCEL));
    }

    // ------------------------------------------------------------------ ввод
    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (mx >= listX() && mx < listX() + listW() && my >= listTop() && my < listBottom()) {
            int i = (int) ((my - listTop() + scroll) / ROW);
            if (i >= 0 && i < shown.size()) {
                LevelSummary s = shown.get(i);
                boolean dbl = s == selected && Util.getMillis() - lastClick < 300;
                selected = s;
                lastClick = Util.getMillis();
                updateButtons();
                if (dbl) play();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scroll = Mth.clamp(scroll - delta * ROW, 0, maxScroll());
        return true;
    }

    private double maxScroll() {
        return Math.max(0, shown.size() * ROW - (listBottom() - listTop()));
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (!search.isFocused() || key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
            int idx = selected == null ? -1 : shown.indexOf(selected);
            if (key == GLFW.GLFW_KEY_DOWN && idx < shown.size() - 1) {
                select(idx + 1);
                return true;
            }
            if (key == GLFW.GLFW_KEY_UP && idx > 0) {
                select(idx - 1);
                return true;
            }
            if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && selected != null) {
                play();
                return true;
            }
        }
        return super.keyPressed(key, scan, mods);
    }

    private void select(int i) {
        selected = shown.get(i);
        double top = i * ROW, bottom = top + ROW, h = listBottom() - listTop();
        if (top < scroll) scroll = top;
        if (bottom > scroll + h) scroll = bottom - h;
        updateButtons();
    }

    // ------------------------------------------------------------------ отрисовка
    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        ArcWidgets.drawBackground(g, width, height);
        int accent = Theme.accent();
        g.drawString(font, title, listX(), 10, Draw.TEXT, true);
        g.fill(listX(), 21, listX() + 30, 22, Draw.alpha(accent, 0xFF));
        String count = loading ? Component.translatable("archud.worlds.loading").getString()
                : Component.translatable("archud.worlds.count", shown.size()).getString();
        Draw.small(g, font, count, listX() + listW() - font.width(count) * 0.7F, 12, Draw.TEXT_DIM, 0.7F);

        // список
        int lx = listX(), lw = listW(), top = listTop(), bottom = listBottom();
        Draw.panel(g, lx - 2, top - 2, lw + 4, bottom - top + 4, 0x90080B10, 0x30FFFFFF);
        scroll = Mth.clamp(scroll, 0, maxScroll());
        g.enableScissor(lx, top, lx + lw, bottom);
        int y = top - (int) scroll;
        for (LevelSummary s : shown) {
            if (y + ROW >= top && y <= bottom) renderRow(g, s, lx, y, lw, mouseX, mouseY, accent, top, bottom);
            y += ROW;
        }
        g.disableScissor();
        if (maxScroll() > 0) {
            int track = bottom - top;
            int thumb = Math.max(20, (int) (track * track / (double) (shown.size() * ROW)));
            int ty = top + (int) ((track - thumb) * (scroll / maxScroll()));
            g.fill(lx + lw - 2, ty, lx + lw, ty + thumb, Draw.alpha(accent, 0xA0));
        }
        if (!loading && shown.isEmpty()) {
            Component msg = error != null ? Component.literal(error) : Component.translatable(all.isEmpty() ? "archud.worlds.empty" : "archud.worlds.nothing");
            g.drawCenteredString(font, msg, lx + lw / 2, top + 30, Draw.TEXT_DIM);
        }

        renderDetails(g, accent);
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void renderRow(GuiGraphics g, LevelSummary s, int x, int y, int w, int mouseX, int mouseY, int accent, int top, int bottom) {
        boolean sel = s == selected;
        boolean hov = mouseX >= x && mouseX < x + w && mouseY >= Math.max(y, top) && mouseY < Math.min(y + ROW - 2, bottom);
        int bg = sel ? Draw.alpha(accent, 0x38) : hov ? 0x1AFFFFFF : 0x0CFFFFFF;
        g.fill(x, y, x + w, y + ROW - 2, bg);
        if (sel) g.fill(x, y, x + 2, y + ROW - 2, Draw.alpha(accent, 0xFF));
        ResourceLocation ic = icon(s);
        int ix = x + 5, iy = y + 3;
        if (ic != null) g.blit(ic, ix, iy, 0, 0, 32, 32, 32, 32);
        else {
            Draw.panel(g, ix, iy, 32, 32, 0xFF141A22, Draw.alpha(accent, 0x60));
            String l = s.getLevelName().isEmpty() ? "?" : s.getLevelName().substring(0, 1).toUpperCase(Locale.ROOT);
            g.drawCenteredString(font, l, ix + 16, iy + 12, Draw.alpha(accent, 0xFF));
        }
        if (hov && !s.isDisabled()) {
            g.fill(ix, iy, ix + 32, iy + 32, 0x80000000);
            g.drawCenteredString(font, "▶", ix + 16, iy + 12, 0xFFFFFFFF);
        }
        int tx = ix + 40;
        String name = s.getLevelName();
        int maxW = w - (tx - x) - 8;
        if (font.width(name) > maxW) name = font.plainSubstrByWidth(name, maxW - 6) + "…";
        g.drawString(font, name, tx, y + 5, s.isDisabled() ? 0xFF8C9AAB : Draw.TEXT, true);
        String mode = s.isHardcore() ? Component.translatable("gameMode.hardcore").getString()
                : Component.translatable("gameMode." + s.getGameMode().getName()).getString();
        String line = DATE.format(Instant.ofEpochMilli(s.getLastPlayed())) + "  ·  " + mode + (s.hasCheats() ? "  ·  " + Component.translatable("selectWorld.cheats").getString() : "");
        Draw.small(g, font, line, tx, y + 17, s.isHardcore() ? 0xFFFF8A80 : Draw.TEXT_DIM, 0.7F);
        Component info = s.getInfo();
        Draw.small(g, font, info.getString(), tx, y + 26, 0xFF6F7C8B, 0.6F);
    }

    private void renderDetails(GuiGraphics g, int accent) {
        int x = detailX(), w = detailW(), y = 28;
        int h = height - 112 - 8 - y;
        Draw.panel(g, x, y, w, h, 0xA00A0E13, Draw.alpha(accent, 0x50));
        LevelSummary s = selected;
        if (s == null) {
            g.drawCenteredString(font, Component.translatable("archud.worlds.select"), x + w / 2, y + h / 2 - 4, Draw.TEXT_DIM);
            return;
        }
        int size = Math.min(64, Math.max(32, w / 4));
        ResourceLocation ic = icon(s);
        if (ic != null) g.blit(ic, x + 8, y + 8, 0, 0, size, size, size, size);
        else Draw.panel(g, x + 8, y + 8, size, size, 0xFF141A22, Draw.alpha(accent, 0x60));
        int tx = x + size + 16, tw = w - size - 24;
        String name = s.getLevelName();
        if (font.width(name) > tw) name = font.plainSubstrByWidth(name, tw - 6) + "…";
        g.drawString(font, name, tx, y + 10, 0xFFFFFFFF, true);
        Draw.small(g, font, s.getLevelId(), tx, y + 22, Draw.TEXT_DIM, 0.65F);
        Draw.small(g, font, s.getWorldVersionName().getString(), tx, y + 31, Draw.TEXT_DIM, 0.65F);

        int ry = y + size + 16;
        ry = detail(g, x, ry, w, "archud.worlds.mode", s.isHardcore() ? Component.translatable("gameMode.hardcore").getString()
                : Component.translatable("gameMode." + s.getGameMode().getName()).getString(), s.isHardcore() ? 0xFFFF8A80 : Draw.TEXT);
        ry = detail(g, x, ry, w, "archud.worlds.cheats", Component.translatable(s.hasCheats() ? "options.on" : "options.off").getString(),
                s.hasCheats() ? 0xFF9CFF5A : Draw.TEXT_DIM);
        ry = detail(g, x, ry, w, "archud.worlds.difficulty", s.getSettings().difficulty().getDisplayName().getString(), Draw.TEXT);
        ry = detail(g, x, ry, w, "archud.worlds.played", DATE.format(Instant.ofEpochMilli(s.getLastPlayed())), Draw.TEXT);
        if (s.isExperimental()) ry = detail(g, x, ry, w, "archud.worlds.experimental", "⚠", 0xFFFFB74D);
        if (s.isLocked() || s.isDisabled() || s.requiresManualConversion()) {
            for (var line : font.split(s.getInfo(), w - 16)) {
                if (ry > y + h - 10) break;
                g.drawString(font, line, x + 8, ry, 0xFFFF8A65, true);
                ry += 10;
            }
        }
    }

    private int detail(GuiGraphics g, int x, int y, int w, String key, String value, int color) {
        Draw.small(g, font, Component.translatable(key).getString(), x + 8, y, Draw.TEXT_DIM, 0.75F);
        g.drawString(font, value, x + w - 8 - font.width(value), y - 1, color, true);
        g.fill(x + 8, y + 10, x + w - 8, y + 11, 0x10FFFFFF);
        return y + 14;
    }

    @Override
    public void removed() {
        for (Optional<ResourceLocation> o : icons.values()) o.ifPresent(minecraft.getTextureManager()::release);
        icons.clear();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
