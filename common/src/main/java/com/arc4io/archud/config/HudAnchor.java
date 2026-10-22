package com.arc4io.archud.config;

/**
 * Края/углы экрана, к которым можно прикрепить блоки HUD.
 * Центр и низ-центр не используются: там прицел и хотбар.
 */
public enum HudAnchor {
    TOP_LEFT(0, 0),
    TOP_CENTER(1, 0),
    TOP_RIGHT(2, 0),
    LEFT(0, 1),
    RIGHT(2, 1),
    BOTTOM_LEFT(0, 2),
    BOTTOM_RIGHT(2, 2);

    public final int col; // 0 лево, 1 центр, 2 право
    public final int row; // 0 верх, 1 середина, 2 низ

    HudAnchor(int col, int row) {
        this.col = col;
        this.row = row;
    }

    public static HudAnchor at(int col, int row) {
        for (HudAnchor a : values()) if (a.col == col && a.row == row) return a;
        return null;
    }

    public HudAnchor next(int dir) {
        HudAnchor[] v = values();
        return v[(ordinal() + dir + v.length) % v.length];
    }
}
