package com.arc4io.archud.client.screen;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Icons;
import com.arc4io.archud.client.hud.Theme;
import com.arc4io.archud.config.ArcConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.inventory.Hotbar;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Креатив-меню Arc HUD — своё окно, а не ванильное с новой текстурой.
 *
 * Вид: верхняя панель (название, поиск, вид «сетка / список», размер плиток, сортировка), под ней лента
 * вкладок-«чипов» с иконками (активная раскрывается с названием), рабочая область и «док» снизу — хотбар,
 * броня, вторая рука, корзина и кнопка, раскрывающая весь инвентарь поверх области. Справа — карточка
 * выбранного предмета.
 *
 * Вкладки: Главная (недавние, избранное, плитки категорий), Все предметы, Избранное, Недавние, Зачарования
 * (книги на максимальный уровень и полностью зачарованное снаряжение), Зелья (обычные, взрывные, туманные,
 * стрелы), Головы (голова любого игрока по нику и всех игроков на сервере), Оператор (командные блоки,
 * барьер, свет 0–15, отладочная палка и т.п.), Моды (предметы по модам), Мастерская (название, описание,
 * количество, неразрушимость, свечение, скрытие характеристик), Наборы (хотбары) и все категории игры и модов.
 */
public class ArcCreativeScreen extends Screen implements ScreenFit.Fits {
    private enum Kind { HOME, ALL, FAVORITES, RECENT, ENCHANTS, POTIONS, HEADS, OPERATOR, MODS, MOD, WORKSHOP, KITS, CATEGORY }

    private record Tab(Kind kind, CreativeModeTab tab, Component name, ItemStack icon, String mod) {}

