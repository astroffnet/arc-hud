package com.arc4io.archud.menu;

import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;

import java.util.List;

/** Меню, у которого есть «чужие» слоты аксессуаров (например, экипировка Legends Core) без {@link AccessoryInfo}. */
public interface AccessoryProvider {
    List<Slot> extraAccessorySlots();

    /** Подпись пустого слота или null. */
    Component accessoryLabel(Slot slot);

    /** Короткая подпись внутри пустого слота (2–4 буквы) или null. */
    default Component accessoryShort(Slot slot) {
        return null;
    }
}
