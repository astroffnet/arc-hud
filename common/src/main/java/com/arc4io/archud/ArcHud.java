package com.arc4io.archud;

import com.arc4io.archud.config.ArcConfig;
import com.arc4io.archud.platform.Platform;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

/**
 * Arc HUD — общий вход мода (Forge и Fabric).
 * Автор: arc4io
 */
public final class ArcHud {
    public static final String MODID = "archud";
    public static final String NAME = "Arc HUD";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static Platform platform;

    private ArcHud() {}

    public static void init(Platform p) {
        platform = p;
        ArcConfig.load();
        com.arc4io.archud.server.ArcServer.init();
        LOGGER.info("{} by arc4io loaded on {}", NAME, p.loaderName());
    }

    public static Platform platform() {
        return platform;
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MODID, path);
    }
}
