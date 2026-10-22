package com.arc4io.archud.client.screen;

import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Icons;
import com.arc4io.archud.client.hud.Theme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatType;
import net.minecraft.stats.Stats;
import net.minecraft.stats.StatsCounter;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.gui.screens.achievement.StatsUpdateListener;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Новая статистика: обзор (крупные карточки), общая статистика, предметы (таблица с сортировкой), мобы. Поиск.
 */
public class ArcStatsScreen extends ArcScreen implements StatsUpdateListener {
    private static final String[] TABS = {"archud.stats.overview", "stat.generalButton", "stat.itemsButton", "stat.mobsButton"};
    private final StatsCounter stats;
    private boolean loaded;
    private int tab;
    private int sortCol = 1;
    private EditBox search;
    private double scroll;
    private final List<Object[]> rows = new ArrayList<>();

    public ArcStatsScreen(Screen parent, StatsCounter stats) {
        super(Component.translatable("gui.stats"), parent);
        this.stats = stats;
    }

    @Override
    protected void init() {
        layout(600, height - 16);
        if (!loaded && minecraft.getConnection() != null) {
            minecraft.getConnection().send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.REQUEST_STATS));
        }
        int tw = 100;
        for (int i = 0; i < TABS.length; i++) {
            int t = i;
            addRenderableWidget(new ArcButton(px + 10 + i * (tw + 4), py + 26, tw, 18, Component.translatable(TABS[i]), b -> {
                tab = t;
                scroll = 0;
                refresh();
            }));
        }
        int sx = px + 10 + TABS.length * (tw + 4);
        search = addRenderableWidget(new EditBox(font, sx, py + 27, px + pw - 10 - sx, 16, Component.translatable("archud.controls.search")));
        search.setHint(Component.translatable("archud.controls.search"));
        search.setResponder(s -> refresh());
        addDone();
        refresh();
    }

    @Override
    public void onStatsUpdated() {
        loaded = true;
        refresh();
    }

    private int v(Stat<?> s) {
        return stats.getValue(s);
    }

    private <T> int typed(StatType<T> type, T value) {
        return type.contains(value) ? stats.getValue(type.get(value)) : 0;
    }

    private void refresh() {
        rows.clear();
        String q = search == null ? "" : search.getValue().toLowerCase(Locale.ROOT);
        if (tab == 1) {
            for (ResourceLocation id : BuiltInRegistries.CUSTOM_STAT) {
                Stat<ResourceLocation> st = Stats.CUSTOM.get(id);
                Component name = Component.translatable("stat." + id.toString().replace(':', '.'));
                if (!q.isEmpty() && !name.getString().toLowerCase(Locale.ROOT).contains(q)) continue;
                rows.add(new Object[]{name, st.format(v(st)), v(st)});
            }
            rows.sort(Comparator.comparing(r -> ((Component) r[0]).getString()));
        } else if (tab == 2) {
            for (Item item : BuiltInRegistries.ITEM) {
                int mined = item instanceof BlockItem bi ? typed(Stats.BLOCK_MINED, bi.getBlock()) : 0;
                int[] vals = {mined, typed(Stats.ITEM_CRAFTED, item), typed(Stats.ITEM_USED, item), typed(Stats.ITEM_BROKEN, item),
                        typed(Stats.ITEM_PICKED_UP, item), typed(Stats.ITEM_DROPPED, item)};
                int sum = 0;
                for (int x : vals) sum += x;
                if (sum == 0) continue;
                ItemStack stack = new ItemStack(item);
                if (!q.isEmpty() && !stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(q)) continue;
                rows.add(new Object[]{stack, vals});
            }
            int c = sortCol - 1;
            rows.sort(Comparator.comparingInt((Object[] r) -> -((int[]) r[1])[Math.max(0, Math.min(5, c))]));
        } else if (tab == 3) {
            for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
                int killed = typed(Stats.ENTITY_KILLED, type), by = typed(Stats.ENTITY_KILLED_BY, type);
                if (killed == 0 && by == 0) continue;
                Component name = type.getDescription();
                if (!q.isEmpty() && !name.getString().toLowerCase(Locale.ROOT).contains(q)) continue;
                rows.add(new Object[]{name, killed, by});
            }
            rows.sort(Comparator.comparingInt((Object[] r) -> -(int) r[1]));
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        frame(g);
        int accent = Theme.accent();
        int lx = px + 10, ly = py + 50, lw = pw - 20, lh = ph - 50 - 30;
        if (!loaded) {
            g.drawCenteredString(font, Component.translatable("multiplayer.downloadingStats"), px + pw / 2, ly + 30, Draw.TEXT_DIM);
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }
        if (tab == 0) overview(g, lx, ly, lw, accent);
        else {
            int rowH = tab == 2 ? 20 : 14;
            int header = tab == 1 ? 0 : 14;
            if (tab == 2) {
                String[] cols = {"stat_type.minecraft.mined", "stat_type.minecraft.crafted", "stat_type.minecraft.used",
                        "stat_type.minecraft.broken", "stat_type.minecraft.picked_up", "stat_type.minecraft.dropped"};
                int cw = (lw - 160) / 6;
                for (int i = 0; i < 6; i++) {
                    int cx = lx + 160 + i * cw;
                    String h = Component.translatable(cols[i]).getString();
                    Draw.small(g, font, h, cx, ly + 3, sortCol == i + 1 ? Draw.alpha(accent, 0xFF) : Draw.TEXT_DIM, 0.6F);
                }
            } else if (tab == 3) {
                Draw.small(g, font, Component.translatable("archud.stats.killed").getString(), lx + lw - 150, ly + 3, Draw.TEXT_DIM, 0.6F);
                Draw.small(g, font, Component.translatable("archud.stats.killed_by").getString(), lx + lw - 70, ly + 3, Draw.TEXT_DIM, 0.6F);
            }
            int top = ly + header;
            int h = lh - header;
            scroll = Mth.clamp(scroll, 0, Math.max(0, rows.size() * rowH - h));
            g.enableScissor(lx, top, lx + lw, top + h);
            int y = top - (int) scroll;
            for (int i = 0; i < rows.size(); i++, y += rowH) {
                if (y + rowH < top || y > top + h) continue;
                Object[] r = rows.get(i);
                if (i % 2 == 0) g.fill(lx, y, lx + lw, y + rowH, 0x14FFFFFF);
                if (tab == 1) {
                    g.drawString(font, (Component) r[0], lx + 4, y + 3, Draw.TEXT, true);
                    String val = (String) r[1];
                    g.drawString(font, val, lx + lw - 4 - font.width(val), y + 3, ((int) r[2]) > 0 ? Draw.alpha(accent, 0xFF) : Draw.TEXT_DIM, true);
                } else if (tab == 2) {
                    ItemStack st = (ItemStack) r[0];
                    g.renderItem(st, lx + 2, y + 2);
                    String n = st.getHoverName().getString();
                    if (font.width(n) > 134) n = font.plainSubstrByWidth(n, 130) + "…";
                    g.drawString(font, n, lx + 22, y + 6, Draw.TEXT, true);
                    int[] vals = (int[]) r[1];
                    int cw = (lw - 160) / 6;
                    for (int c = 0; c < 6; c++) {
                        String s = vals[c] == 0 ? "-" : Integer.toString(vals[c]);
                        g.drawString(font, s, lx + 160 + c * cw, y + 6, vals[c] == 0 ? 0xFF4A525C : Draw.TEXT, true);
                    }
                } else {
                    g.drawString(font, (Component) r[0], lx + 4, y + 3, Draw.TEXT, true);
                    g.drawString(font, Integer.toString((int) r[1]), lx + lw - 150, y + 3, 0xFF9CFF5A, true);
                    g.drawString(font, Integer.toString((int) r[2]), lx + lw - 70, y + 3, 0xFFFF8A80, true);
                }
            }
            g.disableScissor();
            if (rows.isEmpty()) g.drawCenteredString(font, Component.translatable("archud.adv.empty"), lx + lw / 2, top + 20, Draw.TEXT_DIM);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void overview(GuiGraphics g, int x, int y, int w, int accent) {
        record Card(String key, ResourceLocation stat, int icon, int color) {}
        Card[] cards = {
                new Card("stat.minecraft.play_time", Stats.PLAY_TIME, Icons.CLOCK, 0xFF4FC3F7),
                new Card("stat.minecraft.deaths", Stats.DEATHS, Icons.SKULL, 0xFFFF5252),
                new Card("stat.minecraft.mob_kills", Stats.MOB_KILLS, Icons.SWORD, 0xFFFFB74D),
                new Card("stat.minecraft.player_kills", Stats.PLAYER_KILLS, Icons.SWORD, 0xFFE040FB),
                new Card("stat.minecraft.walk_one_cm", Stats.WALK_ONE_CM, Icons.SPEED, 0xFF81C784),
                new Card("stat.minecraft.sprint_one_cm", Stats.SPRINT_ONE_CM, Icons.SPEED, 0xFF66BB6A),
                new Card("stat.minecraft.fly_one_cm", Stats.FLY_ONE_CM, Icons.JUMP, 0xFFB388FF),
                new Card("stat.minecraft.swim_one_cm", Stats.SWIM_ONE_CM, Icons.BUBBLE, 0xFF29B6F6),
                new Card("stat.minecraft.jump", Stats.JUMP, Icons.JUMP, 0xFFFFD54F),
                new Card("stat.minecraft.damage_dealt", Stats.DAMAGE_DEALT, Icons.SWORD, 0xFFFF8A65),
                new Card("stat.minecraft.damage_taken", Stats.DAMAGE_TAKEN, Icons.HEART, 0xFFFF5252),
                new Card("stat.minecraft.animals_bred", Stats.ANIMALS_BRED, Icons.HEART, 0xFFF48FB1),
        };
        int cols = 4, gap = 6;
        int cw = (w - (cols - 1) * gap) / cols, ch = 40;
        for (int i = 0; i < cards.length; i++) {
            Card c = cards[i];
            int cx = x + (i % cols) * (cw + gap), cy = y + (i / cols) * (ch + gap);
            Stat<ResourceLocation> st = Stats.CUSTOM.get(c.stat());
            Draw.panel(g, cx, cy, cw, ch, Draw.PANEL, Draw.alpha(c.color(), 0x80));
            g.fill(cx, cy + 2, cx + 2, cy + ch - 2, c.color());
            Draw.icon(g, c.icon(), cx + 6, cy + 6);
            String name = Component.translatable(c.key()).getString();
            Draw.small(g, font, font.plainSubstrByWidth(name, (int) ((cw - 22) / 0.7F)), cx + 18, cy + 7, Draw.TEXT_DIM, 0.7F);
            String val = st.format(v(st));
            g.pose().pushPose();
            g.pose().translate(cx + 8, cy + 20, 0);
            g.pose().scale(1.4F, 1.4F, 1);
            g.drawString(font, val, 0, 0, c.color(), true);
            g.pose().popPose();
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (tab == 2) {
            int lx = px + 10, ly = py + 50, lw = pw - 20;
            int cw = (lw - 160) / 6;
            if (my >= ly && my < ly + 12 && mx >= lx + 160) {
                sortCol = Math.min(6, (int) ((mx - lx - 160) / cw) + 1);
                refresh();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scroll -= delta * 30;
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return !loaded;
    }
}
