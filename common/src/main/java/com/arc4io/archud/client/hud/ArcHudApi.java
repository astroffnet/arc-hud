package com.arc4io.archud.client.hud;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Публичный API Arc HUD для аддонов и совместимости.
 * Моды про супергероев, магию, энергию (Legends, DivineRPG Arcana, мана, стамина и т.п.)
 * могут добавить свою полосу в общий стиль HUD:
 *
 * <pre>
 * ArcHudApi.registerBar(new ResourceLocation("mymod", "mana"), Component.literal("Мана"), 0xFF7E57C2,
 *         () -> new float[]{ManaData.get(), ManaData.max()});
 * </pre>
 * Если supplier возвращает null — полоса скрыта.
 */
public final class ArcHudApi {
    public record Bar(ResourceLocation id, Component label, int color, Supplier<float[]> values) {}

    private static final List<Bar> BARS = new CopyOnWriteArrayList<>();

    private ArcHudApi() {}

    public static void registerBar(ResourceLocation id, Component label, int argbColor, Supplier<float[]> valueAndMax) {
        BARS.removeIf(b -> b.id().equals(id));
        BARS.add(new Bar(id, label, argbColor, valueAndMax));
    }

    public static void unregisterBar(ResourceLocation id) {
        BARS.removeIf(b -> b.id().equals(id));
    }

    public static List<Bar> bars() {
        return BARS;
    }
}
