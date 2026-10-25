package com.arc4io.archud.client.screen;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Icons;
import com.arc4io.archud.client.hud.Theme;
import com.arc4io.archud.config.ArcConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.inventory.Hotbar;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Креатив-меню Arc HUD, написанное с нуля (не ванильный экран с новой текстурой):
 *  - слева — список вкладок: «Все предметы», «Избранное», «Недавние», «Наборы» и все категории
 *    (ванильные и из модов) с иконками и количеством предметов;
 *  - сверху — поиск, работающий в любой вкладке (текст, «@мод», «#тег»), счётчик и сортировка
 *    (по умолчанию / по имени / по моду);
 *  - в центре — сетка предметов на всю ширину окна, колесо и полоса прокрутки;
 *  - справа — карточка предмета (крупная иконка, название, id, мод, стак, теги) и кнопки «Дать ×1»,
 *    «Дать стак», «В избранное»;
 *  - снизу — инвентарь игрока с бронёй, второй рукой и корзиной.
 * Управление: ЛКМ — взять 1 (повторный клик добавляет), ПКМ / СКМ — стак, Shift+ЛКМ — стак сразу в инвентарь,
 * 1–9 — положить в слот хотбара, Q — выбросить, F — в избранное. Наборы — сохранение и загрузка хотбаров (как C+1…9).
 */
public class ArcCreativeScreen extends Screen implements ScreenFit.Fits {
    private enum Kind { ALL, FAVORITES, RECENT, KITS, CATEGORY }

    private record Tab(Kind kind, CreativeModeTab tab, Component name, ItemStack icon) {}

    private static final int T = 18;            // размер ячейки
    private static final int SIDE_W = 116, INFO_W = 120;
    private static final int INV_H = 4 * T + 4 + 12;
    private static final int RECENT_MAX = 45;
    private static final List<ItemStack> RECENT = new ArrayList<>();
    private static String lastTab = "";
    private static int sortMode;

    private final LocalPlayer player;
    private final List<Tab> tabs = new ArrayList<>();
    private Tab tab;
    private List<ItemStack> items = List.of();
    private final Map<ItemStack, String> haystack = new IdentityHashMap<>();
    private EditBox search;
    private ArcButton sortBtn, give1Btn, giveStackBtn, favBtn;
    private ItemStack carried = ItemStack.EMPTY;
    private ItemStack focus = ItemStack.EMPTY;
    private int scroll, sideScroll;
    private boolean draggingBar;

    // геометрия
    private int px, py, pw, ph;
    private int sx, cx, cw, ix;
    private int gx, gy, cols, rows;
    private int invTop, invX;

    public ArcCreativeScreen(LocalPlayer player) {
        super(Component.translatable("archud.creative.title"));
        this.player = player;
    }

    // ------------------------------------------------------------------ инициализация

    @Override
    protected void init() {
        ScreenFit.ensure(this, 480, 280);
        Minecraft mc = Minecraft.getInstance();
        boolean op = player.canUseGameMasterBlocks() && mc.options.operatorItemsTab().get();
        CreativeModeTabs.tryRebuildTabContents(player.connection.enabledFeatures(), op, player.level().registryAccess());
        buildTabs();

        px = 8;
        py = 8;
        pw = width - 16;
        ph = height - 16;
        sx = px;
        ix = px + pw - INFO_W;
        cx = sx + SIDE_W + 6;
        cw = ix - 6 - cx;
        invTop = py + ph - INV_H;
        gy = py + 34;
        cols = Math.max(1, (cw - 10) / T);
        rows = Math.max(1, (invTop - 6 - gy) / T);
        gx = cx + (cw - 8 - cols * T) / 2;
        invX = cx + (cw - (T + 6 + 9 * T + 6 + T)) / 2;

        String prev = search != null ? search.getValue() : "";
        search = new EditBox(font, cx + 1, py + 14, cw - 70, 14, Component.translatable("archud.creative.search"));
        search.setHint(Component.translatable("archud.creative.search").withStyle(s -> s.withColor(0x6A7380)));
        search.setMaxLength(64);
        search.setValue(prev);
        search.setResponder(s -> {
            scroll = 0;
            refresh();
        });
        addRenderableWidget(search);
        sortBtn = addRenderableWidget(new ArcButton(cx + cw - 66, py + 13, 66, 16, sortLabel(), b -> {
            sortMode = (sortMode + 1) % 3;
            b.setMessage(sortLabel());
            refresh();
        }));
        int by = py + 112;
        give1Btn = addRenderableWidget(new ArcButton(ix + 6, by, INFO_W - 12, 16, Component.translatable("archud.creative.give_one"),
                b -> give(focus, 1)));
        giveStackBtn = addRenderableWidget(new ArcButton(ix + 6, by + 19, INFO_W - 12, 16, Component.translatable("archud.creative.give_stack"),
                b -> give(focus, focus.getMaxStackSize())));
        favBtn = addRenderableWidget(new ArcButton(ix + 6, by + 38, INFO_W - 12, 16, Component.empty(), b -> toggleFavorite(focus))).icon(Icons.STAR);

        Tab sel = tabs.stream().filter(t -> key(t).equals(lastTab)).findFirst().orElse(tabs.get(0));
        selectTab(sel, false);
    }

    private void buildTabs() {
        tabs.clear();
        tabs.add(new Tab(Kind.ALL, null, Component.translatable("archud.creative.all"), new ItemStack(Items.COMPASS)));
        tabs.add(new Tab(Kind.FAVORITES, null, Component.translatable("archud.creative.favorites"), new ItemStack(Items.NETHER_STAR)));
        tabs.add(new Tab(Kind.RECENT, null, Component.translatable("archud.creative.recent"), new ItemStack(Items.CLOCK)));
        tabs.add(new Tab(Kind.KITS, null, Component.translatable("archud.creative.kits"), new ItemStack(Items.BUNDLE)));
        for (CreativeModeTab t : CreativeModeTabs.tabs()) {
            if (t.getType() != CreativeModeTab.Type.CATEGORY || !t.shouldDisplay()) continue;
            tabs.add(new Tab(Kind.CATEGORY, t, t.getDisplayName(), t.getIconItem()));
        }
    }

    private static String key(Tab t) {
        return t.kind() == Kind.CATEGORY ? "cat:" + t.name().getString() : t.kind().name();
    }

    private Component sortLabel() {
        return Component.translatable("archud.creative.sort." + sortMode);
    }

    private void selectTab(Tab t, boolean sound) {
        if (sound && t != tab) click();
        tab = t;
        lastTab = key(t);
        scroll = 0;
        refresh();
    }

    // ------------------------------------------------------------------ содержимое и поиск

    private List<ItemStack> source() {
        return switch (tab.kind()) {
            case ALL -> new ArrayList<>(CreativeModeTabs.searchTab().getDisplayItems());
            case FAVORITES -> favorites();
            case RECENT -> new ArrayList<>(RECENT);
            case KITS -> List.of();
            case CATEGORY -> new ArrayList<>(tab.tab().getDisplayItems());
        };
    }

    private void refresh() {
        if (tab == null) return;
        List<ItemStack> src = source();
        String q = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        List<ItemStack> out = new ArrayList<>();
        if (q.isEmpty()) out.addAll(src);
        else {
            String[] parts = q.split("\\s+");
            for (ItemStack s : src) if (matches(s, parts)) out.add(s);
        }
        if (sortMode == 1) out.sort(Comparator.comparing(s -> s.getHoverName().getString().toLowerCase(Locale.ROOT)));
        else if (sortMode == 2) out.sort(Comparator.comparing((ItemStack s) -> id(s).getNamespace()).thenComparing(s -> s.getHoverName().getString()));
        items = out;
        scroll = Mth.clamp(scroll, 0, maxScroll());
    }

    private boolean matches(ItemStack s, String[] parts) {
        ResourceLocation id = id(s);
        for (String p : parts) {
            if (p.startsWith("@") && p.length() > 1) {
                String mod = p.substring(1);
                if (!id.getNamespace().startsWith(mod) && !ArcHud.platform().modName(id.getNamespace()).toLowerCase(Locale.ROOT).startsWith(mod)) return false;
            } else if (p.startsWith("#") && p.length() > 1) {
                String tag = p.substring(1);
                if (s.getTags().noneMatch(t -> t.location().toString().contains(tag))) return false;
            } else if (!hay(s).contains(p)) return false;
        }
        return true;
    }

    private String hay(ItemStack s) {
        return haystack.computeIfAbsent(s, k -> {
            StringBuilder b = new StringBuilder(k.getHoverName().getString().toLowerCase(Locale.ROOT)).append(' ').append(id(k));
            if (k.hasTag()) {
                try {
                    for (Component c : k.getTooltipLines(player, TooltipFlag.NORMAL)) b.append(' ').append(c.getString().toLowerCase(Locale.ROOT));
                } catch (Exception ignored) {
                }
            }
            return b.toString();
        });
    }

    private static ResourceLocation id(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem());
    }

    private int totalRows() {
        return (items.size() + cols - 1) / cols;
    }

    private int maxScroll() {
        return Math.max(0, totalRows() - rows);
    }

    // ------------------------------------------------------------------ избранное и недавние

    private static List<ItemStack> favorites() {
        List<ItemStack> out = new ArrayList<>();
        for (String s : ArcConfig.get().creativeFavorites) {
            try {
                ItemStack st = ItemStack.of(TagParser.parseTag(s));
                if (!st.isEmpty()) out.add(st);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static String serialize(ItemStack s) {
        return s.copyWithCount(1).save(new net.minecraft.nbt.CompoundTag()).toString();
    }

    private boolean isFavorite(ItemStack s) {
        return !s.isEmpty() && ArcConfig.get().creativeFavorites.contains(serialize(s));
    }

    private void toggleFavorite(ItemStack s) {
        if (s.isEmpty()) return;
        List<String> fav = ArcConfig.get().creativeFavorites;
        String k = serialize(s);
        if (!fav.remove(k)) fav.add(k);
        ArcConfig.save();
        click();
        if (tab.kind() == Kind.FAVORITES) refresh();
    }

    private static void remember(ItemStack s) {
        if (s.isEmpty()) return;
        RECENT.removeIf(r -> ItemStack.isSameItemSameTags(r, s));
        RECENT.add(0, s.copyWithCount(1));
        while (RECENT.size() > RECENT_MAX) RECENT.remove(RECENT.size() - 1);
    }

    // ------------------------------------------------------------------ инвентарь игрока

    /** Индекс в Inventory → индекс слота в InventoryMenu (для пакета креатива). */
    private static int menuSlot(int inv) {
        if (inv < 9) return 36 + inv;
        if (inv < 36) return inv;
        if (inv < 40) return 8 - (inv - 36); // броня: 36 ботинки … 39 шлем → 8 … 5
        return 45;                           // вторая рука
    }

    private void setInv(int inv, ItemStack s) {
        player.getInventory().setItem(inv, s);
        Minecraft.getInstance().gameMode.handleCreativeModeItemAdd(s, menuSlot(inv));
    }

    private static boolean fits(int inv, ItemStack s) {
        if (s.isEmpty() || inv < 36 || inv > 39) return true;
        EquipmentSlot want = switch (inv) {
            case 36 -> EquipmentSlot.FEET;
            case 37 -> EquipmentSlot.LEGS;
            case 38 -> EquipmentSlot.CHEST;
            default -> EquipmentSlot.HEAD;
        };
        return Mob.getEquipmentSlotForItem(s) == want;
    }

    /** Положить в инвентарь (сначала докладываем к таким же, потом в пустые; хотбар первым). */
    private boolean give(ItemStack proto, int count) {
        if (proto.isEmpty()) return false;
        ItemStack s = proto.copyWithCount(Math.min(count, proto.getMaxStackSize()));
        remember(s);
        Inventory inv = player.getInventory();
        int[] order = new int[36];
        for (int i = 0; i < 36; i++) order[i] = i; // 0..8 — хотбар, затем основной
        for (int i : order) {
            ItemStack cur = inv.getItem(i);
            if (!cur.isEmpty() && ItemStack.isSameItemSameTags(cur, s) && cur.getCount() < cur.getMaxStackSize()) {
                int add = Math.min(s.getCount(), cur.getMaxStackSize() - cur.getCount());
                ItemStack n = cur.copyWithCount(cur.getCount() + add);
                setInv(i, n);
                s.shrink(add);
                if (s.isEmpty()) break;
            }
        }
        if (!s.isEmpty()) {
            for (int i : order) {
                if (inv.getItem(i).isEmpty()) {
                    setInv(i, s.copy());
                    s = ItemStack.EMPTY;
                    break;
                }
            }
        }
        if (!s.isEmpty()) {
            player.displayClientMessage(Component.translatable("archud.creative.full").withStyle(ChatFormatting.RED), true);
            return false;
        }
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ITEM_PICKUP, 1.6F, 0.4F));
        return true;
    }

    private int[] slotXY(int inv) {
        int mainX = invX + T + 6;
        int top = invTop + 12;
        if (inv < 9) return new int[]{mainX + inv * T, top + 3 * T + 4};
        if (inv < 36) return new int[]{mainX + ((inv - 9) % 9) * T, top + ((inv - 9) / 9) * T};
        if (inv < 40) return new int[]{invX, top + (39 - inv) * T};
        if (inv == 40) return new int[]{mainX + 9 * T + 6, top};
        return new int[]{mainX + 9 * T + 6, top + 3 * T + 4}; // 41 — корзина
    }

    private int invAt(double mx, double my) {
        for (int i = 0; i <= 41; i++) {
            int[] p = slotXY(i);
            if (mx >= p[0] && mx < p[0] + T && my >= p[1] && my < p[1] + T) return i;
        }
        return -1;
    }

    private int gridAt(double mx, double my) {
        if (tab.kind() == Kind.KITS) return -1;
        if (mx < gx || mx >= gx + cols * T || my < gy || my >= gy + rows * T) return -1;
        int c = (int) ((mx - gx) / T), r = (int) ((my - gy) / T);
        int idx = (scroll + r) * cols + c;
        return idx < items.size() ? idx : -1;
    }

    // ------------------------------------------------------------------ отрисовка

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        int accent = Theme.accent();
        int grid = gridAt(mx, my);
        int inv = invAt(mx, my);
        if (grid >= 0) focus = items.get(grid);
        else if (inv >= 0 && inv <= 40 && !player.getInventory().getItem(inv).isEmpty()) focus = player.getInventory().getItem(inv);

        renderSidebar(g, mx, my, accent);
        // центр
        Draw.panel(g, cx - 3, py, cw + 6, ph, 0xE00C1015, Draw.alpha(accent, 0x70));
        g.fill(cx, py, cx + cw, py + 1, Draw.alpha(accent, 0xE0));
        g.drawString(font, tab.name(), cx + 2, py + 3, Draw.alpha(accent, 0xFF), true);
        String count = tab.kind() == Kind.KITS ? "" : Component.translatable("archud.creative.count", items.size()).getString();
        Draw.small(g, font, count, cx + cw - font.width(count) * 0.65F - 2, py + 4, Draw.TEXT_DIM, 0.65F);
        if (tab.kind() == Kind.KITS) renderKits(g, mx, my, accent);
        else renderGrid(g, mx, my, grid, accent);
        renderInventory(g, mx, my, inv, accent);
        renderInfo(g, accent);

        super.render(g, mx, my, pt);

        // предмет на курсоре / подсказка
        if (!carried.isEmpty()) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 232);
            g.renderItem(carried, mx - 8, my - 8);
            g.renderItemDecorations(font, carried, mx - 8, my - 8);
            g.pose().popPose();
        } else if (grid >= 0) {
            g.renderTooltip(font, items.get(grid), mx, my);
        } else if (inv >= 0 && inv <= 40 && !player.getInventory().getItem(inv).isEmpty()) {
            g.renderTooltip(font, player.getInventory().getItem(inv), mx, my);
        } else if (inv == 41) {
            g.renderTooltip(font, Component.translatable("archud.creative.trash"), mx, my);
        } else {
            Tab t = tabAt(mx, my);
            if (t != null) g.renderTooltip(font, t.name(), mx, my);
        }
    }

    private int sideRows() {
        return (ph - 26) / 18;
    }

    private Tab tabAt(double mx, double my) {
        if (mx < sx || mx >= sx + SIDE_W || my < py + 22) return null;
        int r = (int) ((my - py - 22) / 18);
        if (r >= sideRows()) return null;
        int idx = sideScroll + r;
        return idx < tabs.size() ? tabs.get(idx) : null;
    }

    private void renderSidebar(GuiGraphics g, int mx, int my, int accent) {
        Draw.panel(g, sx, py, SIDE_W, ph, 0xE00C1015, Draw.alpha(accent, 0x70));
        g.fill(sx + 3, py, sx + SIDE_W - 3, py + 1, Draw.alpha(accent, 0xE0));
        Draw.small(g, font, Component.translatable("archud.creative.title").getString(), sx + 6, py + 6, Draw.TEXT_DIM, 0.75F);
        int rowsVis = sideRows();
        sideScroll = Mth.clamp(sideScroll, 0, Math.max(0, tabs.size() - rowsVis));
        for (int r = 0; r < rowsVis && sideScroll + r < tabs.size(); r++) {
            Tab t = tabs.get(sideScroll + r);
            int ty = py + 22 + r * 18;
            boolean sel = t == tab;
            boolean hov = mx >= sx && mx < sx + SIDE_W && my >= ty && my < ty + 18;
            if (sel) {
                Draw.hGradient(g, sx + 2, ty, sx + SIDE_W - 2, ty + 17, Draw.alpha(accent, 0x55), Draw.alpha(accent, 0x08));
                g.fill(sx + 2, ty + 1, sx + 4, ty + 16, accent);
            } else if (hov) g.fill(sx + 2, ty, sx + SIDE_W - 2, ty + 17, 0x18FFFFFF);
            g.renderItem(t.icon(), sx + 7, ty + 1);
            String name = t.name().getString();
            int max = (int) ((SIDE_W - 30) / 0.8F);
            if (font.width(name) > max) name = font.plainSubstrByWidth(name, max - font.width("…")) + "…";
            Draw.small(g, font, name, sx + 27, ty + 5, sel ? 0xFFFFFFFF : hov ? Draw.TEXT : 0xFFB4C0CE, 0.8F);
            if (sideScroll + r == 3) g.fill(sx + 6, ty + 17, sx + SIDE_W - 6, ty + 18, 0x30FFFFFF);
        }
        if (tabs.size() > rowsVis) {
            int trackH = rowsVis * 18;
            int th = Math.max(12, trackH * rowsVis / tabs.size());
            int ty = py + 22 + (trackH - th) * sideScroll / Math.max(1, tabs.size() - rowsVis);
            g.fill(sx + SIDE_W - 3, ty, sx + SIDE_W - 1, ty + th, Draw.alpha(accent, 0xA0));
        }
    }

    private void renderGrid(GuiGraphics g, int mx, int my, int hovered, int accent) {
        if (items.isEmpty()) {
            String key = tab.kind() == Kind.FAVORITES && search.getValue().isEmpty() ? "archud.creative.empty_fav"
                    : tab.kind() == Kind.RECENT && search.getValue().isEmpty() ? "archud.creative.empty_recent" : "archud.creative.nothing";
            Draw.smallCentered(g, font, Component.translatable(key).getString(), cx + cw / 2F, gy + rows * T / 2F - 4, Draw.TEXT_DIM, 0.75F);
            return;
        }
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int idx = (scroll + r) * cols + c;
                if (idx >= items.size()) break;
                int x = gx + c * T, y = gy + r * T;
                boolean hov = idx == hovered;
                g.fill(x + 1, y + 1, x + T - 1, y + T - 1, hov ? Draw.alpha(accent, 0x50) : 0x40000000);
                if (hov) Draw.outline(g, x, y, T, T, Draw.alpha(accent, 0xFF));
                ItemStack s = items.get(idx);
                g.renderItem(s, x + 1, y + 1);
                g.renderItemDecorations(font, s, x + 1, y + 1);
                if (isFavorite(s) && tab.kind() != Kind.FAVORITES) g.fill(x + T - 4, y + 2, x + T - 2, y + 4, 0xFFFFD54F);
            }
        }
        // полоса прокрутки
        int max = maxScroll();
        int bx = gx + cols * T + 3, top = gy, h = rows * T;
        g.fill(bx, top, bx + 3, top + h, 0x30FFFFFF);
        if (max > 0) {
            int th = Math.max(14, h * rows / Math.max(rows, totalRows()));
            int ty = top + (h - th) * scroll / max;
            g.fill(bx, ty, bx + 3, ty + th, Draw.alpha(accent, draggingBar ? 0xFF : 0xB0));
        }
    }

    private void renderKits(GuiGraphics g, int mx, int my, int accent) {
        var mgr = Minecraft.getInstance().getHotbarManager();
        int rowH = Math.max(T + 2, Math.min(24, (invTop - 6 - gy) / 9));
        for (int k = 0; k < 9; k++) {
            Hotbar hb = mgr.get(k);
            int y = gy + k * rowH;
            Draw.small(g, font, Component.translatable("archud.creative.kit", k + 1).getString(), cx + 2, y + 6, Draw.TEXT_DIM, 0.7F);
            int x0 = cx + 46;
            for (int i = 0; i < 9; i++) {
                int x = x0 + i * T;
                g.fill(x + 1, y + 1, x + T - 1, y + T - 1, 0x40000000);
                ItemStack s = hb.get(i);
                if (!s.isEmpty()) {
                    g.renderItem(s, x + 1, y + 1);
                    g.renderItemDecorations(font, s, x + 1, y + 1);
                }
            }
            int bx = x0 + 9 * T + 6;
            kitButton(g, bx, y + 2, Component.translatable("archud.creative.kit_save"), mx, my, accent);
            kitButton(g, bx + 52, y + 2, Component.translatable("archud.creative.kit_load"), mx, my, accent);
        }
    }

    private void kitButton(GuiGraphics g, int x, int y, Component label, int mx, int my, int accent) {
        boolean hov = mx >= x && mx < x + 48 && my >= y && my < y + 14;
        Draw.panel(g, x, y, 48, 14, hov ? Draw.alpha(accent, 0x50) : 0xE0141B24, Draw.alpha(accent, hov ? 0xFF : 0x70));
        Draw.smallCentered(g, font, label.getString(), x + 24, y + 4, 0xFFFFFFFF, 0.65F);
    }

    private void renderInventory(GuiGraphics g, int mx, int my, int hovered, int accent) {
        g.fill(cx + 4, invTop - 3, cx + cw - 4, invTop - 2, Draw.alpha(accent, 0x40));
        Draw.small(g, font, Component.translatable("container.inventory").getString(), invX, invTop + 2, Draw.TEXT_DIM, 0.7F);
        Inventory inv = player.getInventory();
        for (int i = 0; i <= 41; i++) {
            int[] p = slotXY(i);
            int x = p[0], y = p[1];
            boolean hov = i == hovered;
            boolean sel = i == inv.selected;
            int bg = i == 41 ? 0x50FF5252 : sel ? Draw.alpha(accent, 0x40) : 0x60000000;
            g.fill(x + 1, y + 1, x + T - 1, y + T - 1, hov ? Draw.alpha(i == 41 ? 0xFFFF5252 : accent, 0x60) : bg);
            Draw.outline(g, x, y, T, T, hov || sel ? Draw.alpha(i == 41 ? 0xFFFF5252 : accent, 0xE0) : 0x30FFFFFF);
            if (i == 41) {
                // корзина: крестик
                for (int d = 0; d < 8; d++) {
                    g.fill(x + 5 + d, y + 5 + d, x + 6 + d, y + 6 + d, 0xFFFF8A80);
                    g.fill(x + 12 - d, y + 5 + d, x + 13 - d, y + 6 + d, 0xFFFF8A80);
                }
                continue;
            }
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() && i >= 36) {
                // силуэты пустых слотов брони и второй руки (ванильные спрайты)
                ResourceLocation icon = switch (i) {
                    case 39 -> net.minecraft.world.inventory.InventoryMenu.EMPTY_ARMOR_SLOT_HELMET;
                    case 38 -> net.minecraft.world.inventory.InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE;
                    case 37 -> net.minecraft.world.inventory.InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS;
                    case 36 -> net.minecraft.world.inventory.InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS;
                    default -> net.minecraft.world.inventory.InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD;
                };
                g.setColor(1F, 1F, 1F, 0.45F);
                com.mojang.blaze3d.systems.RenderSystem.enableBlend();
                g.blit(x + 1, y + 1, 0, 16, 16, Minecraft.getInstance().getTextureAtlas(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS).apply(icon));
                g.setColor(1F, 1F, 1F, 1F);
            }
            g.renderItem(s, x + 1, y + 1);
            g.renderItemDecorations(font, s, x + 1, y + 1);
        }
    }

    private void renderInfo(GuiGraphics g, int accent) {
        Draw.panel(g, ix, py, INFO_W, ph, 0xE00C1015, Draw.alpha(accent, 0x70));
        g.fill(ix + 3, py, ix + INFO_W - 3, py + 1, Draw.alpha(accent, 0xE0));
        boolean has = !focus.isEmpty();
        give1Btn.active = giveStackBtn.active = favBtn.active = has;
        give1Btn.visible = giveStackBtn.visible = favBtn.visible = has;
        if (!has) {
            Draw.smallCentered(g, font, Component.translatable("archud.creative.hover_hint").getString(), ix + INFO_W / 2F, py + 60, Draw.TEXT_DIM, 0.65F);
        } else {
            // крупная иконка
            Draw.panel(g, ix + INFO_W / 2 - 20, py + 8, 40, 40, 0xC0060A10, Draw.alpha(accent, 0x60));
            g.pose().pushPose();
            g.pose().translate(ix + INFO_W / 2F - 16, py + 12, 0);
            g.pose().scale(2F, 2F, 1F);
            g.renderItem(focus, 0, 0);
            g.pose().popPose();
            int y = py + 52;
            for (var line : font.split(focus.getHoverName(), INFO_W - 12)) {
                g.drawString(font, line, ix + INFO_W / 2 - font.width(line) / 2, y, 0xFFFFFFFF, true);
                y += 10;
                if (y > py + 72) break;
            }
            ResourceLocation id = id(focus);
            Draw.smallCentered(g, font, id.toString(), ix + INFO_W / 2F, y + 1, Draw.TEXT_DIM, 0.55F);
            String mod = ArcHud.platform().modName(id.getNamespace());
            Draw.smallCentered(g, font, mod, ix + INFO_W / 2F, y + 8, Draw.alpha(accent, 0xFF), 0.6F);
            favBtn.setMessage(Component.translatable(isFavorite(focus) ? "archud.creative.unfav" : "archud.creative.fav"));
            // характеристики
            int sy = py + 172;
            stat(g, sy, "archud.creative.stack", Integer.toString(focus.getMaxStackSize()));
            stat(g, sy + 9, "archud.creative.rarity", focus.getRarity().name().charAt(0) + focus.getRarity().name().substring(1).toLowerCase(Locale.ROOT));
            stat(g, sy + 18, "archud.creative.tags", Long.toString(focus.getTags().count()));
            if (focus.isDamageableItem()) stat(g, sy + 27, "archud.creative.durability", Integer.toString(focus.getMaxDamage()));
            else if (focus.isEdible()) stat(g, sy + 27, "archud.creative.food",
                    Integer.toString(focus.getItem().getFoodProperties() == null ? 0 : focus.getItem().getFoodProperties().getNutrition()));
        }
        // подсказка по управлению
        int hy = py + ph - 52;
        g.fill(ix + 6, hy - 4, ix + INFO_W - 6, hy - 3, 0x30FFFFFF);
        for (String k : new String[]{"archud.creative.help1", "archud.creative.help2", "archud.creative.help3", "archud.creative.help4", "archud.creative.help5"}) {
            Draw.small(g, font, Component.translatable(k).getString(), ix + 6, hy, 0xFF6A7682, 0.5F);
            hy += 9;
        }
    }

    private void stat(GuiGraphics g, int y, String key, String value) {
        Draw.small(g, font, Component.translatable(key).getString(), ix + 8, y, Draw.TEXT_DIM, 0.6F);
        Draw.small(g, font, value, ix + INFO_W - 8 - font.width(value) * 0.6F, y, Draw.TEXT, 0.6F);
    }

    // ------------------------------------------------------------------ мышь

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (search.isFocused() && !(mx >= search.getX() && mx < search.getX() + search.getWidth() && my >= search.getY() && my < search.getY() + search.getHeight())) {
            search.setFocused(false);
        }
        Tab t = tabAt(mx, my);
        if (t != null) {
            selectTab(t, true);
            return true;
        }
        // полоса прокрутки
        int bx = gx + cols * T + 2;
        if (tab.kind() != Kind.KITS && mx >= bx && mx < bx + 5 && my >= gy && my < gy + rows * T) {
            draggingBar = true;
            dragBar(my);
            return true;
        }
        if (tab.kind() == Kind.KITS && clickKits(mx, my)) return true;
        int grid = gridAt(mx, my);
        if (grid >= 0 || (mx >= gx && mx < gx + cols * T && my >= gy && my < gy + rows * T)) {
            clickGrid(grid, button);
            return true;
        }
        int inv = invAt(mx, my);
        if (inv >= 0) {
            clickInventory(inv, button);
            return true;
        }
        // клик мимо окон с предметом на курсоре — выбросить
        boolean outside = mx < px || mx >= px + pw || my < py || my >= py + ph;
        if (outside && !carried.isEmpty()) {
            ItemStack drop = button == 1 ? carried.split(1) : carried;
            Minecraft.getInstance().gameMode.handleCreativeModeItemDrop(drop.copy());
            if (button != 1) carried = ItemStack.EMPTY;
            return true;
        }
        return false;
    }

    private void clickGrid(int idx, int button) {
        if (!carried.isEmpty()) {
            // предмет на курсоре над сеткой: тот же — +1, иначе убрать с курсора
            if (idx >= 0 && button == 0 && ItemStack.isSameItemSameTags(carried, items.get(idx)) && carried.getCount() < carried.getMaxStackSize()) {
                carried.grow(1);
            } else carried = ItemStack.EMPTY;
            return;
        }
        if (idx < 0) return;
        ItemStack s = items.get(idx);
        if (button == 0 && hasShiftDown()) {
            give(s, s.getMaxStackSize());
            return;
        }
        carried = s.copyWithCount(button == 0 ? 1 : s.getMaxStackSize());
        remember(s);
    }

    private void clickInventory(int i, int button) {
        Inventory inv = player.getInventory();
        if (i == 41) { // корзина
            if (!carried.isEmpty()) carried = ItemStack.EMPTY;
            else if (hasShiftDown()) {
                for (int k = 0; k <= 40; k++) if (!inv.getItem(k).isEmpty()) setInv(k, ItemStack.EMPTY);
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.LAVA_EXTINGUISH, 1.5F, 0.3F));
            }
            return;
        }
        ItemStack cur = inv.getItem(i);
        if (button == 2) { // СКМ — копия стака на курсор
            if (!cur.isEmpty() && carried.isEmpty()) carried = cur.copyWithCount(cur.getMaxStackSize());
            return;
        }
        if (carried.isEmpty()) {
            if (cur.isEmpty()) return;
            if (button == 0 && hasShiftDown()) {
                // быстрый перенос между хотбаром и основным инвентарём
                int from = i < 9 ? 9 : 0, to = i < 9 ? 36 : 9;
                for (int k = from; k < to; k++) {
                    if (inv.getItem(k).isEmpty()) {
                        setInv(k, cur.copy());
                        setInv(i, ItemStack.EMPTY);
                        break;
                    }
                }
                return;
            }
            if (button == 1) {
                int half = (cur.getCount() + 1) / 2;
                carried = cur.copyWithCount(half);
                setInv(i, cur.getCount() - half <= 0 ? ItemStack.EMPTY : cur.copyWithCount(cur.getCount() - half));
            } else {
                carried = cur.copy();
                setInv(i, ItemStack.EMPTY);
            }
            return;
        }
        if (!fits(i, carried)) return;
        if (button == 1) { // по одному
            if (cur.isEmpty()) {
                setInv(i, carried.copyWithCount(1));
                carried.shrink(1);
            } else if (ItemStack.isSameItemSameTags(cur, carried) && cur.getCount() < cur.getMaxStackSize()) {
                setInv(i, cur.copyWithCount(cur.getCount() + 1));
                carried.shrink(1);
            }
            if (carried.isEmpty()) carried = ItemStack.EMPTY;
            return;
        }
        if (cur.isEmpty()) {
            setInv(i, carried);
            carried = ItemStack.EMPTY;
        } else if (ItemStack.isSameItemSameTags(cur, carried)) {
            int add = Math.min(carried.getCount(), cur.getMaxStackSize() - cur.getCount());
            setInv(i, cur.copyWithCount(cur.getCount() + add));
            carried.shrink(add);
            if (carried.isEmpty()) carried = ItemStack.EMPTY;
        } else {
            ItemStack old = cur.copy();
            setInv(i, carried);
            carried = old;
        }
    }

    private boolean clickKits(double mx, double my) {
        var mgr = Minecraft.getInstance().getHotbarManager();
        int rowH = Math.max(T + 2, Math.min(24, (invTop - 6 - gy) / 9));
        int x0 = cx + 46, bx = x0 + 9 * T + 6;
        for (int k = 0; k < 9; k++) {
            int y = gy + k * rowH + 2;
            if (my < y || my >= y + 14) continue;
            Hotbar hb = mgr.get(k);
            if (mx >= bx && mx < bx + 48) {
                for (int i = 0; i < 9; i++) hb.set(i, player.getInventory().getItem(i).copy());
                mgr.save();
                click();
                player.displayClientMessage(Component.translatable("archud.creative.kit_saved", k + 1), true);
                return true;
            }
            if (mx >= bx + 52 && mx < bx + 100) {
                for (int i = 0; i < 9; i++) setInv(i, hb.get(i).copy());
                click();
                return true;
            }
            // клик по предмету набора — взять его
            if (mx >= x0 && mx < x0 + 9 * T) {
                ItemStack s = hb.get((int) ((mx - x0) / T));
                if (!s.isEmpty() && carried.isEmpty()) carried = s.copy();
                return true;
            }
        }
        return false;
    }

    private void dragBar(double my) {
        int max = maxScroll();
        if (max <= 0) return;
        float f = (float) ((my - gy) / (rows * T));
        scroll = Mth.clamp(Math.round(f * max), 0, max);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (draggingBar) {
            dragBar(my);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        draggingBar = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (mx >= sx && mx < sx + SIDE_W) {
            sideScroll = Mth.clamp(sideScroll - (int) Math.signum(delta), 0, Math.max(0, tabs.size() - sideRows()));
            return true;
        }
        if (mx >= cx && mx < cx + cw && my < invTop) {
            scroll = Mth.clamp(scroll - (int) Math.signum(delta) * (hasShiftDown() ? rows : 1), 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    // ------------------------------------------------------------------ клавиатура

    private ItemStack hoveredStack(double mx, double my) {
        int grid = gridAt(mx, my);
        if (grid >= 0) return items.get(grid);
        int inv = invAt(mx, my);
        if (inv >= 0 && inv <= 40) return player.getInventory().getItem(inv);
        return ItemStack.EMPTY;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        Minecraft mc = Minecraft.getInstance();
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (!search.isFocused()) {
            double mx = mc.mouseHandler.xpos() * width / mc.getWindow().getScreenWidth();
            double my = mc.mouseHandler.ypos() * height / mc.getWindow().getScreenHeight();
            ItemStack hov = hoveredStack(mx, my);
            int grid = gridAt(mx, my), inv = invAt(mx, my);
            for (int n = 0; n < 9; n++) {
                if (!mc.options.keyHotbarSlots[n].matches(key, scan)) continue;
                if (grid >= 0) {
                    setInv(n, hov.copyWithCount(hov.getMaxStackSize()));
                    remember(hov);
                } else if (inv >= 0 && inv <= 40 && inv != n) {
                    ItemStack a = player.getInventory().getItem(inv).copy(), b = player.getInventory().getItem(n).copy();
                    if (fits(inv, b)) {
                        setInv(n, a);
                        setInv(inv, b);
                    }
                }
                return true;
            }
            if (mc.options.keyDrop.matches(key, scan) && !hov.isEmpty()) {
                if (grid >= 0) mc.gameMode.handleCreativeModeItemDrop(hov.copyWithCount(hasControlDown() ? hov.getMaxStackSize() : 1));
                else if (inv >= 0 && inv <= 40) {
                    ItemStack cur = player.getInventory().getItem(inv);
                    int n = hasControlDown() ? cur.getCount() : 1;
                    mc.gameMode.handleCreativeModeItemDrop(cur.copyWithCount(n));
                    setInv(inv, cur.getCount() - n <= 0 ? ItemStack.EMPTY : cur.copyWithCount(cur.getCount() - n));
                }
                return true;
            }
            if (key == GLFW.GLFW_KEY_F && !hov.isEmpty()) {
                toggleFavorite(hov);
                return true;
            }
            if (mc.options.keyInventory.matches(key, scan)) {
                onClose();
                return true;
            }
            if (key == GLFW.GLFW_KEY_SLASH || (key == GLFW.GLFW_KEY_F && hasControlDown())) {
                setFocused(search);
                search.setFocused(true);
                return true;
            }
        }
        if (search.isFocused() && key == GLFW.GLFW_KEY_ENTER) {
            search.setFocused(false);
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (!search.isFocused() && !Character.isWhitespace(c) && c != '/' && !Character.isDigit(c)
                && Character.toLowerCase(c) != 'q' && Character.toLowerCase(c) != 'f' && Character.toLowerCase(c) != 'e') {
            // начали печатать — сразу в поиск
            setFocused(search);
            search.setFocused(true);
        }
        return super.charTyped(c, mods);
    }

    // ------------------------------------------------------------------ жизненный цикл

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode == null || !mc.gameMode.hasInfiniteItems()) {
            mc.setScreen(new InventoryScreen(player));
            return;
        }
        search.tick();
    }

    @Override
    public void removed() {
        // предмет с курсора — в инвентарь, если некуда — на землю
        if (!carried.isEmpty() && Minecraft.getInstance().gameMode != null) {
            if (!give(carried, carried.getCount())) Minecraft.getInstance().gameMode.handleCreativeModeItemDrop(carried);
            carried = ItemStack.EMPTY;
        }
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void click() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }
}
