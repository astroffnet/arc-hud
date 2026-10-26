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
    public static final KeyMapping OPEN_CONFIG = new KeyMapping("key.archud.config", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, CATEGORY);
    public static final KeyMapping SORT_INVENTORY = new KeyMapping("key.archud.sort", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY);

    public static final KeyMapping WORLD_MAP = new KeyMapping("key.archud.world_map", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, CATEGORY);
    public static final KeyMapping ADD_WAYPOINT = new KeyMapping("key.archud.add_waypoint", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_U, CATEGORY);
    public static final KeyMapping ZOOM_IN = new KeyMapping("key.archud.zoom_in", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_EQUAL, CATEGORY);
    public static final KeyMapping ZOOM_OUT = new KeyMapping("key.archud.zoom_out", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_MINUS, CATEGORY);

    public static final KeyMapping HOST_PANEL = new KeyMapping("key.archud.host_panel", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY);

    public static final KeyMapping WAYPOINTS = new KeyMapping("key.archud.waypoints", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Y, CATEGORY);

    public static final KeyMapping INSPECT = new KeyMapping("key.archud.inspect", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, CATEGORY);

    public static final KeyMapping CAMERA = new KeyMapping("key.archud.camera", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F6, CATEGORY);
    public static final KeyMapping CAMERA_SHOULDER = new KeyMapping("key.archud.camera_shoulder", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY);

    public static final KeyMapping ZOOM = new KeyMapping("key.archud.zoom", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, CATEGORY);
    public static final KeyMapping FREELOOK = new KeyMapping("key.archud.freelook", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT, CATEGORY);
    public static final KeyMapping FULLBRIGHT = new KeyMapping("key.archud.fullbright", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY);
    public static final KeyMapping COPY_COORDS = new KeyMapping("key.archud.copy_coords", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY);
    public static final KeyMapping CLEAN_SCREENSHOT = new KeyMapping("key.archud.clean_screenshot", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY);
    public static final KeyMapping AUTO_SPRINT = new KeyMapping("key.archud.auto_sprint", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY);

    public static final KeyMapping SAVE_WORLD = new KeyMapping("key.archud.save_world", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F7, CATEGORY);

    public static final List<KeyMapping> ALL = List.of(SAVE_WORLD, ZOOM, FREELOOK, FULLBRIGHT, COPY_COORDS, CLEAN_SCREENSHOT, AUTO_SPRINT, CAMERA, CAMERA_SHOULDER, INSPECT, OPEN_BACKPACK, TOGGLE_HUD, TOGGLE_BHOP, OPEN_CONFIG, SORT_INVENTORY,
            WORLD_MAP, ADD_WAYPOINT, WAYPOINTS, ZOOM_IN, ZOOM_OUT, HOST_PANEL);

    private ArcKeys() {}
}
