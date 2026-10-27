package com.arc4io.archud.client.compat;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.config.ArcConfig;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Аксессуары (Curios, Aether, слои других модов) во время анимаций playerAnimator (Legends, Emotecraft и т. п.).
 * Модель аксессуара получает от модели игрока только повороты частей, поэтому:
 * <ul>
 *   <li>сгиб локтей/коленей не переносится — перчатки и браслеты «отрываются» от кисти;</li>
 *   <li>наклон корпуса применяется дважды (слоем и самой моделью) — руки аксессуара уезжают.</li>
 * </ul>
 * Здесь после {@code copyPropertiesTo} сгибы копируются на руки/ноги модели аксессуара, а наклон корпуса
 * остаётся только у слоя. Броня не трогается — у неё своя схема в playerAnimator.
 * Всё через отражение: без playerAnimator ничего не делается.
 */
public final class PlayerAnimCompat {
    private static final Set<Object> ARMOR_MODELS = Collections.newSetFromMap(new WeakHashMap<>());
    private static boolean init, ok;
    private static Class<?> mutable;
    private static Method getSupplier, setSupplier, supplierGet, isActive, getBend, bend;
    private static Object bendHelper;
    private static Constructor<?> newSupplier;
    private static int errors;

    private PlayerAnimCompat() {}

    public static void registerArmorModel(Object model) {
        if (model != null) ARMOR_MODELS.add(model);
    }

    private static boolean setup() {
        if (init) return ok;
        init = true;
        try {
            mutable = Class.forName("dev.kosmx.playerAnim.impl.IMutableModel");
            Class<?> sup = Class.forName("dev.kosmx.playerAnim.core.util.SetableSupplier");
            Class<?> proc = Class.forName("dev.kosmx.playerAnim.core.impl.AnimationProcessor");
            Class<?> pair = Class.forName("dev.kosmx.playerAnim.core.util.Pair");
            Class<?> helper = Class.forName("dev.kosmx.playerAnim.impl.animation.IBendHelper");
            getSupplier = mutable.getMethod("getEmoteSupplier");
            setSupplier = mutable.getMethod("setEmoteSupplier", sup);
            supplierGet = sup.getMethod("get");
            newSupplier = sup.getConstructor();
            isActive = proc.getMethod("isActive");
            getBend = proc.getMethod("getBend", String.class);
            bend = helper.getMethod("bend", ModelPart.class, pair);
            bendHelper = helper.getField("INSTANCE").get(null);
            ok = true;
            ArcHud.LOGGER.info("Arc HUD: playerAnimator found — accessories follow animated limbs");
        } catch (Throwable t) {
            ok = false;
        }
        return ok;
    }

    /** После {@code from.copyPropertiesTo(to)}. */
    public static void afterCopy(HumanoidModel<?> from, HumanoidModel<?> to) {
        if (from == to || ARMOR_MODELS.contains(to) || !ArcConfig.get().accessoryAnimFix || !setup()) return;
        try {
            if (!mutable.isInstance(from) || !mutable.isInstance(to)) return;
            Object anim = supplierGet.invoke(getSupplier.invoke(from));
            if (anim == null || !(Boolean) isActive.invoke(anim)) return;
            bend.invoke(bendHelper, to.rightArm, getBend.invoke(anim, "rightArm"));
            bend.invoke(bendHelper, to.leftArm, getBend.invoke(anim, "leftArm"));
            bend.invoke(bendHelper, to.rightLeg, getBend.invoke(anim, "rightLeg"));
            bend.invoke(bendHelper, to.leftLeg, getBend.invoke(anim, "leftLeg"));
            // наклон корпуса уже применён к слою — модель не должна добавлять его второй раз
            setSupplier.invoke(to, newSupplier.newInstance());
        } catch (Throwable t) {
            if (errors++ < 3) ArcHud.LOGGER.warn("Arc HUD: playerAnimator bend sync failed", t);
        }
    }
}
