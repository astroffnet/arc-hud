package com.arc4io.archud.client.hud;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Публичный API Arc HUD для аддонов и совместимости.
 *
 * Полоса (мана, выносливость, энергия...) — рисуется над полосами здоровья в стиле Arc HUD:
 * <pre>
 * ArcHudApi.registerBar(new ResourceLocation("mymod", "mana"), Component.literal("Мана"), 0xFF7E57C2,
 *         () -> new float[]{ManaData.get(), ManaData.max()});
 * </pre>
 * Панель (список способностей, задач и т.п.) — отдельный блок, который игрок ставит в любой край экрана.
 * Если supplier возвращает null — элемент скрыт.
 */
public final class ArcHudApi {
    public enum Side {LEFT, RIGHT}

    /** main = большая полоса в стиле HP/еды (LEFT — над HP, RIGHT — над едой), иначе тонкая слева. */
    public record Bar(ResourceLocation id, Component label, int color, Supplier<float[]> values, boolean main, Side side, int icon) {}

    /** Строка панели: текст слева, текст справа, прогресс 0..1 (или -1 без полосы), цвет, выделение. */
    public record PanelRow(String text, String right, float progress, int color, boolean highlight) {}

    public record Panel(ResourceLocation id, Component title, int color, Supplier<List<PanelRow>> rows) {}

    private static final List<Bar> BARS = new CopyOnWriteArrayList<>();
    private static final List<Panel> PANELS = new CopyOnWriteArrayList<>();
    private static final Set<String> REPLACED_OVERLAYS = ConcurrentHashMap.newKeySet();

    private ArcHudApi() {}

    public static void registerBar(ResourceLocation id, Component label, int argbColor, Supplier<float[]> valueAndMax) {
        BARS.removeIf(b -> b.id().equals(id));
        BARS.add(new Bar(id, label, argbColor, valueAndMax, false, Side.LEFT, Icons.STAR));
    }

    /** Большая полоса в стиле HP/еды. icon — индекс из {@link Icons}. */
    public static void registerMainBar(ResourceLocation id, Component label, int argbColor, int icon, Side side, Supplier<float[]> valueAndMax) {
        BARS.removeIf(b -> b.id().equals(id));
        BARS.add(new Bar(id, label, argbColor, valueAndMax, true, side, icon));
    }

    public static void unregisterBar(ResourceLocation id) {
        BARS.removeIf(b -> b.id().equals(id));
    }

    public static List<Bar> bars() {
        return BARS;
    }

    public static void registerPanel(ResourceLocation id, Component title, int argbColor, Supplier<List<PanelRow>> rows) {
        PANELS.removeIf(p -> p.id().equals(id));
        PANELS.add(new Panel(id, title, argbColor, rows));
    }

    public static List<Panel> panels() {
        return PANELS;
    }

    /** Оверлей другого мода, который теперь рисует Arc HUD (оригинал будет скрыт). */
    public static void markReplaced(String overlayId) {
        REPLACED_OVERLAYS.add(overlayId);
    }

    public static boolean isReplaced(String overlayId) {
        return REPLACED_OVERLAYS.contains(overlayId);
    }
}
