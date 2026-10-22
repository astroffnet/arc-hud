package com.arc4io.archud.menu;

import com.arc4io.archud.item.BackpackItem;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/**
 * Меню рюкзака на базе ванильного сундука (клиенту не нужен свой экран — работает и с модами на интерфейс).
 * Блокирует перемещение самого открытого рюкзака и вкладывание рюкзаков друг в друга.
 */
public class BackpackMenu extends ChestMenu {
    private final int containerSlots;
    private final int lockedMenuSlot;
    private final int lockedInvSlot;

    public BackpackMenu(int id, Inventory inv, BackpackContainer container, int rows, int lockedInvSlot) {
        super(typeFor(rows), id, inv, container, rows);
        this.containerSlots = rows * 9;
        this.lockedInvSlot = lockedInvSlot;
        this.lockedMenuSlot = menuIndexFor(lockedInvSlot, rows);
    }

    public static MenuType<?> typeFor(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            case 6 -> MenuType.GENERIC_9x6;
            default -> MenuType.GENERIC_9x3;
        };
    }

    /** Индекс слота меню для слота инвентаря игрока (или -1). */
    private static int menuIndexFor(int invSlot, int rows) {
        if (invSlot < 0) return -1;
        int base = rows * 9;
        if (invSlot < 9) return base + 27 + invSlot;   // хотбар
        if (invSlot < 36) return base + (invSlot - 9); // основной инвентарь
        return -1;                                     // броня/вторая рука не отображаются в меню
    }

    private static boolean isBackpack(ItemStack s) {
        return s.getItem() instanceof BackpackItem;
    }

    @Override
    public void clicked(int slotId, int button, ClickType type, Player player) {
        if (lockedMenuSlot >= 0 && slotId == lockedMenuSlot) return;
        if (type == ClickType.SWAP && lockedInvSlot >= 0 && button == lockedInvSlot) return;
        if (slotId >= 0 && slotId < containerSlots) {
            if ((type == ClickType.PICKUP || type == ClickType.QUICK_CRAFT) && isBackpack(getCarried())) return;
            if (type == ClickType.SWAP && button >= 0 && button < player.getInventory().getContainerSize()
                    && isBackpack(player.getInventory().getItem(button))) return;
        }
        if (type == ClickType.QUICK_MOVE && slotId >= containerSlots && slotId < slots.size()
                && isBackpack(slots.get(slotId).getItem())) return;
        super.clicked(slotId, button, type, player);
    }
}