    /** Область, по которой можно кликнуть (собирается при каждой отрисовке). */
    private record Hit(int x, int y, int w, int h, ItemStack stack, Runnable action, Component tip) {
        boolean in(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private static final int[] TILE = {18, 22, 28};
    private static final int S = 20;                 // ячейка дока
    private static final int INFO_W = 128;
    private static final int RECENT_MAX = 45;
    private static final List<ItemStack> RECENT = new ArrayList<>();
    private static String lastTab = "HOME";
    private static int sortMode, tileIdx = 1;
    private static boolean listView, invOpen;
    // мастерская
    private static ItemStack work = ItemStack.EMPTY;
    private static int workCount = 1;
    private static boolean workUnbreakable, workGlint, workHide;
    private static String workName = "", workLore = "";

    private final LocalPlayer player;
    private final List<Tab> tabs = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private final Map<ItemStack, String> haystack = new IdentityHashMap<>();
    private Tab tab;
    private List<ItemStack> items = List.of();
    private EditBox search, nickBox, nameBox, loreBox;
    private ArcButton give1Btn, giveStackBtn, favBtn, toWorkBtn;
    private ItemStack carried = ItemStack.EMPTY;
    private ItemStack focus = ItemStack.EMPTY;
    private int scroll, maxScroll, stripScroll;
    private boolean draggingBar;
    private int barX, barY, barH;

    // геометрия
    private int left, right, top, bottom;   // рабочая область
    private int stripY, dockTop, infoX;

    public ArcCreativeScreen(LocalPlayer player) {
        super(Component.translatable("archud.creative.title"));
        this.player = player;
    }

    // ================================================================== инициализация

    @Override
    protected void init() {
        ScreenFit.ensure(this, 480, 280);
        Minecraft mc = Minecraft.getInstance();
        boolean op = player.canUseGameMasterBlocks() && mc.options.operatorItemsTab().get();
        CreativeModeTabs.tryRebuildTabContents(player.connection.enabledFeatures(), op, player.level().registryAccess());
        if (tabs.isEmpty()) buildTabs();
        if (tab == null) tab = tabs.stream().filter(t -> key(t).equals(lastTab)).findFirst().orElse(tabs.get(0));

        stripY = 34;
        dockTop = height - 6 - (S + 10);
        infoX = width - 6 - INFO_W;
        left = 10;
        right = infoX - 6;
        top = stripY + 28;
        bottom = dockTop - 6;

        // верхняя панель
        String prev = search != null ? search.getValue() : "";
        int sw = Math.min(260, width - 400);
        search = new EditBox(font, width / 2 - sw / 2, 11, sw, 14, Component.translatable("archud.creative.search"));
        search.setHint(Component.translatable("archud.creative.search").withStyle(s -> s.withColor(0x6A7380)));
        search.setMaxLength(64);
        search.setValue(prev);
        search.setResponder(s -> {
            if (!s.isEmpty() && !hasItemList(tab.kind()) && tab.kind() != Kind.MODS) {
                selectTab(tabs.get(1), false); // с Главной — во «Все»
                setFocused(search);
                search.setFocused(true);
                search.moveCursorToEnd();
                return;
            }
            scroll = 0;
            refresh();
        });
        addRenderableWidget(search);
        int bx = width - 8;
        bx -= 62;
        addRenderableWidget(new ArcButton(bx, 10, 62, 16, Component.translatable("archud.creative.sort." + sortMode), b -> {
            sortMode = (sortMode + 1) % 3;
            b.setMessage(Component.translatable("archud.creative.sort." + sortMode));
            refresh();
        }));
        bx -= 22;
        addRenderableWidget(new ArcButton(bx, 10, 20, 16, Component.literal(new String[]{"S", "M", "L"}[tileIdx]), b -> {
            tileIdx = (tileIdx + 1) % TILE.length;
            b.setMessage(Component.literal(new String[]{"S", "M", "L"}[tileIdx]));
            scroll = 0;
        }));
        bx -= 52;
        addRenderableWidget(new ArcButton(bx, 10, 50, 16, Component.translatable(listView ? "archud.creative.view.list" : "archud.creative.view.grid"), b -> {
            listView = !listView;
            b.setMessage(Component.translatable(listView ? "archud.creative.view.list" : "archud.creative.view.grid"));
            scroll = 0;
        }));

        // карточка предмета
        int iy = top + 118;
        give1Btn = addRenderableWidget(new ArcButton(infoX + 6, iy, INFO_W - 12, 15, Component.translatable("archud.creative.give_one"), b -> give(focus, 1)));
        giveStackBtn = addRenderableWidget(new ArcButton(infoX + 6, iy + 18, INFO_W - 12, 15, Component.translatable("archud.creative.give_stack"),
                b -> give(focus, focus.getMaxStackSize())));
        favBtn = addRenderableWidget(new ArcButton(infoX + 6, iy + 36, INFO_W - 12, 15, Component.empty(), b -> toggleFavorite(focus))).icon(Icons.STAR);
        toWorkBtn = addRenderableWidget(new ArcButton(infoX + 6, iy + 54, INFO_W - 12, 15, Component.translatable("archud.creative.to_workshop"), b -> {
            loadWork(focus);
            selectTab(tabs.stream().filter(t -> t.kind() == Kind.WORKSHOP).findFirst().orElse(tab), true);
        }));

        // поля вкладок
        if (tab.kind() == Kind.HEADS) {
            nickBox = new EditBox(font, left + 4, top + 4, 140, 14, Component.translatable("archud.creative.nick"));
            nickBox.setHint(Component.translatable("archud.creative.nick").withStyle(s -> s.withColor(0x6A7380)));
            nickBox.setMaxLength(16);
            addRenderableWidget(nickBox);
            addRenderableWidget(new ArcButton(left + 148, top + 3, 80, 16, Component.translatable("archud.creative.head_give"), b -> {
                String n = nickBox.getValue().trim();
                if (n.isEmpty()) return;
                ItemStack h = head(n);
                give(h, 1);
                List<String> saved = ArcConfig.get().creativeHeads;
                saved.remove(n);
                saved.add(0, n);
                while (saved.size() > 36) saved.remove(saved.size() - 1);
                ArcConfig.save();
                refresh();
            }));
        }
        if (tab.kind() == Kind.WORKSHOP) {
            int fx = left + 150, fw = Math.max(120, right - fx - 10);
            nameBox = new EditBox(font, fx, top + 20, fw, 14, Component.translatable("archud.creative.w_name"));
            nameBox.setHint(Component.translatable("archud.creative.w_name").withStyle(s -> s.withColor(0x6A7380)));
            nameBox.setMaxLength(50);
            nameBox.setValue(workName);
            nameBox.setResponder(s -> workName = s);
            addRenderableWidget(nameBox);
            loreBox = new EditBox(font, fx, top + 52, fw, 14, Component.translatable("archud.creative.w_lore"));
            loreBox.setHint(Component.translatable("archud.creative.w_lore").withStyle(s -> s.withColor(0x6A7380)));
            loreBox.setMaxLength(80);
            loreBox.setValue(workLore);
            loreBox.setResponder(s -> workLore = s);
            addRenderableWidget(loreBox);
            int ty = top + 96;
            addRenderableWidget(toggle(fx, ty, fw, "archud.creative.w_unbreakable", () -> workUnbreakable, v -> workUnbreakable = v));
            addRenderableWidget(toggle(fx, ty + 18, fw, "archud.creative.w_glint", () -> workGlint, v -> workGlint = v));
            addRenderableWidget(toggle(fx, ty + 36, fw, "archud.creative.w_hide", () -> workHide, v -> workHide = v));
            addRenderableWidget(new ArcButton(fx, ty + 60, (fw - 4) / 2, 16, Component.translatable("archud.creative.w_give"), b -> {
                if (!work.isEmpty()) give(buildWork(), workCount);
            }).icon(Icons.STAR));
            addRenderableWidget(new ArcButton(fx + (fw - 4) / 2 + 4, ty + 60, (fw - 4) / 2, 16, Component.translatable("archud.creative.w_reset"), b -> {
                work = ItemStack.EMPTY;
                workCount = 1;
                workUnbreakable = workGlint = workHide = false;
                workName = workLore = "";
                rebuildWidgets();
            }).danger());
        }
        refresh();
    }

    private static Component toggleLabel(String key, boolean on) {
        return Component.translatable(key).append(": ").append(Component.translatable(on ? "options.on" : "options.off"));
    }

    private ArcButton toggle(int x, int y, int w, String key, java.util.function.BooleanSupplier get, java.util.function.Consumer<Boolean> set) {
        return new ArcButton(x, y, w, 16, toggleLabel(key, get.getAsBoolean()), btn -> {
            set.accept(!get.getAsBoolean());
            btn.setMessage(toggleLabel(key, get.getAsBoolean()));
        });
    }

    private void buildTabs() {
        tabs.clear();
        tabs.add(special(Kind.HOME, "home", Items.BEACON));
        tabs.add(special(Kind.ALL, "all", Items.COMPASS));
        tabs.add(special(Kind.FAVORITES, "favorites", Items.NETHER_STAR));
        tabs.add(special(Kind.RECENT, "recent", Items.CLOCK));
        tabs.add(special(Kind.ENCHANTS, "enchants", Items.ENCHANTED_BOOK));
        tabs.add(special(Kind.POTIONS, "potions", Items.DRAGON_BREATH));
        tabs.add(special(Kind.HEADS, "heads", Items.PLAYER_HEAD));
        tabs.add(special(Kind.OPERATOR, "operator", Items.COMMAND_BLOCK));
        tabs.add(special(Kind.MODS, "mods", Items.KNOWLEDGE_BOOK));
        tabs.add(special(Kind.WORKSHOP, "workshop", Items.ANVIL));
        tabs.add(special(Kind.KITS, "kits", Items.BUNDLE));
        for (CreativeModeTab t : CreativeModeTabs.tabs()) {
            if (t.getType() != CreativeModeTab.Type.CATEGORY || !t.shouldDisplay()) continue;
            tabs.add(new Tab(Kind.CATEGORY, t, t.getDisplayName(), t.getIconItem(), null));
        }
    }

    private static Tab special(Kind k, String key, Item icon) {
        return new Tab(k, null, Component.translatable("archud.creative." + key), new ItemStack(icon), null);
    }

    private static String key(Tab t) {
        return t.kind() == Kind.CATEGORY ? "cat:" + t.name().getString() : t.kind() == Kind.MOD ? "mod:" + t.mod() : t.kind().name();
    }

    private static boolean hasItemList(Kind k) {
        return switch (k) {
            case ALL, FAVORITES, RECENT, ENCHANTS, POTIONS, HEADS, OPERATOR, MOD, CATEGORY -> true;
            default -> false;
        };
    }

    private void selectTab(Tab t, boolean sound) {
        if (sound && t != tab) click();
        boolean widgetsChange = tab == null || t.kind() != tab.kind() || tab.kind() == Kind.HEADS || tab.kind() == Kind.WORKSHOP;
        tab = t;
        lastTab = key(t);
        scroll = 0;
        if (widgetsChange) rebuildWidgets();
        else refresh();
    }

    private void openMod(String ns) {
        selectTab(new Tab(Kind.MOD, null, Component.literal(ArcHud.platform().modName(ns)), modIcon(ns), ns), true);
    }

    // ================================================================== содержимое

    private List<ItemStack> source() {
        return switch (tab.kind()) {
            case ALL -> new ArrayList<>(CreativeModeTabs.searchTab().getDisplayItems());
            case FAVORITES -> favorites();
            case RECENT -> new ArrayList<>(RECENT);
            case ENCHANTS -> enchantItems();
            case POTIONS -> potionItems();
            case HEADS -> headItems();
            case OPERATOR -> operatorItems();
            case MOD -> {
                List<ItemStack> out = new ArrayList<>();
                for (ItemStack s : CreativeModeTabs.searchTab().getDisplayItems()) if (id(s).getNamespace().equals(tab.mod())) out.add(s);
                yield out;
            }
            case CATEGORY -> new ArrayList<>(tab.tab().getDisplayItems());
            default -> List.of();
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

    // ---------- наборы для новых вкладок

    private static List<ItemStack> enchantItems() {
        List<ItemStack> out = new ArrayList<>();
        // полностью зачарованное снаряжение
        Item[] gear = {Items.NETHERITE_SWORD, Items.NETHERITE_AXE, Items.NETHERITE_PICKAXE, Items.NETHERITE_SHOVEL, Items.NETHERITE_HOE,
                Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS,
                Items.BOW, Items.CROSSBOW, Items.TRIDENT, Items.FISHING_ROD, Items.ELYTRA, Items.SHIELD, Items.SHEARS};
        for (Item it : gear) if (it != null) out.add(maxEnchanted(it));
        // книги на максимальный уровень
        List<Enchantment> list = new ArrayList<>();
        for (Enchantment e : BuiltInRegistries.ENCHANTMENT) list.add(e);
        list.sort(Comparator.comparing(e -> Component.translatable(e.getDescriptionId()).getString()));
        for (Enchantment e : list) out.add(EnchantedBookItem.createForEnchantment(new EnchantmentInstance(e, e.getMaxLevel())));
        return out;
    }

    private static ItemStack maxEnchanted(Item item) {
        ItemStack s = new ItemStack(item);
        List<Enchantment> taken = new ArrayList<>();
        for (Enchantment e : BuiltInRegistries.ENCHANTMENT) {
            if (e.isCurse() || !e.canEnchant(s)) continue;
            boolean ok = true;
            for (Enchantment t : taken) if (!t.isCompatibleWith(e)) ok = false;
            if (!ok) continue;
            taken.add(e);
            s.enchant(e, e.getMaxLevel());
        }
        return s;
    }

    private static List<ItemStack> potionItems() {
        List<ItemStack> out = new ArrayList<>();
        Item[] kinds = {Items.POTION, Items.SPLASH_POTION, Items.LINGERING_POTION, Items.TIPPED_ARROW};
        for (Item k : kinds) {
            for (Potion p : BuiltInRegistries.POTION) {
                if (p == Potions.EMPTY) continue;
                out.add(PotionUtils.setPotion(new ItemStack(k), p));
            }
        }
        return out;
    }

    private List<ItemStack> headItems() {
        List<ItemStack> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        if (player.connection != null) {
            for (PlayerInfo info : player.connection.getOnlinePlayers()) {
                String n = info.getProfile().getName();
                if (seen.contains(n.toLowerCase(Locale.ROOT))) continue;
                seen.add(n.toLowerCase(Locale.ROOT));
                out.add(head(n));
            }
        }
        for (String n : ArcConfig.get().creativeHeads) {
            if (seen.contains(n.toLowerCase(Locale.ROOT))) continue;
            seen.add(n.toLowerCase(Locale.ROOT));
            out.add(head(n));
        }
        for (Item it : new Item[]{Items.SKELETON_SKULL, Items.WITHER_SKELETON_SKULL, Items.ZOMBIE_HEAD, Items.CREEPER_HEAD,
                Items.PIGLIN_HEAD, Items.DRAGON_HEAD, Items.PLAYER_HEAD}) out.add(new ItemStack(it));
        return out;
    }

    private static ItemStack head(String nick) {
        ItemStack h = new ItemStack(Items.PLAYER_HEAD);
        h.getOrCreateTag().putString("SkullOwner", nick);
        return h;
    }

    private static List<ItemStack> operatorItems() {
        List<ItemStack> out = new ArrayList<>();
        for (Item it : new Item[]{Items.COMMAND_BLOCK, Items.CHAIN_COMMAND_BLOCK, Items.REPEATING_COMMAND_BLOCK, Items.COMMAND_BLOCK_MINECART,
                Items.STRUCTURE_BLOCK, Items.STRUCTURE_VOID, Items.JIGSAW, Items.BARRIER, Items.DEBUG_STICK, Items.KNOWLEDGE_BOOK,
                Items.SPAWNER, Items.PETRIFIED_OAK_SLAB, Items.DRAGON_EGG, Items.BEDROCK, Items.END_PORTAL_FRAME, Items.REINFORCED_DEEPSLATE,
                Items.BUDDING_AMETHYST, Items.FARMLAND, Items.DIRT_PATH, Items.FROGSPAWN, Items.SUSPICIOUS_SAND, Items.SUSPICIOUS_GRAVEL,
                Items.WRITABLE_BOOK, Items.FILLED_MAP}) out.add(new ItemStack(it));
        for (int lvl = 0; lvl <= 15; lvl++) {
            ItemStack light = new ItemStack(Items.LIGHT);
            light.getOrCreateTagElement("BlockStateTag").putString("level", Integer.toString(lvl));
            out.add(light);
        }
        return out;
    }

    private static ItemStack modIcon(String ns) {
        for (ItemStack s : CreativeModeTabs.searchTab().getDisplayItems()) if (id(s).getNamespace().equals(ns)) return s;
        return new ItemStack(Items.BOOK);
    }

    private static Map<String, Integer> modCounts() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("minecraft", 0);
        for (ItemStack s : CreativeModeTabs.searchTab().getDisplayItems()) m.merge(id(s).getNamespace(), 1, Integer::sum);
        return m;
    }

    // ---------- мастерская

    private static void loadWork(ItemStack s) {
        if (s.isEmpty()) return;
        work = s.copyWithCount(1);
        workCount = 1;
        workName = s.hasCustomHoverName() ? s.getHoverName().getString() : "";
    }

    private static ItemStack buildWork() {
        ItemStack r = work.copyWithCount(1);
        if (!workName.isBlank()) r.setHoverName(Component.literal(workName).withStyle(s -> s.withItalic(false)));
        if (!workLore.isBlank()) {
            ListTag lore = new ListTag();
            lore.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal(workLore).withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GRAY)))));
            r.getOrCreateTagElement("display").put("Lore", lore);
        }
        if (workUnbreakable) r.getOrCreateTag().putBoolean("Unbreakable", true);
        if (workGlint && !r.isEnchanted()) {
            ListTag l = new ListTag();
            l.add(new CompoundTag());
            r.getOrCreateTag().put("Enchantments", l);
        }
        if (workHide) r.getOrCreateTag().putInt("HideFlags", 127);
        return r;
    }

    // ================================================================== избранное / недавние

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
        return s.copyWithCount(1).save(new CompoundTag()).toString();
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

    // ================================================================== инвентарь

    private static int menuSlot(int inv) {
        if (inv < 9) return 36 + inv;
        if (inv < 36) return inv;
        if (inv < 40) return 8 - (inv - 36);
        return 45;
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

    private boolean give(ItemStack proto, int count) {
        if (proto.isEmpty()) return false;
        ItemStack s = proto.copyWithCount(Math.min(count, proto.getMaxStackSize()));
        remember(s);
        Inventory inv = player.getInventory();
        for (int i = 0; i < 36 && !s.isEmpty(); i++) {
            ItemStack cur = inv.getItem(i);
            if (!cur.isEmpty() && ItemStack.isSameItemSameTags(cur, s) && cur.getCount() < cur.getMaxStackSize()) {
                int add = Math.min(s.getCount(), cur.getMaxStackSize() - cur.getCount());
                setInv(i, cur.copyWithCount(cur.getCount() + add));
                s.shrink(add);
            }
        }
        for (int i = 0; i < 36 && !s.isEmpty(); i++) {
            if (inv.getItem(i).isEmpty()) {
                setInv(i, s.copy());
                s = ItemStack.EMPTY;
            }
        }
        if (!s.isEmpty()) {
            player.displayClientMessage(Component.translatable("archud.creative.full").withStyle(ChatFormatting.RED), true);
            return false;
        }
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ITEM_PICKUP, 1.6F, 0.4F));
        return true;
    }

    /** Позиция слота: док (хотбар, броня, вторая рука, корзина) и раскрытый инвентарь над доком. */
    private int[] slotXY(int inv) {
        int dockW = 4 * S + 8 + 9 * S + 8 + S + 4 + S + 4 + 18;
        int dx = (left + right) / 2 - dockW / 2;
        int y = dockTop + 5;
        int hotX = dx + 4 * S + 8;
        if (inv >= 36 && inv <= 39) return new int[]{dx + (39 - inv) * S, y};
        if (inv < 9) return new int[]{hotX + inv * S, y};
        if (inv == 40) return new int[]{hotX + 9 * S + 8, y};
        if (inv == 41) return new int[]{hotX + 9 * S + 8 + S + 4, y};
        if (inv == 42) return new int[]{hotX + 9 * S + 8 + 2 * S + 8, y}; // кнопка «инвентарь»
        // 9..35 — раскрытый инвентарь
        int oy = dockTop - 4 - 3 * S - 4;
        return new int[]{hotX + ((inv - 9) % 9) * S, oy + ((inv - 9) / 9) * S};
    }

    private int invAt(double mx, double my) {
        for (int i = 0; i <= 42; i++) {
            if (i >= 9 && i < 36 && !invOpen) continue;
            int[] p = slotXY(i);
            int w = i == 42 ? 18 : S;
            if (mx >= p[0] && mx < p[0] + w && my >= p[1] && my < p[1] + S) return i;
        }
        return -1;
    }

    // ================================================================== отрисовка

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        g.fill(0, 0, width, height, 0xB0060A0E); // плотнее ванильного затемнения — мир и HUD не просвечивают
        int accent = Theme.accent();
        hits.clear();
        // раскрытый инвентарь: рабочая область заканчивается над ним (иначе иконки предметов, которые
        // рисуются на верхнем слое, проступают сквозь панель инвентаря)
        bottom = invOpen ? slotXY(9)[1] - 12 : dockTop - 6;

        renderTopBar(g, accent);
        renderStrip(g, mx, my, accent);
        // рабочая область
        Draw.panel(g, left - 4, top - 4, right - left + 8, bottom - top + 8, 0xD80B0F14, Draw.alpha(accent, 0x50));
        switch (tab.kind()) {
            case HOME -> renderHome(g, mx, my, accent);
            case MODS -> renderMods(g, mx, my, accent);
            case WORKSHOP -> renderWorkshop(g, mx, my, accent);
            case KITS -> renderKits(g, mx, my, accent);
            default -> renderItems(g, mx, my, accent, tab.kind() == Kind.HEADS ? 24 : tab.kind() == Kind.MOD ? 18 : 0);
        }
        renderDock(g, mx, my, accent);

        // фокус: предмет под курсором
        int inv = invAt(mx, my);
        Hit hov = inv >= 0 ? null : hitAt(mx, my);
        if (hov != null && hov.stack() != null && !hov.stack().isEmpty()) focus = hov.stack();
        else if (inv >= 0 && inv <= 40 && !player.getInventory().getItem(inv).isEmpty()) focus = player.getInventory().getItem(inv);
        renderInfo(g, accent);

        super.render(g, mx, my, pt);

        if (!carried.isEmpty()) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 232);
            g.renderItem(carried, mx - 8, my - 8);
            g.renderItemDecorations(font, carried, mx - 8, my - 8);
            g.pose().popPose();
        } else if (hov != null && hov.stack() != null && !hov.stack().isEmpty()) {
            g.renderTooltip(font, hov.stack(), mx, my);
        } else if (inv >= 0 && inv <= 40 && !player.getInventory().getItem(inv).isEmpty()) {
            g.renderTooltip(font, player.getInventory().getItem(inv), mx, my);
        } else if (inv == 41) {
            g.renderTooltip(font, Component.translatable("archud.creative.trash"), mx, my);
        } else if (inv == 42) {
            g.renderTooltip(font, Component.translatable(invOpen ? "archud.creative.inv_hide" : "archud.creative.inv_show"), mx, my);
        } else if (hov != null && hov.tip() != null) {
            g.renderTooltip(font, hov.tip(), mx, my);
        }
    }

    private Hit hitAt(double mx, double my) {
        for (int i = hits.size() - 1; i >= 0; i--) if (hits.get(i).in(mx, my)) return hits.get(i);
        return null;
    }

    private void renderTopBar(GuiGraphics g, int accent) {
        Draw.hGradient(g, 0, 0, width, 32, Draw.alpha(accent, 0x38), 0x20000000);
        g.fill(0, 31, width, 32, Draw.alpha(accent, 0x90));
        // логотип: ромб + название
        int lx = 12, ly = 9;
        for (int i = 0; i < 7; i++) g.fill(lx + 6 - i, ly + i, lx + 7 + i, ly + i + 1, accent);
        for (int i = 0; i < 7; i++) g.fill(lx + i, ly + 7 + i, lx + 13 - i, ly + 8 + i, Draw.darken(accent, 0.3F));
        g.drawString(font, title, lx + 20, 8, 0xFFFFFFFF, true);
        Draw.small(g, font, tab.name().getString(), lx + 20, 19, Draw.alpha(accent, 0xFF), 0.65F);
    }

    /** Лента вкладок: иконки, активная раскрыта с названием, колесо — прокрутка. */
    private void renderStrip(GuiGraphics g, int mx, int my, int accent) {
        int x0 = left - 4, x1 = right + 4;
        g.enableScissor(x0, stripY, x1, stripY + 24);
        int x = x0 - stripScroll;
        int total = 0;
        for (int i = 0; i < tabs.size(); i++) {
            Tab t = tabs.get(i);
            boolean sel = key(t).equals(key(tab)) || (tab.kind() == Kind.MOD && t.kind() == Kind.MODS);
            String label = t.name().getString();
            int w = sel ? 26 + (int) (font.width(label) * 0.75F) + 8 : 22;
            boolean hov = mx >= x && mx < x + w && my >= stripY && my < stripY + 22 && mx >= x0 && mx < x1;
            int bg = sel ? Draw.alpha(accent, 0x55) : hov ? 0x30FFFFFF : 0x18FFFFFF;
            Draw.panel(g, x, stripY, w, 22, bg, sel ? accent : hov ? Draw.alpha(accent, 0x90) : 0x25FFFFFF);
            g.renderItem(t.icon(), x + 3, stripY + 3);
            if (sel) Draw.small(g, font, label, x + 23, stripY + 8, 0xFFFFFFFF, 0.75F);
            int fx = x;
            hits.add(new Hit(Math.max(x, x0), stripY, Math.min(w, x1 - Math.max(x, x0)), 22, null, () -> selectTab(t, true), t.name()));
            x += w + 3;
            total += w + 3;
            if (i == 10) { // разделитель между вкладками Arc и категориями игры
                g.fill(x, stripY + 4, x + 1, stripY + 18, 0x40FFFFFF);
                x += 5;
                total += 5;
            }
            if (fx > x1) break;
        }
        g.disableScissor();
        stripMax = Math.max(0, total - (x1 - x0));
        if (stripMax > 0) {
            int w = x1 - x0;
            int th = Math.max(20, w * w / (w + stripMax));
            int tx = x0 + (w - th) * stripScroll / stripMax;
            g.fill(tx, stripY + 23, tx + th, stripY + 24, Draw.alpha(accent, 0xA0));
        }
    }

    private int stripMax;

    // ---------- сетка / список предметов

    private void renderItems(GuiGraphics g, int mx, int my, int accent, int header) {
        int y0 = top + header;
        if (tab.kind() == Kind.MOD) {
            Draw.small(g, font, Component.translatable("archud.creative.mod_view", tab.name()).getString(), left + 2, top + 3, Draw.TEXT_DIM, 0.7F);
            String back = "< " + Component.translatable("archud.creative.mods").getString();
            int bw = (int) (font.width(back) * 0.7F) + 8;
            Draw.panel(g, right - bw, top, bw, 12, 0x30FFFFFF, Draw.alpha(accent, 0x80));
            Draw.small(g, font, back, right - bw + 4, top + 3, 0xFFFFFFFF, 0.7F);
            Tab mods = tabs.stream().filter(t -> t.kind() == Kind.MODS).findFirst().orElse(tabs.get(0));
            hits.add(new Hit(right - bw, top, bw, 12, null, () -> selectTab(mods, true), null));
        }
        if (tab.kind() == Kind.HEADS && nickBox != null && !nickBox.getValue().isBlank()) {
            // предпросмотр головы по нику
            ItemStack h = head(nickBox.getValue().trim());
            g.renderItem(h, left + 234, top + 2);
            hits.add(new Hit(left + 233, top + 1, 18, 18, h, null, null));
        }
        if (items.isEmpty()) {
            String key = tab.kind() == Kind.FAVORITES && search.getValue().isEmpty() ? "archud.creative.empty_fav"
                    : tab.kind() == Kind.RECENT && search.getValue().isEmpty() ? "archud.creative.empty_recent" : "archud.creative.nothing";
            Draw.smallCentered(g, font, Component.translatable(key).getString(), (left + right) / 2F, (y0 + bottom) / 2F - 4, Draw.TEXT_DIM, 0.75F);
            maxScroll = 0;
            return;
        }
        int areaW = right - left - 8;
        int areaH = bottom - y0;
        if (listView) {
            int rowH = 20;
            int colW = Math.max(160, areaW / Math.max(1, areaW / 200));
            int cols = Math.max(1, areaW / colW);
            int rows = Math.max(1, areaH / rowH);
            int total = (items.size() + cols - 1) / cols;
            maxScroll = Math.max(0, total - rows);
            scroll = Mth.clamp(scroll, 0, maxScroll);
            for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
                int idx = (scroll + r) * cols + c;
                if (idx >= items.size()) break;
                ItemStack s = items.get(idx);
                int x = left + c * colW, y = y0 + r * rowH;
                boolean hov = mx >= x && mx < x + colW - 4 && my >= y && my < y + rowH - 2;
                Draw.panel(g, x, y, colW - 4, rowH - 2, hov ? Draw.alpha(accent, 0x40) : 0x22FFFFFF, hov ? accent : 0x18FFFFFF);
                g.renderItem(s, x + 2, y + 1);
                String name = s.getHoverName().getString();
                int max = (int) ((colW - 4 - 24 - 34) / 0.75F);
                if (font.width(name) > max) name = font.plainSubstrByWidth(name, max - font.width("…")) + "…";
                Draw.small(g, font, name, x + 22, y + 3, 0xFFFFFFFF, 0.75F);
                Draw.small(g, font, ArcHud.platform().modName(id(s).getNamespace()), x + 22, y + 11, Draw.TEXT_DIM, 0.5F);
                hits.add(new Hit(x, y, colW - 38, rowH - 2, s, null, null));
                // быстрые кнопки +1 / +стак
                mini(g, x + colW - 36, y + 3, "+1", mx, my, accent, () -> give(s, 1));
                if (s.getMaxStackSize() > 1) mini(g, x + colW - 20, y + 3, "+" + s.getMaxStackSize(), mx, my, accent, () -> give(s, s.getMaxStackSize()));
            }
            scrollbar(g, y0, areaH, rows, total, accent);
            return;
        }
        int t = TILE[tileIdx];
        int cols = Math.max(1, areaW / t);
        int rows = Math.max(1, areaH / t);
        int total = (items.size() + cols - 1) / cols;
        maxScroll = Math.max(0, total - rows);
        scroll = Mth.clamp(scroll, 0, maxScroll);
        int gx = left + (areaW - cols * t) / 2;
        float scale = t >= 28 ? 1.4F : 1F;
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
            int idx = (scroll + r) * cols + c;
            if (idx >= items.size()) break;
            ItemStack s = items.get(idx);
            int x = gx + c * t, y = y0 + r * t;
            boolean hov = mx >= x && mx < x + t && my >= y && my < y + t;
            g.fill(x + 1, y + 1, x + t - 1, y + t - 1, hov ? Draw.alpha(accent, 0x45) : 0x1EFFFFFF);
            if (hov) Draw.outline(g, x, y, t, t, accent);
            float sc = hov ? scale * 1.15F : scale;
            g.pose().pushPose();
            g.pose().translate(x + t / 2F - 8 * sc, y + t / 2F - 8 * sc, hov ? 50 : 0);
            g.pose().scale(sc, sc, 1F);
            g.renderItem(s, 0, 0);
            g.renderItemDecorations(font, s, 0, 0);
            g.pose().popPose();
            if (isFavorite(s) && tab.kind() != Kind.FAVORITES) g.fill(x + t - 4, y + 2, x + t - 2, y + 4, 0xFFFFD54F);
            hits.add(new Hit(x, y, t, t, s, null, null));
        }
        scrollbar(g, y0, areaH, rows, total, accent);
    }

    private void scrollbar(GuiGraphics g, int y0, int h, int rows, int total, int accent) {
        barX = right + 1;
        barY = y0;
        barH = rows * (listView ? 20 : TILE[tileIdx]);
        g.fill(barX, barY, barX + 2, barY + barH, 0x25FFFFFF);
        if (maxScroll > 0) {
            int th = Math.max(14, barH * rows / Math.max(rows, total));
            int ty = barY + (barH - th) * scroll / maxScroll;
            g.fill(barX, ty, barX + 2, ty + th, Draw.alpha(accent, draggingBar ? 0xFF : 0xB0));
        }
    }

    private void mini(GuiGraphics g, int x, int y, String label, int mx, int my, int accent, Runnable action) {
        int w = Math.max(14, (int) (font.width(label) * 0.55F) + 4);
        boolean hov = mx >= x && mx < x + w && my >= y && my < y + 12;
        Draw.panel(g, x, y, w, 12, hov ? Draw.alpha(accent, 0x70) : 0x30FFFFFF, hov ? accent : 0x30FFFFFF);
        Draw.smallCentered(g, font, label, x + w / 2F, y + 4, 0xFFFFFFFF, 0.55F);
        hits.add(new Hit(x, y, w, 12, null, action, null));
    }

    // ---------- Главная

    private void renderHome(GuiGraphics g, int mx, int my, int accent) {
        int y = top;
        int w = right - left;
        y = homeRow(g, mx, my, accent, y, "archud.creative.recent", RECENT, "archud.creative.empty_recent");
        y = homeRow(g, mx, my, accent, y + 4, "archud.creative.favorites", favorites(), "archud.creative.empty_fav");
        // плитки категорий
        Draw.small(g, font, Component.translatable("archud.creative.categories").getString(), left, y + 6, Draw.alpha(accent, 0xFF), 0.75F);
        y += 16;
        int cardW = 92, cardH = 30;
        int cols = Math.max(1, (w + 4) / (cardW + 4));
        cardW = (w + 4) / cols - 4;
        int i = 0;
        for (Tab t : tabs) {
            if (t.kind() == Kind.HOME) continue;
            int cx = left + (i % cols) * (cardW + 4), cy = y + (i / cols) * (cardH + 4);
            if (cy + cardH > bottom) break;
            boolean hov = mx >= cx && mx < cx + cardW && my >= cy && my < cy + cardH;
            Draw.panel(g, cx, cy, cardW, cardH, hov ? Draw.alpha(accent, 0x45) : 0x26FFFFFF, hov ? accent : 0x25FFFFFF);
            if (hov) g.fill(cx + 1, cy + cardH - 2, cx + cardW - 1, cy + cardH - 1, accent);
            g.renderItem(t.icon(), cx + 6, cy + 7);
            String n = t.name().getString();
            int max = (int) ((cardW - 30) / 0.7F);
            if (font.width(n) > max) n = font.plainSubstrByWidth(n, max - font.width("…")) + "…";
            Draw.small(g, font, n, cx + 26, cy + 8, 0xFFFFFFFF, 0.7F);
            String sub = t.kind() == Kind.CATEGORY ? Component.translatable("archud.creative.count", t.tab().getDisplayItems().size()).getString()
                    : Component.translatable("archud.creative.sub." + t.kind().name().toLowerCase(Locale.ROOT)).getString();
            Draw.small(g, font, sub, cx + 26, cy + 17, Draw.TEXT_DIM, 0.5F);
            hits.add(new Hit(cx, cy, cardW, cardH, null, () -> selectTab(t, true), null));
            i++;
        }
        maxScroll = 0;
    }

    private int homeRow(GuiGraphics g, int mx, int my, int accent, int y, String title, List<ItemStack> list, String empty) {
        Draw.small(g, font, Component.translatable(title).getString(), left, y + 2, Draw.alpha(accent, 0xFF), 0.75F);
        y += 11;
        int t = 20;
        int cols = (right - left) / t;
        Draw.panel(g, left, y, right - left, t + 4, 0x18FFFFFF, 0x18FFFFFF);
        if (list.isEmpty()) {
            Draw.small(g, font, Component.translatable(empty).getString(), left + 6, y + 9, Draw.TEXT_DIM, 0.6F);
        }
        for (int i = 0; i < Math.min(cols, list.size()); i++) {
            ItemStack s = list.get(i);
            int x = left + 2 + i * t;
            boolean hov = mx >= x && mx < x + t && my >= y + 2 && my < y + 2 + t;
            if (hov) g.fill(x, y + 2, x + t, y + 2 + t, Draw.alpha(accent, 0x50));
            g.renderItem(s, x + 2, y + 4);
            g.renderItemDecorations(font, s, x + 2, y + 4);
            hits.add(new Hit(x, y + 2, t, t, s, null, null));
        }
        return y + t + 4;
    }

    // ---------- Моды

    private void renderMods(GuiGraphics g, int mx, int my, int accent) {
        String q = search.getValue().trim().toLowerCase(Locale.ROOT);
        List<Map.Entry<String, Integer>> mods = new ArrayList<>();
        for (Map.Entry<String, Integer> e : modCounts().entrySet()) {
            if (e.getValue() == 0) continue;
            String name = ArcHud.platform().modName(e.getKey());
            if (!q.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(q) && !e.getKey().contains(q)) continue;
            mods.add(e);
        }
        int w = right - left;
        int cardW = 120, cardH = 34;
        int cols = Math.max(1, (w + 4) / (cardW + 4));
        cardW = (w + 4) / cols - 4;
        int rows = Math.max(1, (bottom - top + 4) / (cardH + 4));
        int total = (mods.size() + cols - 1) / cols;
        maxScroll = Math.max(0, total - rows);
        scroll = Mth.clamp(scroll, 0, maxScroll);
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
            int idx = (scroll + r) * cols + c;
            if (idx >= mods.size()) break;
            Map.Entry<String, Integer> e = mods.get(idx);
            int x = left + c * (cardW + 4), y = top + r * (cardH + 4);
            boolean hov = mx >= x && mx < x + cardW && my >= y && my < y + cardH;
            Draw.panel(g, x, y, cardW, cardH, hov ? Draw.alpha(accent, 0x45) : 0x26FFFFFF, hov ? accent : 0x25FFFFFF);
            g.pose().pushPose();
            g.pose().translate(x + 5, y + 5, 0);
            g.pose().scale(1.5F, 1.5F, 1F);
            g.renderItem(modIcon(e.getKey()), 0, 0);
            g.pose().popPose();
            String name = ArcHud.platform().modName(e.getKey());
            int max = (int) ((cardW - 38) / 0.75F);
            if (font.width(name) > max) name = font.plainSubstrByWidth(name, max - font.width("…")) + "…";
            Draw.small(g, font, name, x + 34, y + 8, 0xFFFFFFFF, 0.75F);
            Draw.small(g, font, Component.translatable("archud.creative.count", e.getValue()).getString(), x + 34, y + 19, Draw.TEXT_DIM, 0.55F);
            String ns = e.getKey();
            hits.add(new Hit(x, y, cardW, cardH, null, () -> openMod(ns), null));
        }
        scrollbar(g, top, bottom - top, rows, total, accent);
    }

    // ---------- Мастерская

    private void renderWorkshop(GuiGraphics g, int mx, int my, int accent) {
        // слот предмета
        int sx = left + 10, sy = top + 6;
        Draw.small(g, font, Component.translatable("archud.creative.w_item").getString(), sx, sy, Draw.TEXT_DIM, 0.65F);
        int bx = sx, by = sy + 10;
        boolean hov = mx >= bx && mx < bx + 40 && my >= by && my < by + 40;
        Draw.panel(g, bx, by, 40, 40, hov ? Draw.alpha(accent, 0x40) : 0x30000000, hov ? accent : Draw.alpha(accent, 0x70));
        if (!work.isEmpty()) {
            g.pose().pushPose();
            g.pose().translate(bx + 4, by + 4, 0);
            g.pose().scale(2F, 2F, 1F);
            g.renderItem(work, 0, 0);
            g.pose().popPose();
        } else Draw.smallCentered(g, font, "+", bx + 20, by + 16, 0x60FFFFFF, 1.2F);
        hits.add(new Hit(bx, by, 40, 40, null, () -> {
            if (!carried.isEmpty()) {
                loadWork(carried);
                carried = ItemStack.EMPTY;
                rebuildWidgets();
            }
        }, Component.translatable("archud.creative.w_drop")));
        // количество
        int cy = by + 48;
        Draw.small(g, font, Component.translatable("archud.creative.w_count").getString(), sx, cy, Draw.TEXT_DIM, 0.65F);
        int max = work.isEmpty() ? 64 : work.getMaxStackSize();
        workCount = Mth.clamp(workCount, 1, max);
        mini(g, sx, cy + 10, "-", mx, my, accent, () -> workCount = Math.max(1, workCount - (hasShiftDown() ? 10 : 1)));
        Draw.smallCentered(g, font, Integer.toString(workCount), sx + 30, cy + 13, 0xFFFFFFFF, 0.9F);
        mini(g, sx + 46, cy + 10, "+", mx, my, accent, () -> workCount = Math.min(work.isEmpty() ? 64 : work.getMaxStackSize(), workCount + (hasShiftDown() ? 10 : 1)));
        mini(g, sx + 66, cy + 10, "max", mx, my, accent, () -> workCount = work.isEmpty() ? 64 : work.getMaxStackSize());
        // подписи полей
        int fx = left + 150;
        Draw.small(g, font, Component.translatable("archud.creative.w_name").getString(), fx, top + 10, Draw.TEXT_DIM, 0.65F);
        Draw.small(g, font, Component.translatable("archud.creative.w_lore").getString(), fx, top + 42, Draw.TEXT_DIM, 0.65F);
        Draw.small(g, font, Component.translatable("archud.creative.w_flags").getString(), fx, top + 84, Draw.TEXT_DIM, 0.65F);
        // предпросмотр результата
        int py = cy + 32;
        Draw.small(g, font, Component.translatable("archud.creative.w_result").getString(), sx, py, Draw.TEXT_DIM, 0.65F);
        if (!work.isEmpty()) {
            ItemStack r = buildWork();
            Draw.panel(g, sx, py + 10, 130, Math.max(24, bottom - py - 14), 0x30000000, 0x25FFFFFF);
            g.renderItem(r, sx + 4, py + 14);
            hits.add(new Hit(sx + 3, py + 13, 18, 18, r.copyWithCount(workCount), null, null));
            int ly = py + 14;
            for (Component c : r.getTooltipLines(player, TooltipFlag.NORMAL)) {
                if (ly > bottom - 10) break;
                String s = c.getString();
                if (font.width(s) * 0.6F > 100) s = font.plainSubstrByWidth(s, (int) (100 / 0.6F)) + "…";
                Draw.small(g, font, s, sx + 24, ly + 1, 0xFFE0E6EE, 0.6F);
                ly += 7;
            }
        } else {
            Draw.small(g, font, Component.translatable("archud.creative.w_hint").getString(), sx, py + 12, 0xFF6A7682, 0.55F);
        }
    }

    // ---------- Наборы

    private void renderKits(GuiGraphics g, int mx, int my, int accent) {
        var mgr = Minecraft.getInstance().getHotbarManager();
        int rowH = Math.max(20, Math.min(26, (bottom - top) / 9));
        for (int k = 0; k < 9; k++) {
            Hotbar hb = mgr.get(k);
            int y = top + k * rowH;
            Draw.panel(g, left, y, right - left, rowH - 2, 0x1EFFFFFF, 0x18FFFFFF);
            Draw.small(g, font, Component.translatable("archud.creative.kit", k + 1).getString(), left + 6, y + rowH / 2 - 4, Draw.TEXT_DIM, 0.7F);
            int x0 = left + 52;
            for (int i = 0; i < 9; i++) {
                ItemStack s = hb.get(i);
                int x = x0 + i * 19;
                g.fill(x, y + 1, x + 18, y + 19, 0x30000000);
                if (!s.isEmpty()) {
                    g.renderItem(s, x + 1, y + 2);
                    g.renderItemDecorations(font, s, x + 1, y + 2);
                    hits.add(new Hit(x, y + 1, 18, 18, s, null, null));
                }
            }
            final int kk = k;
            int bx = x0 + 9 * 19 + 8;
            kitBtn(g, bx, y + 3, "archud.creative.kit_save", mx, my, accent, () -> {
                for (int i = 0; i < 9; i++) hb.set(i, player.getInventory().getItem(i).copy());
                mgr.save();
                click();
                player.displayClientMessage(Component.translatable("archud.creative.kit_saved", kk + 1), true);
            });
            kitBtn(g, bx + 58, y + 3, "archud.creative.kit_load", mx, my, accent, () -> {
                for (int i = 0; i < 9; i++) setInv(i, hb.get(i).copy());
                click();
            });
        }
        maxScroll = 0;
    }

    private void kitBtn(GuiGraphics g, int x, int y, String key, int mx, int my, int accent, Runnable action) {
        boolean hov = mx >= x && mx < x + 54 && my >= y && my < y + 14;
        Draw.panel(g, x, y, 54, 14, hov ? Draw.alpha(accent, 0x55) : 0x30FFFFFF, hov ? accent : Draw.alpha(accent, 0x60));
        Draw.smallCentered(g, font, Component.translatable(key).getString(), x + 27, y + 4, 0xFFFFFFFF, 0.65F);
        hits.add(new Hit(x, y, 54, 14, null, action, null));
    }

    // ---------- док

    private void renderDock(GuiGraphics g, int mx, int my, int accent) {
        int[] a = slotXY(39), b = slotXY(42);
        int dx = a[0] - 6, dw = b[0] + 18 + 6 - dx;
        Draw.panel(g, dx, dockTop, dw, S + 10, 0xF0101419, Draw.alpha(accent, 0x90));
        g.fill(dx + 3, dockTop, dx + dw - 3, dockTop + 1, accent);
        Inventory inv = player.getInventory();
        if (invOpen) {
            int[] p = slotXY(9);
            Draw.panel(g, p[0] - 5, p[1] - 5, 9 * S + 10, 3 * S + 10, 0xF4101419, Draw.alpha(accent, 0x90));
        }
        int hovered = invAt(mx, my);
        for (int i = 0; i <= 42; i++) {
            if (i >= 9 && i < 36 && !invOpen) continue;
            int[] p = slotXY(i);
            int x = p[0], y = p[1];
            boolean hov = i == hovered;
            if (i == 42) {
                Draw.panel(g, x, y, 18, S, hov ? Draw.alpha(accent, 0x60) : 0x30FFFFFF, hov ? accent : 0x40FFFFFF);
                Draw.smallCentered(g, font, invOpen ? "v" : "^", x + 9, y + 7, 0xFFFFFFFF, 0.8F);
                continue;
            }
            boolean sel = i == inv.selected;
            int bg = i == 41 ? 0x40FF5252 : sel ? Draw.alpha(accent, 0x45) : 0x50000000;
            g.fill(x + 1, y + 1, x + S - 1, y + S - 1, hov ? Draw.alpha(i == 41 ? 0xFFFF5252 : accent, 0x60) : bg);
            Draw.outline(g, x, y, S, S, hov || sel ? Draw.alpha(i == 41 ? 0xFFFF5252 : accent, 0xE0) : 0x28FFFFFF);
            if (i == 41) {
                for (int d = 0; d < 8; d++) {
                    g.fill(x + 6 + d, y + 6 + d, x + 7 + d, y + 7 + d, 0xFFFF8A80);
                    g.fill(x + 13 - d, y + 6 + d, x + 14 - d, y + 7 + d, 0xFFFF8A80);
                }
                continue;
            }
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() && i >= 36) {
                ResourceLocation icon = switch (i) {
                    case 39 -> InventoryMenu.EMPTY_ARMOR_SLOT_HELMET;
                    case 38 -> InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE;
                    case 37 -> InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS;
                    case 36 -> InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS;
                    default -> InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD;
                };
                g.setColor(1F, 1F, 1F, 0.4F);
                RenderSystem.enableBlend();
                g.blit(x + 2, y + 2, 0, 16, 16, Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(icon));
                g.setColor(1F, 1F, 1F, 1F);
            }
            if (i < 9 && s.isEmpty()) Draw.small(g, font, Integer.toString(i + 1), x + 2, y + 2, 0x40FFFFFF, 0.5F);
            g.renderItem(s, x + 2, y + 2);
            g.renderItemDecorations(font, s, x + 2, y + 2);
        }
    }

    // ---------- карточка

    private void renderInfo(GuiGraphics g, int accent) {
        int iy = top - 4, ih = bottom - top + 8;
        Draw.panel(g, infoX, iy, INFO_W, ih, 0xE00E1218, Draw.alpha(accent, 0x60));
        boolean has = !focus.isEmpty();
        give1Btn.visible = giveStackBtn.visible = favBtn.visible = toWorkBtn.visible = has;
        if (!has) {
            Draw.smallCentered(g, font, Component.translatable("archud.creative.hover_hint").getString(), infoX + INFO_W / 2F, iy + 50, Draw.TEXT_DIM, 0.65F);
            return;
        }
        // крупная иконка на подсвеченном фоне
        int cx = infoX + INFO_W / 2;
        Draw.hGradient(g, infoX + 1, iy + 1, infoX + INFO_W - 1, iy + 54, Draw.alpha(accent, 0x30), 0x00000000);
        g.pose().pushPose();
        g.pose().translate(cx - 20, iy + 8, 0);
        g.pose().scale(2.5F, 2.5F, 1F);
        g.renderItem(focus, 0, 0);
        g.pose().popPose();
        int y = iy + 58;
        for (var line : font.split(focus.getHoverName(), INFO_W - 10)) {
            g.drawString(font, line, cx - font.width(line) / 2, y, 0xFFFFFFFF, true);
            y += 10;
            if (y > iy + 78) break;
        }
        ResourceLocation id = id(focus);
        Draw.smallCentered(g, font, id.toString(), cx, y + 1, Draw.TEXT_DIM, 0.5F);
        Draw.smallCentered(g, font, ArcHud.platform().modName(id.getNamespace()), cx, y + 8, Draw.alpha(accent, 0xFF), 0.6F);
        favBtn.setMessage(Component.translatable(isFavorite(focus) ? "archud.creative.unfav" : "archud.creative.fav"));
        int sy = top + 192;
        if (sy + 30 < bottom) {
            stat(g, sy, "archud.creative.stack", Integer.toString(focus.getMaxStackSize()));
            stat(g, sy + 9, "archud.creative.rarity", focus.getRarity().name().charAt(0) + focus.getRarity().name().substring(1).toLowerCase(Locale.ROOT));
            stat(g, sy + 18, "archud.creative.tags", Long.toString(focus.getTags().count()));
            if (focus.isDamageableItem()) stat(g, sy + 27, "archud.creative.durability", Integer.toString(focus.getMaxDamage()));
        }
    }

    private void stat(GuiGraphics g, int y, String key, String value) {
        Draw.small(g, font, Component.translatable(key).getString(), infoX + 8, y, Draw.TEXT_DIM, 0.6F);
        Draw.small(g, font, value, infoX + INFO_W - 8 - font.width(value) * 0.6F, y, Draw.TEXT, 0.6F);
    }

    // ================================================================== мышь

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        for (EditBox b : new EditBox[]{search, nickBox, nameBox, loreBox}) {
            if (b != null && b.isFocused() && !b.isMouseOver(mx, my)) b.setFocused(false);
        }
        if (maxScroll > 0 && mx >= barX - 2 && mx < barX + 4 && my >= barY && my < barY + barH) {
            draggingBar = true;
            dragBar(my);
            return true;
        }
        int inv = invAt(mx, my);
        if (inv >= 0) {
            clickInventory(inv, button);
            return true;
        }
        Hit h = hitAt(mx, my);
        if (h != null) {
            if (h.action() != null) {
                if (button == 0) h.action().run();
                return true;
            }
            if (h.stack() != null) {
                clickItem(h.stack(), button);
                return true;
            }
        }
        // пустое место рабочей области с предметом на курсоре — убрать его
        if (!carried.isEmpty() && mx >= left && mx < right && my >= top && my < bottom) {
            carried = ItemStack.EMPTY;
            return true;
        }
        boolean outside = my > 32 && (mx < left - 4 || mx >= infoX + INFO_W || my >= height - 4);
        if (outside && !carried.isEmpty()) {
            ItemStack drop = button == 1 ? carried.split(1) : carried;
            Minecraft.getInstance().gameMode.handleCreativeModeItemDrop(drop.copy());
            if (button != 1) carried = ItemStack.EMPTY;
            return true;
        }
        return false;
    }

    private void clickItem(ItemStack s, int button) {
        if (!carried.isEmpty()) {
            if (button == 0 && ItemStack.isSameItemSameTags(carried, s) && carried.getCount() < carried.getMaxStackSize()) carried.grow(1);
            else carried = ItemStack.EMPTY;
            return;
        }
        if (button == 0 && hasShiftDown()) {
            give(s, s.getMaxStackSize());
            return;
        }
        carried = s.copyWithCount(button == 0 ? 1 : s.getMaxStackSize());
        remember(s);
    }

    private void clickInventory(int i, int button) {
        Inventory inv = player.getInventory();
        if (i == 42) {
            invOpen = !invOpen;
            click();
            return;
        }
        if (i == 41) {
            if (!carried.isEmpty()) carried = ItemStack.EMPTY;
            else if (hasShiftDown()) {
                for (int k = 0; k <= 40; k++) if (!inv.getItem(k).isEmpty()) setInv(k, ItemStack.EMPTY);
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.LAVA_EXTINGUISH, 1.5F, 0.3F));
            }
            return;
        }
        ItemStack cur = inv.getItem(i);
        if (button == 2) {
            if (!cur.isEmpty() && carried.isEmpty()) carried = cur.copyWithCount(cur.getMaxStackSize());
            return;
        }
        if (carried.isEmpty()) {
            if (cur.isEmpty()) return;
            if (button == 0 && hasShiftDown()) {
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
        if (button == 1) {
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

    private void dragBar(double my) {
        if (maxScroll <= 0) return;
        float f = (float) ((my - barY) / Math.max(1, barH));
        scroll = Mth.clamp(Math.round(f * maxScroll), 0, maxScroll);
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
        if (my >= stripY && my < stripY + 24) {
            stripScroll = Mth.clamp(stripScroll - (int) (delta * 30), 0, stripMax);
            return true;
        }
        if (mx >= left - 4 && mx < right + 4 && my >= top && my < bottom) {
            scroll = Mth.clamp(scroll - (int) Math.signum(delta) * (hasShiftDown() ? 5 : 1), 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    // ================================================================== клавиатура

    private boolean typing() {
        for (EditBox b : new EditBox[]{search, nickBox, nameBox, loreBox}) if (b != null && b.isFocused()) return true;
        return false;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        Minecraft mc = Minecraft.getInstance();
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (!typing()) {
            double mx = mc.mouseHandler.xpos() * width / mc.getWindow().getScreenWidth();
            double my = mc.mouseHandler.ypos() * height / mc.getWindow().getScreenHeight();
            Hit h = hitAt(mx, my);
            ItemStack grid = h != null && h.stack() != null ? h.stack() : ItemStack.EMPTY;
            int inv = invAt(mx, my);
            for (int n = 0; n < 9; n++) {
                if (!mc.options.keyHotbarSlots[n].matches(key, scan)) continue;
                if (!grid.isEmpty()) {
                    setInv(n, grid.copyWithCount(grid.getMaxStackSize()));
                    remember(grid);
                } else if (inv >= 0 && inv <= 40 && inv != n) {
                    ItemStack a = player.getInventory().getItem(inv).copy(), b = player.getInventory().getItem(n).copy();
                    if (fits(inv, b)) {
                        setInv(n, a);
                        setInv(inv, b);
                    }
                }
                return true;
            }
            if (mc.options.keyDrop.matches(key, scan)) {
                if (!grid.isEmpty()) mc.gameMode.handleCreativeModeItemDrop(grid.copyWithCount(hasControlDown() ? grid.getMaxStackSize() : 1));
                else if (inv >= 0 && inv <= 40 && !player.getInventory().getItem(inv).isEmpty()) {
                    ItemStack cur = player.getInventory().getItem(inv);
                    int n = hasControlDown() ? cur.getCount() : 1;
                    mc.gameMode.handleCreativeModeItemDrop(cur.copyWithCount(n));
                    setInv(inv, cur.getCount() - n <= 0 ? ItemStack.EMPTY : cur.copyWithCount(cur.getCount() - n));
                }
                return true;
            }
            ItemStack hov = !grid.isEmpty() ? grid : inv >= 0 && inv <= 40 ? player.getInventory().getItem(inv) : ItemStack.EMPTY;
            if (key == GLFW.GLFW_KEY_F && !hov.isEmpty()) {
                toggleFavorite(hov);
                return true;
            }
            if (key == GLFW.GLFW_KEY_W && !hov.isEmpty()) { // в мастерскую
                loadWork(hov);
                selectTab(tabs.stream().filter(t -> t.kind() == Kind.WORKSHOP).findFirst().orElse(tab), true);
                return true;
            }
            if (key == GLFW.GLFW_KEY_TAB) { // следующая вкладка
                int i = tabs.indexOf(tab);
                selectTab(tabs.get(Math.floorMod(i + (hasShiftDown() ? -1 : 1), tabs.size())), true);
                return true;
            }
            if (mc.options.keyInventory.matches(key, scan)) {
                onClose();
                return true;
            }
            if (key == GLFW.GLFW_KEY_SLASH) {
                setFocused(search);
                search.setFocused(true);
                return true;
            }
        }
        if (typing() && key == GLFW.GLFW_KEY_ENTER) {
            for (EditBox b : new EditBox[]{search, nickBox, nameBox, loreBox}) if (b != null) b.setFocused(false);
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (!typing() && !Character.isWhitespace(c) && c != '/' && !Character.isDigit(c)
                && "qfewQFEW".indexOf(c) < 0 && tab.kind() != Kind.WORKSHOP) {
            setFocused(search);
            search.setFocused(true);
        }
        return super.charTyped(c, mods);
    }

    // ================================================================== жизненный цикл

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode == null || !mc.gameMode.hasInfiniteItems()) {
            mc.setScreen(new InventoryScreen(player));
            return;
        }
        for (EditBox b : new EditBox[]{search, nickBox, nameBox, loreBox}) if (b != null) b.tick();
    }

    @Override
    public void removed() {
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

    /** Для автоматических скриншотов: мастерская с примером. */
    public void showcaseWorkshop() {
        loadWork(new ItemStack(Items.DIAMOND_SWORD));
        workName = "Excalibur";
        workLore = "Arc HUD";
        workGlint = true;
        workUnbreakable = true;
        showcaseTab("WORKSHOP");
    }

    public void showcaseInventory(boolean open) {
        invOpen = open;
    }

    public void showcaseList(boolean list) {
        listView = list;
    }

    /** Для автоматических скриншотов. */
    public void showcaseTab(String kind) {
        tabs.stream().filter(t -> t.kind().name().equals(kind)).findFirst().ifPresent(t -> selectTab(t, false));
    }

    private void click() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }
}
