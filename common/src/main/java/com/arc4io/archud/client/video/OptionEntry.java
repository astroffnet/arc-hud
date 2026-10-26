package com.arc4io.archud.client.video;

import com.arc4io.archud.mixin.OptionInstanceAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.util.List;

/** Настройка ванилы / OptiFine (OptionInstance) в своём виде: переключатель, ползунок или список. */
@SuppressWarnings({"unchecked", "rawtypes"})
public final class OptionEntry implements VideoEntry {
    private final OptionInstance opt;
    private final Kind kind;
    private List<Object> cycleValues;
    private int min, max;
    private boolean unit;

    public OptionEntry(OptionInstance<?> option) {
        this.opt = option;
        Object values = opt.values();
        Kind k = Kind.WIDGET;
        try {
            if (values instanceof OptionInstance.Enum e) cycleValues = e.valueListSupplier().getSelectedList();
            else if (values instanceof OptionInstance.AltEnum e) cycleValues = e.valueListSupplier().getSelectedList();
            else if (values instanceof OptionInstance.LazyEnum e) cycleValues = e.valueListSupplier().getSelectedList();
            else if (values instanceof OptionInstance.IntRange r) {
                min = r.minInclusive();
                max = r.maxInclusive();
                k = Kind.SLIDER;
            } else if (values instanceof OptionInstance.ClampingLazyMaxIntRange r) {
                min = r.minInclusive();
                max = r.maxInclusive();
                k = Kind.SLIDER;
            } else if (values instanceof OptionInstance.UnitDouble) {
                unit = true;
                k = Kind.SLIDER;
            }
        } catch (Throwable ignored) {
            k = Kind.WIDGET;
        }
        if (cycleValues != null && !cycleValues.isEmpty()) {
            k = cycleValues.size() == 2 && cycleValues.get(0) instanceof Boolean ? Kind.TOGGLE : Kind.CYCLE;
        }
        this.kind = k;
    }

    private OptionInstanceAccessor acc() {
        return (OptionInstanceAccessor) (Object) opt;
    }

    @Override
    public Component name() {
        return acc().archud$caption();
    }

    @Override
    public Component description() {
        try {
            Tooltip t = acc().archud$tooltip().apply(opt.get());
            if (t == null) return Component.empty();
            MutableComponent out = Component.empty();
            boolean first = true;
            for (FormattedCharSequence line : t.toCharSequence(Minecraft.getInstance())) {
                StringBuilder b = new StringBuilder();
                line.accept((i, style, cp) -> {
                    b.appendCodePoint(cp);
                    return true;
                });
                if (!first) out.append(" ");
                out.append(b.toString());
                first = false;
            }
            return out;
        } catch (Throwable e) {
            return Component.empty();
        }
    }

    @Override
    public Component valueText() {
        try {
            Component full = acc().archud$toString().apply(opt.get());
            String caption = name().getString();
            String s = full.getString();
            if (s.startsWith(caption + ": ")) return Component.literal(s.substring(caption.length() + 2));
            return full;
        } catch (Throwable e) {
            return Component.literal(String.valueOf(opt.get()));
        }
    }

    @Override
    public Kind kind() {
        return kind;
    }

    @Override
    public boolean bool() {
        return Boolean.TRUE.equals(opt.get());
    }

    @Override
    public void setBool(boolean v) {
        opt.set(v);
    }

    @Override
    public double fraction() {
        Object v = opt.get();
        if (unit) return v instanceof Double d ? d : 0;
        if (v instanceof Integer i && max > min) return (i - min) / (double) (max - min);
        return 0;
    }

    @Override
    public void setFraction(double f) {
        f = Mth.clamp(f, 0, 1);
        if (unit) opt.set(Math.round(f * 100) / 100.0);
        else opt.set(min + (int) Math.round(f * (max - min)));
    }

    @Override
    public int steps() {
        return unit ? 100 : Math.max(1, max - min);
    }

    @Override
    public void cycle(int dir) {
        if (cycleValues == null || cycleValues.isEmpty()) return;
        int i = cycleValues.indexOf(opt.get());
        opt.set(cycleValues.get(Math.floorMod(i + dir, cycleValues.size())));
    }

    @Override
    public AbstractWidget createWidget(int x, int y, int w) {
        return opt.createButton(Minecraft.getInstance().options, x, y, w);
    }

    public OptionInstance<?> option() {
        return opt;
    }
}
