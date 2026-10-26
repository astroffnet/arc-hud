package com.arc4io.archud.client.video;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

/** Одна настройка на экране графики (ванилла / Sodium / OptiFine — общий вид). */
public interface VideoEntry {
    enum Kind { TOGGLE, SLIDER, CYCLE, WIDGET, BUTTON, READONLY }

    Component name();

    Component description();

    Component valueText();

    Kind kind();

    default boolean available() {
        return true;
    }

    /** Влияние на производительность (Sodium) или null. */
    default Component impact() {
        return null;
    }

    default boolean changed() {
        return false;
    }

    // TOGGLE
    default boolean bool() {
        return false;
    }

    default void setBool(boolean v) {}

    // SLIDER: доля 0..1 и число шагов
    default double fraction() {
        return 0;
    }

    default void setFraction(double f) {}

    default int steps() {
        return 100;
    }

    // CYCLE
    default void cycle(int dir) {}

    // WIDGET (ванильный виджет, если вид настройки неизвестен)
    default AbstractWidget createWidget(int x, int y, int w) {
        return null;
    }

    // BUTTON (открыть другой экран, например шейдеры OptiFine)
    default void press() {}
}
