package com.arc4io.archud.client.jei;

import com.arc4io.archud.client.ArcClient;
import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Theme;
import com.arc4io.archud.client.recipes.RecipeCard;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawablesView;
import mezz.jei.api.gui.ingredient.IRecipeSlotRichTooltipCallback;
import mezz.jei.api.gui.ingredient.IRecipeSlotTooltipCallback;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.gui.placement.IPlaceable;
import mezz.jei.api.gui.widgets.IDrawableWidget;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.gui.widgets.IRecipeWidget;
import mezz.jei.api.gui.widgets.IScrollBoxWidget;
import mezz.jei.api.gui.widgets.IScrollGridWidget;
import mezz.jei.api.gui.widgets.ITextWidget;
import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.material.Fluid;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Рецепт из категории мода (через API JEI), нарисованный так, как его задумал мод: фон, слоты, виджеты, draw(). */
@SuppressWarnings({"rawtypes", "unchecked"})
final class JeiCard implements RecipeCard {
    /** Виджет из createRecipeExtras: картинка, текст, область подсказки, свой виджет мода. */
    private static final class Widget {
        int x, y, w, h;
        IDrawable drawable;
        List<FormattedText> text;
        int color = 0xFF404040;
        boolean shadow;
        int align; // 0 — слева, 1 — по центру, 2 — справа
        List<Component> tooltip;
        IRecipeWidget custom;
        Runnable relayout;
    }

    private final IRecipeCategory cat;
    private final Object recipe;
    private final List<JeiSlot> slots;
    private final List<Widget> widgets;
    private final int width, height;
    private final IRecipeSlotsView slotsView;

    private JeiCard(IRecipeCategory cat, Object recipe, List<JeiSlot> slots, List<Widget> widgets, int w, int h) {
        this.cat = cat;
        this.recipe = recipe;
        this.slots = slots;
        this.widgets = widgets;
        this.width = w;
        this.height = h;
        List<mezz.jei.api.gui.ingredient.IRecipeSlotView> views = new ArrayList<>();
        for (JeiSlot s : slots) views.add(s.view());
        this.slotsView = Stub.of(IRecipeSlotsView.class).on("getSlotViews", 0, a -> views).build();
    }

    static JeiCard create(IRecipeCategory cat, Object recipe, IJeiHelpers helpers) {
        try {
            if (!cat.isHandled(recipe)) return null;
        } catch (Throwable ignored) {
        }
        List<JeiSlot> slots = new ArrayList<>();
        boolean[] shapeless = {false};
        cat.setRecipe(JeiSlot.layout(slots, shapeless), recipe, JeiHelpers.emptyFocus());
        List<Widget> widgets = new ArrayList<>();
        try {
            cat.createRecipeExtras(extras(widgets, slots), recipe, JeiHelpers.emptyFocus());
        } catch (Throwable ignored) {
        }
        int w = 0, h = 0;
        try {
            w = cat.getWidth();
            h = cat.getHeight();
        } catch (Throwable ignored) {
        }
        if (w <= 0 || h <= 0) {
            for (JeiSlot s : slots) {
                w = Math.max(w, s.x + s.w + 1);
                h = Math.max(h, s.y + s.h + 1);
            }
            for (Widget wd : widgets) {
                w = Math.max(w, wd.x + wd.w);
                h = Math.max(h, wd.y + wd.h);
            }
        }
        return new JeiCard(cat, recipe, slots, widgets, Math.max(18, w), Math.max(18, h));
    }

