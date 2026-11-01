package com.arc4io.archud.forge.compat;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.client.compat.PlayerAnimCompat;
import com.arc4io.archud.config.ArcConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.armortrim.ArmorTrim;
import org.apache.commons.lang3.tuple.Triple;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotResult;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Перчатки Aether вместе с анимациями рук.
 * <p>
 * Aether рисует перчатку отдельно от руки: от первого лица — вторым проходом, заново повторяя ванильную математику
 * положения руки (поэтому любые моды анимаций — Punchy, playerAnimator, Legends — двигают руку, а перчатка остаётся
 * на месте); от третьего лица — своей моделью, в которую копируются только повороты руки (наклон корпуса, сдвиги и
 * масштаб от модов анимаций теряются).
 * <p>
 * Здесь перчатка снимается «с самой руки»: в момент, когда рисуется рука игрока, запоминается её итоговое положение
 * (матрица и поза части модели — со всеми преобразованиями, которые к этому моменту применили моды), и сразу после
 * модели игрока перчатка рисуется ровно там же. Свой проход Aether при этом пропускается.
 */
public final class AetherGloves {
    private static boolean init, ok;
    private static Class<?> glovesItem, glovesRenderer;
    private static Method texture, colors, material, getRenderer;
    private static Field fModel, fSlim, fFirst, fTrim, fTrimSlim, fTrimFirst;
    private static int errors;
    /** Punchy рисует руку от первого лица сам (своей моделью игрока) — начало и конец его отрисовки руки. */
    public static void punchyArm(LivingEntity player, Object model, boolean right, MultiBufferSource b) {
        fpRight = right;
        begin(player, model, b, true);
    }

    /** Какая рука рисуется от первого лица (renderRightHand / renderLeftHand). */
    public static boolean fpRight = true;

    /** Отложенная перчатка: рука уже нарисована, перчатка будет дорисована после модели. */
    /** Полная матрица руки — уже после {@code translateAndRotate} (вместе с преобразованиями модов вроде Punchy). */
    private record Pending(boolean right, PoseStack.Pose pose, int light) {}

    private static Pending capture(boolean right, ModelPart part, PoseStack pose, int light) {
        PoseStack copy = new PoseStack();
        copy.last().pose().set(pose.last().pose());
        copy.last().normal().set(pose.last().normal());
        part.translateAndRotate(copy);
        return new Pending(right, copy.last(), light);
    }

    private static LivingEntity current;
    private static HumanoidModel<?> playerModel;
    private static MultiBufferSource buffers;
    private static ItemStack gloves = ItemStack.EMPTY;
    private static boolean firstPerson, drawing;
    private static final List<Pending> PENDING = new ArrayList<>();

    private AetherGloves() {}

    private static boolean setup() {
        if (init) return ok;
        init = true;
        try {
            glovesItem = Class.forName("com.aetherteam.aether.item.accessories.gloves.GlovesItem");
            texture = glovesItem.getMethod("getGlovesTexture");
            colors = glovesItem.getMethod("getColors", ItemStack.class);
            material = glovesItem.getMethod("getMaterial");
            glovesRenderer = Class.forName("com.aetherteam.aether.client.renderer.accessory.GlovesRenderer");
            fModel = field("glovesModel");
            fSlim = field("glovesModelSlim");
            fFirst = field("glovesFirstPerson");
            fTrim = field("glovesTrimModel");
            fTrimSlim = field("glovesTrimModelSlim");
            fTrimFirst = field("glovesTrimFirstPerson");
            getRenderer = Class.forName("top.theillusivec4.curios.api.client.CuriosRendererRegistry").getMethod("getRenderer", Item.class);
            ok = true;
            ArcHud.LOGGER.info("Arc HUD: Aether gloves follow arm animations");
        } catch (Throwable t) {
            ok = false;
        }
        return ok;
    }

