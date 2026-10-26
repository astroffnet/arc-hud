package com.arc4io.archud.client.video;

import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import static com.arc4io.archud.client.video.SodiumBridge.call;
import static com.arc4io.archud.client.video.SodiumBridge.field;

/** Настройка Sodium / Embeddium в своём виде (TickBox → переключатель, Slider → ползунок, Cycling → список). */
public final class SodiumEntry implements VideoEntry {
    final Object opt;
    private final Kind kind;
    private int min, max, interval = 1;
    private Object formatter;
    private Object[] values;
    private Component[] names;

    SodiumEntry(Object opt) {
        this.opt = opt;
        Kind k = Kind.READONLY;
        try {
            Object control = call(opt, "getControl");
            String cn = control.getClass().getSimpleName();
            if (cn.equals("TickBoxControl")) k = Kind.TOGGLE;
            else if (cn.equals("SliderControl")) {
                min = (Integer) field(control, "min");
                max = (Integer) field(control, "max");
                Object iv = field(control, "interval");
                interval = iv instanceof Integer i && i > 0 ? i : 1;
                formatter = field(control, "mode");
                k = Kind.SLIDER;
            } else if (cn.equals("CyclingControl")) {
                values = (Object[]) field(control, "allowedValues");
                Object n = field(control, "names");
                names = n instanceof Component[] c ? c : null;
                k = Kind.CYCLE;
            } else {
                Object v = call(opt, "getValue");
                if (v instanceof Boolean) k = Kind.TOGGLE;
                else if (v instanceof Enum<?> e) {
                    values = e.getDeclaringClass().getEnumConstants();
                    k = Kind.CYCLE;
                }
            }
        } catch (Throwable ignored) {
        }
        this.kind = k;
    }

    private Object value() {
        try {
            return call(opt, "getValue");
        } catch (Throwable e) {
            return null;
        }
    }

    private void set(Object v) {
        try {
            call(opt, "setValue", v);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public Component name() {
        try {
            return (Component) call(opt, "getName");
        } catch (Throwable e) {
            return Component.literal("?");
        }
    }

    @Override
    public Component description() {
        try {
            Object t = call(opt, "getTooltip");
            return t instanceof Component c ? c : Component.empty();
        } catch (Throwable e) {
            return Component.empty();
        }
    }

    @Override
    public Component valueText() {
        Object v = value();
        try {
            if (kind == Kind.TOGGLE) return Component.translatable(Boolean.TRUE.equals(v) ? "options.on" : "options.off");
            if (kind == Kind.SLIDER && formatter != null) return (Component) call(formatter, "format", v);
            if (values != null) {
                for (int i = 0; i < values.length; i++) {
                    if (values[i] == v && names != null && i < names.length && names[i] != null) return names[i];
                }
                return localized(v);
            }
        } catch (Throwable ignored) {
        }
        return Component.literal(String.valueOf(v));
    }

    private static Component localized(Object v) {
        try {
            Object c = call(v, "getLocalizedName");
            if (c instanceof Component comp) return comp;
        } catch (Throwable ignored) {
        }
        return Component.literal(String.valueOf(v));
    }

    @Override
    public Kind kind() {
        return kind;
    }

    @Override
    public boolean available() {
        try {
            return (Boolean) call(opt, "isAvailable");
        } catch (Throwable e) {
            return true;
        }
    }

    @Override
    public Component impact() {
        try {
            Object i = call(opt, "getImpact");
            return i == null ? null : localized(i);
        } catch (Throwable e) {
            return null;
        }
    }

    @Override
    public boolean changed() {
        try {
            return (Boolean) call(opt, "hasChanged");
        } catch (Throwable e) {
            return false;
        }
    }

    public void reset() {
        try {
            call(opt, "reset");
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean bool() {
        return Boolean.TRUE.equals(value());
    }

    @Override
    public void setBool(boolean v) {
        set(v);
    }

    @Override
    public double fraction() {
        Object v = value();
        return v instanceof Integer i && max > min ? (i - min) / (double) (max - min) : 0;
    }

    @Override
    public void setFraction(double f) {
        int raw = min + (int) Math.round(Mth.clamp(f, 0, 1) * (max - min));
        int snapped = min + Math.round((raw - min) / (float) interval) * interval;
        set(Mth.clamp(snapped, min, max));
    }

    @Override
    public int steps() {
        return Math.max(1, (max - min) / interval);
    }

    @Override
    public void cycle(int dir) {
        if (values == null || values.length == 0) return;
        Object v = value();
        int i = 0;
        for (int k = 0; k < values.length; k++) if (values[k] == v) i = k;
        set(values[Math.floorMod(i + dir, values.length)]);
    }
}
