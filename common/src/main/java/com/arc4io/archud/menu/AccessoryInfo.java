package com.arc4io.archud.menu;

import net.minecraft.network.chat.Component;

/** Слот аксессуара (Curios, Aether и др.) в новом инвентаре: группа (тип слота) и подпись. */
public interface AccessoryInfo {
    String group();

    Component label();
}
