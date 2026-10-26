package com.arc4io.archud.client.extras;

import com.arc4io.archud.config.ArcConfig;
import com.arc4io.archud.mixin.ClientTextTooltipAccessor;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTextTooltip;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * Перенос длинных подсказок. Подсказка ставится справа от курсора, а если не влезает — слева; если строка
 * длиннее и того, и другого места, ванила уводит её за край экрана. Здесь такие строки переносятся по ширине
 * большей из сторон (картинки в подсказках — карты, предметы мешка — не трогаются).
 */
public final class TooltipWrap {
    /** Защита от повторного переноса при вложенном вызове. */
    public static boolean inside;

    private TooltipWrap() {}

    /** Новый список строк или null, если переносить не нужно. */
    public static List<ClientTooltipComponent> wrap(Font font, List<ClientTooltipComponent> lines, int x, int screenW) {
        if (inside || lines.isEmpty() || !ArcConfig.get().tooltipWrap) return null;
        int right = screenW - x - 12 - 8, left = x - 12 - 8;
        int max = Math.max(Math.max(right, left), Math.min(200, screenW - 16));
        int widest = 0;
        for (ClientTooltipComponent c : lines) widest = Math.max(widest, c.getWidth(font));
        if (widest <= max) return null;
        List<ClientTooltipComponent> out = new ArrayList<>(lines.size() + 4);
        boolean changed = false;
        for (ClientTooltipComponent c : lines) {
            if (c instanceof ClientTextTooltip t && c.getWidth(font) > max) {
                for (FormattedCharSequence part : font.split(toComponent(((ClientTextTooltipAccessor) t).archud$text()), max)) {
                    out.add(ClientTooltipComponent.create(part));
                }
                changed = true;
            } else {
                out.add(c);
            }
        }
        return changed ? out : null;
    }

    /** FormattedCharSequence → Component с сохранением стилей (цвет, жирность и т. п.). */
    private static Component toComponent(FormattedCharSequence seq) {
        MutableComponent root = Component.empty();
        StringBuilder sb = new StringBuilder();
        Style[] cur = {null};
        seq.accept((index, style, cp) -> {
            if (cur[0] != null && !style.equals(cur[0])) {
                root.append(Component.literal(sb.toString()).withStyle(cur[0]));
                sb.setLength(0);
            }
            cur[0] = style;
            sb.appendCodePoint(cp);
            return true;
        });
        if (sb.length() > 0) root.append(Component.literal(sb.toString()).withStyle(cur[0] == null ? Style.EMPTY : cur[0]));
        return root;
    }
}