    List<List<ItemStack>> items(RecipeIngredientRole role) {
        List<List<ItemStack>> out = new ArrayList<>();
        for (JeiSlot s : slots) {
            if (s.role != role) continue;
            List<ItemStack> l = s.itemStacks();
            if (!l.isEmpty()) out.add(l);
        }
        return out;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    // ------------------------------------------------------------------ отрисовка

    @Override
    public void render(GuiGraphics g, int mx, int my) {
        for (Widget w : widgets) if (w.relayout != null) w.relayout.run();
        IDrawable bg = null;
        try {
            bg = cat.getBackground();
        } catch (Throwable ignored) {
        }
        if (bg != null) {
            try {
                bg.draw(g, 0, 0);
            } catch (Throwable ignored) {
            }
        }
        for (JeiSlot s : slots) {
            if (s.invisible) continue;
            if (s.background != null) {
                s.background.draw(g, s.x + s.bgX, s.y + s.bgY);
            } else if (s.outputBackground) {
                Draw.panel(g, s.x - 5, s.y - 5, s.w + 10, s.h + 10, 0xE0070A0E, Draw.alpha(Theme.accent(), 0xA0));
            } else if (s.standardBackground || bg == null) {
                Draw.panel(g, s.x - 1, s.y - 1, s.w + 2, s.h + 2, 0xE0070A0E, 0x50FFFFFF);
            }
        }
        try {
            cat.draw(recipe, slotsView, g, mx, my);
        } catch (Throwable ignored) {
        }
        for (Widget w : widgets) drawWidget(g, w, mx, my);
        for (JeiSlot s : slots) {
            if (s.invisible) continue;
            Object v = s.displayed();
            if (v != null) drawIngredient(g, v, s.x, s.y, s.w, s.h, s.fluidCapacity);
            if (s.overlay != null) s.overlay.draw(g, s.x + s.ovX, s.y + s.ovY);
            if (mx >= s.x && mx < s.x + s.w && my >= s.y && my < s.y + s.h) {
                g.fill(s.x, s.y, s.x + s.w, s.y + s.h, 0x50FFFFFF);
            }
        }
    }

    private static void drawWidget(GuiGraphics g, Widget w, int mx, int my) {
        try {
            if (w.drawable != null) w.drawable.draw(g, w.x, w.y);
            if (w.text != null) {
                var font = Minecraft.getInstance().font;
                int yy = w.y;
                for (FormattedText t : w.text) {
                    for (FormattedCharSequence line : font.split(t, Math.max(10, w.w))) {
                        int lw = font.width(line);
                        int xx = w.align == 1 ? w.x + (w.w - lw) / 2 : w.align == 2 ? w.x + w.w - lw : w.x;
                        g.drawString(font, line, xx, yy, w.color, w.shadow);
                        yy += 10;
                    }
                }
            }
            if (w.custom != null) {
                var pos = w.custom.getPosition();
                g.pose().pushPose();
                g.pose().translate(pos.x(), pos.y(), 0);
                w.custom.drawWidget(g, mx - pos.x(), my - pos.y());
                g.pose().popPose();
            }
        } catch (Throwable ignored) {
        }
    }

    static Fluid fluidOf(Object v) {
        if (v instanceof JeiSlot.FluidValue f) return f.fluid();
        try {
            Object f = v.getClass().getMethod("getFluid").invoke(v);
            return f instanceof Fluid fl ? fl : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static long fluidAmount(Object v) {
        if (v instanceof JeiSlot.FluidValue f) return f.amount();
        try {
            Object a = v.getClass().getMethod("getAmount").invoke(v);
            return a instanceof Number n ? n.longValue() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void drawIngredient(GuiGraphics g, Object v, int x, int y, int w, int h, long capacity) {
        Minecraft mc = Minecraft.getInstance();
        if (v instanceof ItemStack s) {
            g.renderItem(s, x, y);
            g.renderItemDecorations(mc.font, s, x, y);
            return;
        }
        Fluid fluid = fluidOf(v);
        if (fluid != null) {
            long amount = fluidAmount(v);
            int fh = capacity > 0 ? (int) Math.max(1, Math.min(h, h * amount / capacity)) : h;
            drawFluid(g, fluid, x, y + h - fh, w, fh);
            return;
        }
        Object r = JeiHelpers.INGREDIENT_RENDERERS.get(v.getClass());
        if (r == null) {
            for (Map.Entry<Class<?>, Object> e : JeiHelpers.INGREDIENT_RENDERERS.entrySet()) if (e.getKey().isInstance(v)) r = e.getValue();
        }
        if (r instanceof IIngredientRenderer renderer) {
            g.pose().pushPose();
            g.pose().translate(x, y, 0);
            try {
                renderer.render(g, v);
            } catch (Throwable ignored) {
            }
            g.pose().popPose();
        }
    }

    static void drawFluid(GuiGraphics g, Fluid fluid, int x, int y, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        ResourceLocation id = ArcClient.platform().fluidSprite(fluid);
        TextureAtlasSprite sp = id != null ? mc.getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(id)
                : mc.getBlockRenderer().getBlockModelShaper().getParticleIcon(fluid.defaultFluidState().createLegacyBlock());
        int tint = ArcClient.platform().fluidTint(fluid);
        g.enableScissor(x, y, x + w, y + h);
        g.setColor(((tint >> 16) & 255) / 255F, ((tint >> 8) & 255) / 255F, (tint & 255) / 255F, 1F);
        for (int ty = y + h - 16; ty > y - 16; ty -= 16) {
            for (int tx = x; tx < x + w; tx += 16) g.blit(tx, ty, 0, 16, 16, sp);
        }
        g.setColor(1F, 1F, 1F, 1F);
        g.disableScissor();
    }

    // ------------------------------------------------------------------ мышь

    private JeiSlot slotAt(int mx, int my) {
        for (JeiSlot s : slots) {
            if (!s.invisible && mx >= s.x && mx < s.x + s.w && my >= s.y && my < s.y + s.h) return s;
        }
        return null;
    }

    @Override
    public ItemStack stackAt(int mx, int my) {
        JeiSlot s = slotAt(mx, my);
        return s != null && s.displayed() instanceof ItemStack st ? st : ItemStack.EMPTY;
    }

    @Override
    public List<Component> tooltip(int mx, int my) {
        Minecraft mc = Minecraft.getInstance();
        JeiSlot s = slotAt(mx, my);
        if (s != null) {
            Object v = s.displayed();
            if (v == null) return null;
            List<Component> lines = new ArrayList<>();
            if (v instanceof ItemStack st) lines.addAll(Screen.getTooltipFromItem(mc, st));
            else if (fluidOf(v) != null) {
                Fluid f = fluidOf(v);
                Component name;
                try {
                    Object dn = v.getClass().getMethod("getDisplayName").invoke(v);
                    name = dn instanceof Component c ? c : f.defaultFluidState().createLegacyBlock().getBlock().getName();
                } catch (Throwable t) {
                    name = f.defaultFluidState().createLegacyBlock().getBlock().getName();
                }
                lines.add(name);
                lines.add(Component.literal(fluidAmount(v) + " mB").withStyle(sty -> sty.withColor(0xA0A0A0)));
            } else {
                Object r = JeiHelpers.INGREDIENT_RENDERERS.get(v.getClass());
                if (r instanceof IIngredientRenderer renderer) {
                    try {
                        lines.addAll(renderer.getTooltip(v, TooltipFlag.Default.NORMAL));
                    } catch (Throwable ignored) {
                    }
                }
            }
            for (Object cb : s.tooltipCallbacks) {
                try {
                    if (cb instanceof IRecipeSlotRichTooltipCallback rich) {
                        rich.onRichTooltip(s.view(), tooltipBuilder(lines));
                    } else if (cb instanceof IRecipeSlotTooltipCallback old) {
                        old.onTooltip(s.view(), lines);
                    }
                } catch (Throwable ignored) {
                }
            }
            return lines;
        }
        for (Widget w : widgets) {
            if (w.tooltip != null && mx >= w.x && mx < w.x + w.w && my >= w.y && my < w.y + w.h) return w.tooltip;
        }
        List<Component> lines = new ArrayList<>();
        try {
            cat.getTooltip(tooltipBuilder(lines), recipe, slotsView, mx, my);
        } catch (Throwable ignored) {
        }
        return lines.isEmpty() ? null : lines;
    }

    static ITooltipBuilder tooltipBuilder(List<Component> lines) {
        return Stub.of(ITooltipBuilder.class)
                .on("add", 1, a -> {
                    if (a[0] instanceof Component c) lines.add(c);
                    else if (a[0] instanceof FormattedText t) lines.add(Component.literal(t.getString()));
                    return null;
                })
                .on("addAll", 1, a -> {
                    for (Object o : (Collection<?>) a[0]) {
                        if (o instanceof Component c) lines.add(c);
                        else if (o instanceof FormattedText t) lines.add(Component.literal(t.getString()));
                    }
                    return null;
                })
                .on("clear", 0, a -> {
                    lines.clear();
                    return null;
                })
                .on("toLegacyToComponents", 0, a -> lines)
                .on("removeAll", 1, a -> {
                    lines.removeAll((List<?>) a[0]);
                    return null;
                })
                .build();
    }

    // ------------------------------------------------------------------ createRecipeExtras

    private static Object placeable(Class<?> iface, Widget w, List<Widget> out) {
        out.add(w);
        Object[] self = new Object[1];
        self[0] = Stub.of((Class<Object>) iface)
                .on("setPosition", 2, a -> {
                    w.x = (Integer) a[0];
                    w.y = (Integer) a[1];
                    return self[0];
                })
                .on("setPosition", 6, a -> {
                    int ax = (Integer) a[0], ay = (Integer) a[1], aw = (Integer) a[2], ah = (Integer) a[3];
                    String ha = String.valueOf(a[4]), va = String.valueOf(a[5]);
                    w.x = ha.equals("CENTER") ? ax + (aw - w.w) / 2 : ha.equals("RIGHT") ? ax + aw - w.w : ax;
                    w.y = va.equals("CENTER") ? ay + (ah - w.h) / 2 : va.equals("BOTTOM") ? ay + ah - w.h : ay;
                    return self[0];
                })
                .on("getWidth", 0, a -> w.w)
                .on("getHeight", 0, a -> w.h)
                .on("setTooltip", 1, a -> {
                    List<Component> lines = new ArrayList<>();
                    if (a[0] instanceof Component c) {
                        lines.add(c);
                    } else if (a[0] instanceof Collection<?> col) {
                        for (Object o : col) {
                            if (o instanceof Component c) lines.add(c);
                            else if (o instanceof FormattedText t) lines.add(Component.literal(t.getString()));
                        }
                    } else if (a[0] instanceof FormattedText t) {
                        lines.add(Component.literal(t.getString()));
                    }
                    w.tooltip = lines;
                    return self[0];
                })
                .on("setColor", 1, a -> {
                    int c = (Integer) a[0];
                    w.color = (c & 0xFF000000) == 0 ? c | 0xFF000000 : c;
                    return self[0];
                })
                .on("setShadow", 1, a -> {
                    w.shadow = (Boolean) a[0];
                    return self[0];
                })
                .on("setTextAlignment", 1, a -> {
                    String al = String.valueOf(a[0]);
                    if (al.equals("CENTER")) w.align = 1;
                    else if (al.equals("RIGHT")) w.align = 2;
                    else if (al.equals("LEFT")) w.align = 0;
                    return self[0];
                })
                .on("setContents", 1, a -> {
                    if (a[0] instanceof IDrawable d) w.drawable = d;
                    else if (a[0] instanceof List<?> l) w.text = (List<FormattedText>) l;
                    return self[0];
                })
                .build();
        return self[0];
    }

    private static Widget drawableWidget(IDrawable d, int x, int y) {
        Widget w = new Widget();
        w.drawable = d;
        w.x = x;
        w.y = y;
        w.w = d.getWidth();
        w.h = d.getHeight();
        return w;
    }

    private static IRecipeExtrasBuilder extras(List<Widget> out, List<JeiSlot> slots) {
        Map<Object, JeiSlot> slotOf = new IdentityHashMap<>();
        List<IRecipeSlotDrawable> drawables = new ArrayList<>();
        for (JeiSlot s : slots) {
            IRecipeSlotDrawable d = Stub.of(IRecipeSlotDrawable.class)
                    .on("getRole", 0, a -> s.role)
                    .on("getAllIngredients", 0, a -> s.view().getAllIngredients())
                    .on("getAllIngredientsList", 0, a -> s.view().getAllIngredientsList())
                    .on("getDisplayedIngredient", 0, a -> s.view().getDisplayedIngredient())
                    .on("getSlotName", 0, a -> java.util.Optional.ofNullable(s.name))
                    .on("setPosition", 2, a -> {
                        s.x = (Integer) a[0] + 1;
                        s.y = (Integer) a[1] + 1;
                        return null;
                    })
                    .on("getRect", 0, a -> new Rect2i(s.x, s.y, s.w, s.h))
                    .on("getAreaIncludingBackground", 0, a -> new Rect2i(s.x - 1, s.y - 1, s.w + 2, s.h + 2))
                    .build();
            slotOf.put(d, s);
            drawables.add(d);
        }
        IRecipeSlotDrawablesView view = Stub.of(IRecipeSlotDrawablesView.class).on("getSlots", 0, a -> drawables).build();
        return Stub.of(IRecipeExtrasBuilder.class)
                .on("getRecipeSlots", 0, a -> view)
                .on("addDrawable", 3, a -> {
                    out.add(drawableWidget((IDrawable) a[0], (Integer) a[1], (Integer) a[2]));
                    return null;
                })
                .on("addDrawable", 1, a -> placeable(IPlaceable.class, drawableWidget((IDrawable) a[0], 0, 0), out))
                .on("addDrawableWidget", 1, a -> placeable(IDrawableWidget.class, drawableWidget((IDrawable) a[0], 0, 0), out))
                .on("addTooltipArea", 4, a -> {
                    Widget w = new Widget();
                    w.x = (Integer) a[0];
                    w.y = (Integer) a[1];
                    w.w = (Integer) a[2];
                    w.h = (Integer) a[3];
                    return placeable(IDrawableWidget.class, w, out);
                })
                .on("addRecipeArrow", 0, a -> placeable(IPlaceable.class, drawableWidget(JeiDraw.arrow(false), 0, 0), out))
                .on("addRecipeArrowWidget", 0, a -> placeable(IDrawableWidget.class, drawableWidget(JeiDraw.arrow(false), 0, 0), out))
                .on("addRecipePlusSign", 0, a -> placeable(IPlaceable.class, drawableWidget(JeiDraw.plus(), 0, 0), out))
                .on("addRecipePlusSignWidget", 0, a -> placeable(IDrawableWidget.class, drawableWidget(JeiDraw.plus(), 0, 0), out))
                .on("addAnimatedRecipeArrow", 1, a -> placeable(IPlaceable.class, drawableWidget(JeiDraw.animated(JeiDraw.arrow(true),
                        JeiDraw.timer((Integer) a[0], false), mezz.jei.api.gui.drawable.IDrawableAnimated.StartDirection.LEFT), 0, 0), out))
                .on("addAnimatedRecipeArrowWidget", 1, a -> placeable(IDrawableWidget.class, drawableWidget(JeiDraw.animated(JeiDraw.arrow(true),
                        JeiDraw.timer((Integer) a[0], false), mezz.jei.api.gui.drawable.IDrawableAnimated.StartDirection.LEFT), 0, 0), out))
                .on("addAnimatedRecipeFlame", 1, a -> placeable(IPlaceable.class, drawableWidget(JeiDraw.animated(JeiDraw.flame(true),
                        JeiDraw.timer((Integer) a[0], true), mezz.jei.api.gui.drawable.IDrawableAnimated.StartDirection.TOP), 0, 0), out))
                .on("addAnimatedRecipeFlameWidget", 1, a -> placeable(IDrawableWidget.class, drawableWidget(JeiDraw.animated(JeiDraw.flame(true),
                        JeiDraw.timer((Integer) a[0], true), mezz.jei.api.gui.drawable.IDrawableAnimated.StartDirection.TOP), 0, 0), out))
                .on("addText", 3, a -> {
                    Widget w = new Widget();
                    w.text = a[0] instanceof List<?> l ? (List<FormattedText>) l : List.of((FormattedText) a[0]);
                    w.w = (Integer) a[1];
                    w.h = (Integer) a[2];
                    return placeable(ITextWidget.class, w, out);
                })
                .on("addScrollBoxWidget", 4, a -> {
                    Widget w = new Widget();
                    w.w = (Integer) a[0];
                    w.h = (Integer) a[1];
                    w.x = (Integer) a[2];
                    w.y = (Integer) a[3];
                    return placeable(IScrollBoxWidget.class, w, out);
                })
                .on("addScrollGridWidget", 3, a -> {
                    List<?> grid = (List<?>) a[0];
                    int cols = Math.max(1, (Integer) a[1]);
                    Widget w = new Widget();
                    w.w = cols * 18;
                    w.h = Math.max(1, (Integer) a[2]) * 18;
                    Object p = placeable(IScrollGridWidget.class, w, out);
                    // слоты ставим сеткой от позиции виджета (в момент отрисовки — позиция уже известна)
                    Runnable relayout = () -> {
                        for (int i = 0; i < grid.size(); i++) {
                            JeiSlot s = slotOf.get(grid.get(i));
                            if (s == null) continue;
                            s.x = w.x + (i % cols) * 18 + 1;
                            s.y = w.y + (i / cols) * 18 + 1;
                        }
                    };
                    relayout.run();
                    w.relayout = relayout;
                    return p;
                })
                .on("addWidget", 1, a -> {
                    Widget w = new Widget();
                    w.custom = (IRecipeWidget) a[0];
                    out.add(w);
                    return null;
                })
                .on("addSlottedWidget", 2, a -> {
                    Widget w = new Widget();
                    w.custom = (IRecipeWidget) a[0];
                    out.add(w);
                    return null;
                })
                .build();
    }
}
