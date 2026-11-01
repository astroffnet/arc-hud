package mezz.jei.common.platform;

/** Совместимость Arc HUD (см. {@link IPlatformFluidHelperInternal}). */
public interface IPlatformHelper {
    IPlatformFluidHelperInternal<?> getFluidHelper();
}
