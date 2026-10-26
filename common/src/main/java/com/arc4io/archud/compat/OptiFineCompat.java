package com.arc4io.archud.compat;

/** Установлен ли OptiFine (он не числится в списке модов Forge — проверяем по его классу). */
public final class OptiFineCompat {
    private static Boolean present;

    private OptiFineCompat() {}

    public static boolean present() {
        if (present == null) {
            boolean p;
            try {
                Class.forName("net.optifine.Config", false, OptiFineCompat.class.getClassLoader());
                p = true;
            } catch (Throwable t) {
                p = false;
            }
            present = p;
        }
        return present;
    }
}
