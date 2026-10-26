package com.arc4io.archud.client.perf;

import com.arc4io.archud.config.ArcConfig;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.PrioritizeChunkUpdates;

import java.util.Map;

/**
 * Переключатели оптимизации, которые меняют ванильные настройки на дешёвые (облака, смешивание биомов,
 * плавное освещение, мип-текстуры, графика, тени, обновление чанков). При выключении возвращается прежнее значение.
 */
final class VanillaOpts {
    private VanillaOpts() {}

    static void onChanged(String id, int v) {
        if (!id.startsWith("w_")) return;
        Minecraft mc = Minecraft.getInstance();
        Options o = mc.options;
        if (o == null) return;
        Map<String, Integer> saved = ArcConfig.get().perfRestore;
        boolean on = v != 0;
        try {
            switch (id) {
                case "w_clouds" -> {
                    if (on) {
                        saved.putIfAbsent(id, o.cloudStatus().get().ordinal());
                        o.cloudStatus().set(CloudStatus.OFF);
                    } else if (saved.containsKey(id)) o.cloudStatus().set(CloudStatus.values()[saved.remove(id)]);
                }
                case "w_biome_blend" -> {
                    if (on) {
                        saved.putIfAbsent(id, o.biomeBlendRadius().get());
                        o.biomeBlendRadius().set(0);
                    } else if (saved.containsKey(id)) o.biomeBlendRadius().set(saved.remove(id));
                    if (mc.levelRenderer != null) mc.levelRenderer.allChanged();
                }
                case "w_smooth_light" -> {
                    if (on) {
                        saved.putIfAbsent(id, o.ambientOcclusion().get() ? 1 : 0);
                        o.ambientOcclusion().set(false);
                    } else if (saved.containsKey(id)) o.ambientOcclusion().set(saved.remove(id) != 0);
                    if (mc.levelRenderer != null) mc.levelRenderer.allChanged();
                }
                case "w_mipmaps" -> {
                    int before = o.mipmapLevels().get();
                    if (on) {
                        saved.putIfAbsent(id, before);
                        o.mipmapLevels().set(0);
                    } else if (saved.containsKey(id)) o.mipmapLevels().set(saved.remove(id));
                    if (o.mipmapLevels().get() != before) {
                        mc.updateMaxMipLevel(o.mipmapLevels().get());
                        mc.delayTextureReload();
                    }
                }
                case "w_fast_graphics" -> {
                    if (on) {
                        saved.putIfAbsent(id, o.graphicsMode().get().getId());
                        o.graphicsMode().set(GraphicsStatus.FAST);
                    } else if (saved.containsKey(id)) o.graphicsMode().set(GraphicsStatus.byId(saved.remove(id)));
                }
                case "w_vanilla_shadows" -> {
                    if (on) {
                        saved.putIfAbsent(id, o.entityShadows().get() ? 1 : 0);
                        o.entityShadows().set(false);
                    } else if (saved.containsKey(id)) o.entityShadows().set(saved.remove(id) != 0);
                }
                case "w_chunk_priority" -> {
                    if (on) {
                        saved.putIfAbsent(id, o.prioritizeChunkUpdates().get().getId());
                        o.prioritizeChunkUpdates().set(PrioritizeChunkUpdates.NEARBY);
                    } else if (saved.containsKey(id)) o.prioritizeChunkUpdates().set(PrioritizeChunkUpdates.byId(saved.remove(id)));
                }
                default -> {
                }
            }
            o.save();
        } catch (Throwable ignored) {
        }
    }
}
