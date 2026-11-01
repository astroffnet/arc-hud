package mezz.jei.common.platform;

import mezz.jei.api.helpers.IPlatformFluidHelper;

/**
 * Совместимость Arc HUD: часть внутреннего API JEI, которую используют некоторые плагины модов (например, Aether).
 * Есть только во вложенной копии API — при установленном настоящем JEI его классы заменяют эти.
 */
public interface IPlatformFluidHelperInternal<T> extends IPlatformFluidHelper<T> {
}
