package mezz.jei.common.util;

import net.minecraft.client.resources.language.I18n;

import java.util.Locale;

/** Совместимость Arc HUD: переводчик из внутреннего API JEI, которым пользуются плагины модов. */
public final class Translator {
    private Translator() {}

    public static String translateToLocal(String key) {
        return I18n.exists(key) ? I18n.get(key) : key;
    }

    public static String translateToLocalFormatted(String key, Object... format) {
        return I18n.get(key, format);
    }

    public static String toLowercaseWithLocale(String string) {
        return string.toLowerCase(Locale.getDefault());
    }
}
