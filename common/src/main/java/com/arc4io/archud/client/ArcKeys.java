package com.arc4io.archud.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Клавиши мода. Все переназначаются в настройках управления (в т.ч. в новом экране Arc HUD).
 */
public final class ArcKeys {
    public static final String CATEGORY = "key.categories.archud";

    public static final KeyMapping OPEN_BACKPACK = new KeyMapping("key.archud.backpack", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, CATEGORY);
    public static final KeyMapping TOGGLE_HUD = new KeyMapping("key.archud.toggle_hud", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, CATEGORY);
    public static final KeyMapping TOGGLE_BHOP = new KeyMapping("key.archud.toggle_bhop", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, CATEGORY);
    public static final KeyMapping OPEN_CONFIG = new KeyMapping("key.archud.config", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, CATEGORY);
    public static final KeyMapping SORT_INVENTORY = new KeyMapping("key.archud.sort", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY);

    public static final List<KeyMapping> ALL = List.of(OPEN_BACKPACK, TOGGLE_HUD, TOGGLE_BHOP, OPEN_CONFIG, SORT_INVENTORY);

    private ArcKeys() {}
}
