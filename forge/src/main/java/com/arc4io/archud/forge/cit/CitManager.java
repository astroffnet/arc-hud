package com.arc4io.archud.forge.cit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Правила CIT: публикуются при перезагрузке ресурсов, подбираются при отрисовке предмета. */
public final class CitManager {
    private static volatile Map<Item, List<CitRule>> byItem = Map.of();
    private static volatile List<ResourceLocation> models = List.of();

    private CitManager() {}

    static void publish(List<CitRule> rules) {
        Map<Item, List<CitRule>> map = new HashMap<>();
        List<ResourceLocation> locs = new ArrayList<>();
        for (CitRule r : rules) {
            locs.add(r.model);
            for (Item i : r.items) map.computeIfAbsent(i, k -> new ArrayList<>()).add(r);
        }
        // сначала больший вес, при равенстве — правило из более позднего файла
        for (List<CitRule> l : map.values()) {
            l.sort(Comparator.comparingInt((CitRule r) -> r.weight).reversed().thenComparing((CitRule r) -> r.source, Comparator.reverseOrder()));
        }
        byItem = map;
        models = locs;
    }

    /** Модели для регистрации (ModelEvent.RegisterAdditional). */
    public static List<ResourceLocation> models() {
        return models;
    }

    /** Модель CIT для предмета или null. */
    public static BakedModel find(ItemStack stack) {
        if (stack.isEmpty()) return null;
        List<CitRule> rules = byItem.get(stack.getItem());
        if (rules == null) return null;
        for (CitRule r : rules) {
            if (!r.matches(stack)) continue;
            ModelManager mm = Minecraft.getInstance().getModelManager();
            BakedModel m = mm.getModel(r.model);
            return m == mm.getMissingModel() ? null : m;
        }
        return null;
    }
}
