package com.arc4io.archud.client.mods;

import net.minecraft.client.gui.screens.Screen;

import java.io.InputStream;
import java.util.function.Function;

/**
 * Мод для общего списка модов (одинаковый экран на Forge и Fabric).
 *
 * @param logo   открыть картинку логотипа (null — нет)
 * @param config экран настроек мода (null — нет)
 * @param parent id «родительского» мода (модули Fabric API, вложенные моды) — такие свёрнуты под родителем
 */
public record ModEntry(String id, String name, String version, String description, String authors, String url,
                       boolean library, Logo logo, Function<Screen, Screen> config, String parent) {
    @FunctionalInterface
    public interface Logo {
        InputStream open() throws Exception;
    }
}
