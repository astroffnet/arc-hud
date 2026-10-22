package com.arc4io.archud.client.hud;

import com.arc4io.archud.config.ArcConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * Цветовая тема HUD. Акцентный цвет плавно меняется в зависимости от измерения:
 * Overworld, Nether, End, Aether, Twilight Forest, DivineRPG и любые модовые измерения (цвет по хэшу).
 */
public final class Theme {
    public static final int DEFAULT_ACCENT = 0xFF4FC3F7;
    private static int current = DEFAULT_ACCENT;
    private static int target = DEFAULT_ACCENT;

    private Theme() {}

    public static int accent() {
        return current;
    }

    public static void update(Minecraft mc) {
        if (mc.level == null || !ArcConfig.get().dimensionThemes) {
            target = DEFAULT_ACCENT;
        } else {
            target = forDimension(mc.level.dimension().location());
        }
        current = Draw.mix(current, target, 0.08F);
    }

    public static int forDimension(ResourceLocation dim) {
        String ns = dim.getNamespace();
        String path = dim.getPath();
        if (ns.equals("minecraft")) {
            return switch (path) {
                case "the_nether" -> 0xFFFF7043;
                case "the_end" -> 0xFFB388FF;
                default -> DEFAULT_ACCENT;
            };
        }
        return switch (ns) {
            case "aether", "aether_redux", "deep_aether" -> 0xFFFFD54F;
            case "twilightforest" -> 0xFF81C784;
            case "divinerpg" -> switch (path) {
                case "eden" -> 0xFFFFE082;
                case "wildwood" -> 0xFF4DD0E1;
                case "apalachia" -> 0xFFCE93D8;
                case "skythern" -> 0xFFE0E0E0;
                case "mortum" -> 0xFF90A4AE;
                case "vethea" -> 0xFF26A69A;
                case "iceika" -> 0xFF81D4FA;
                case "arcana" -> 0xFF7E57C2;
                default -> 0xFFE040FB;
            };
            case "undergarden" -> 0xFF9CCC65;
            case "blue_skies" -> 0xFF64B5F6;
            case "ad_astra", "stellaris", "beyond_earth" -> 0xFFB0BEC5;
            default -> 0xFF000000 | net.minecraft.util.Mth.hsvToRgb((dim.hashCode() & 0xFFFF) / 65535F, 0.55F, 0.95F);
        };
    }
}
