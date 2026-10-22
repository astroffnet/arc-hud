package com.arc4io.archud.client.map;

/**
 * Метка на карте.
 */
public class Waypoint {
    public enum Source {USER, DEATH, WAYSTONE, TEMPAD}

    public static final int[] PALETTE = {0xFF4FC3F7, 0xFFFF5252, 0xFF9CFF5A, 0xFFFFD54F, 0xFFB388FF, 0xFFFF8A65, 0xFFFFFFFF, 0xFF26C6DA};

    public String name;
    public int x, y, z;
    public int color;
    public String dimension;
    public Source source = Source.USER;
    public boolean visible = true;

    public Waypoint() {}

    public Waypoint(String name, int x, int y, int z, int color, String dimension, Source source) {
        this.name = name;
        this.x = x;
        this.y = y;
        this.z = z;
        this.color = color;
        this.dimension = dimension;
        this.source = source;
    }

    public boolean editable() {
        return source == Source.USER || source == Source.DEATH;
    }

    public double distanceTo(double px, double py, double pz) {
        double dx = x + 0.5 - px, dy = y - py, dz = z + 0.5 - pz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
