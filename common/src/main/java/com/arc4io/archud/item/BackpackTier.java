package com.arc4io.archud.item;

/**
 * Уровни рюкзаков: имя предмета и число рядов по 9 слотов.
 */
public enum BackpackTier {
    LEATHER("leather_backpack", 3),
    IRON("iron_backpack", 4),
    DIAMOND("diamond_backpack", 6);

    private final String itemName;
    private final int rows;

    BackpackTier(String itemName, int rows) {
        this.itemName = itemName;
        this.rows = rows;
    }

    public String itemName() {
        return itemName;
    }

    public int rows() {
        return rows;
    }

    public int size() {
        return rows * 9;
    }
}
