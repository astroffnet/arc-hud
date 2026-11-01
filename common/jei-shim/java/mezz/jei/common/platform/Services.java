package mezz.jei.common.platform;

/** Совместимость Arc HUD: платформенные помощники JEI, которые реализует Arc HUD (см. {@link IPlatformFluidHelperInternal}). */
public final class Services {
    public static final IPlatformHelper PLATFORM = load();

    private Services() {}

    private static IPlatformHelper load() {
        try {
            ClassLoader cl = Services.class.getClassLoader();
            Class<?> c = Class.forName("com.arc4io.archud.client.jei.JeiInternals", true, cl);
            return (IPlatformHelper) c.getMethod("platform").invoke(null);
        } catch (Throwable t) {
            throw new IllegalStateException("Arc HUD JEI compatibility layer is not available", t);
        }
    }
}
