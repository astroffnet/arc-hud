package com.arc4io.archud.client.map;

/**
 * Метка на карте.
 */
public class Waypoint {
    public enum Source {USER, DEATH, WAYSTONE, TEMPAD, SPAWN, TEMP}

    public static final int[] PALETTE = {0xFF4FC3F7, 0xFFFF5252, 0xFF9CFF5A, 0xFFFFD54F, 0xFFB388FF, 0xFFFF8A65, 0xFFFFFFFF, 0xFF26C6DA,
            0xFFF06292, 0xFF8D6E63};

    public String name;
    public int x, y, z;
    public int color;
    public String dimension;
    public Source source = Source.USER;
    public boolean visible = true;
    /** Внешний id (например UUID локации Tempad). */
    public String extId;
    /** Время создания (мс) — для истории смертей. */
    public long created = System.currentTimeMillis();

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
        return source == Source.USER || source == Source.DEATH || source == Source.TEMP;
    }

    /** Буквы на значке метки (как в Xaero): первые буквы слов имени. */
    public String initials() {
        if (source == Source.DEATH) return "✝";
        if (source == Source.SPAWN) return "⌂";
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) return "?";
        String[] parts = n.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (!p.isEmpty() && Character.isLetterOrDigit(p.charAt(0))) sb.appendCodePoint(Character.toUpperCase(p.codePointAt(0)));
            if (sb.length() >= 2) break;
        }
        return sb.length() == 0 ? n.substring(0, 1) : sb.toString();
    }

    public double distanceTo(double px, double py, double pz) {
        double dx = x + 0.5 - px, dy = y - py, dz = z + 0.5 - pz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public double horizontalDistance(double px, double pz) {
        double dx = x + 0.5 - px, dz = z + 0.5 - pz;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