    private static Field field(String name) throws NoSuchFieldException {
        Field f = glovesRenderer.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    public static boolean enabled() {
        return ArcConfig.get().aetherGlovesFix && setup();
    }

    private static ItemStack find(LivingEntity e) {
        try {
            return CuriosApi.getCuriosInventory(e).resolve()
                    .flatMap(h -> h.findFirstCurio(s -> glovesItem.isInstance(s.getItem())))
                    .filter(r -> r.slotContext().visible())
                    .map(SlotResult::stack)
                    .orElse(ItemStack.EMPTY);
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    // ------------------------------------------------------------------ точки входа из миксинов

    /** Перед отрисовкой игрока (третье лицо) или руки (первое лицо). */
    public static void begin(LivingEntity e, Object model, MultiBufferSource b, boolean fp) {
        if (drawing) return;
        current = null;
        PENDING.clear();
        if (!(e instanceof Player) || !(model instanceof HumanoidModel<?> hm) || !enabled()) return;
        ItemStack s = find(e);
        if (s.isEmpty()) return;
        current = e;
        playerModel = hm;
        buffers = b;
        gloves = s;
        firstPerson = fp;
    }

    /** Рисуется часть модели: если это рука игрока — запомнить её итоговое положение. */
    public static void part(ModelPart part, PoseStack pose, int light) {
        if (current == null || drawing || !part.visible || com.arc4io.archud.client.dev.CompatDriver.noPartHook) return;
        boolean right;
        if (firstPerson) {
            // от первого лица моды могут подменять саму часть руки — берём первую часть, нарисованную в renderHand
            if (!PENDING.isEmpty()) return;
            right = fpRight;
        } else {
            right = part == playerModel.rightArm;
            if (!right && part != playerModel.leftArm) return;
        }
        PENDING.add(capture(right, part, pose, light));
    }

    /**
     * Конец {@code renderHand} (первое лицо). Если отрисовку части руки перехватить не удалось (OptiFine и другие моды
     * переписывают ModelPart), перчатка берётся из самой руки, которую рисовала игра, — в её итоговой позе.
     */
    public static void endHand(LivingEntity e, ModelPart arm, PoseStack pose, int light) {
        if (current != null && current == e && firstPerson && PENDING.isEmpty() && arm != null && !drawing) {
            PENDING.add(capture(fpRight, arm, pose, light));
        }
        end(e);
    }

    /** Aether не рисует перчатку сам, если она уже нарисована вместе с рукой. */
    public static boolean handled(LivingEntity e) {
        return e != null && e == current && !PENDING.isEmpty();
    }

    /** После модели игрока / руки: дорисовать перчатки там, где были руки. */
    public static void end(LivingEntity e) {
        if (current == null || current != e || drawing) return;
        drawing = true;
        try {
            for (Pending p : PENDING) draw(p);
        } catch (Throwable t) {
            if (errors++ < 3) ArcHud.LOGGER.warn("Arc HUD: Aether gloves render failed", t);
        } finally {
            drawing = false;
            PENDING.clear();
            current = null;
        }
    }

    // ------------------------------------------------------------------ отрисовка

    private static HumanoidModel<?> model(Object renderer, boolean trim) throws IllegalAccessException {
        boolean slim = current instanceof AbstractClientPlayer p && "slim".equals(p.getModelName());
        Field f = firstPerson ? (trim ? fTrimFirst : fFirst) : slim ? (trim ? fTrimSlim : fSlim) : (trim ? fTrim : fModel);
        return (HumanoidModel<?>) f.get(renderer);
    }

    private static ModelPart arm(HumanoidModel<?> m, Pending p) {
        ModelPart part = p.right() ? m.rightArm : m.leftArm;
        // положение уже в матрице — сама часть перчатки ничего не сдвигает и не поворачивает
        part.loadPose(PartPose.ZERO);
        part.xScale = 1F;
        part.yScale = 1F;
        part.zScale = 1F;
        part.visible = true;
        if (!firstPerson) PlayerAnimCompat.syncArmBends(playerModel, m);
        return part;
    }

    private static void draw(Pending p) throws Exception {
        Object renderer = ((Optional<?>) getRenderer.invoke(null, gloves.getItem())).orElse(null);
        if (!glovesRenderer.isInstance(renderer)) return;
        Item item = gloves.getItem();
        PoseStack pose = new PoseStack();
        pose.last().pose().set(p.pose().pose());
        pose.last().normal().set(p.pose().normal());

        ModelPart arm = arm(model(renderer, false), p);
        ResourceLocation tex = (ResourceLocation) texture.invoke(item);
        float r = 1F, g = 1F, b = 1F;
        if (colors.invoke(item, gloves) instanceof Triple<?, ?, ?> c) {
            r = ((Number) c.getLeft()).floatValue();
            g = ((Number) c.getMiddle()).floatValue();
            b = ((Number) c.getRight()).floatValue();
        }
        VertexConsumer vc = ItemRenderer.getArmorFoilBuffer(buffers, RenderType.armorCutoutNoCull(tex), false, gloves.hasFoil());
        arm.render(pose, vc, p.light(), OverlayTexture.NO_OVERLAY, r, g, b, 1F);

        Optional<ArmorTrim> trim = ArmorTrim.getTrim(current.level().registryAccess(), gloves);
        if (trim.isPresent()) {
            ModelPart trimArm = arm(model(renderer, true), p);
            TextureAtlasSprite sprite = Minecraft.getInstance().getModelManager().getAtlas(Sheets.ARMOR_TRIMS_SHEET)
                    .getSprite(trim.get().outerTexture((ArmorMaterial) material.invoke(item)));
            trimArm.render(pose, sprite.wrap(buffers.getBuffer(Sheets.armorTrimsSheet())), p.light(), OverlayTexture.NO_OVERLAY);
        }
    }
}
