package com.arc4io.archud.client.fx;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.compat.OptiFineCompat;

/** Включён ли шейдерпак (Iris / Oculus / OptiFine) — тогда свои эффекты и масштаб рендера не применяются. */
public final class ShaderPacks {
    private static java.lang.reflect.Method irisApi;
    private static Object iris;
    private static boolean irisChecked;

    private ShaderPacks() {}

    public static boolean inUse() {
        try {
            if (!irisChecked) {
                irisChecked = true;
                if (ArcHud.platform().isModLoaded("iris") || ArcHud.platform().isModLoaded("oculus")) {
                    Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                    iris = c.getMethod("getInstance").invoke(null);
                    irisApi = c.getMethod("isShaderPackInUse");
                }
            }
            if (iris != null && (Boolean) irisApi.invoke(iris)) return true;
            if (OptiFineCompat.present()) {
                Class<?> cfg = Class.forName("net.optifine.Config");
                return (Boolean) cfg.getMethod("isShaders").invoke(null);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
