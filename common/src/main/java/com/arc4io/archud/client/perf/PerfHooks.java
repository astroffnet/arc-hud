package com.arc4io.archud.client.perf;

import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.TerrainParticle;
import net.minecraft.client.renderer.entity.layers.ArrowLayer;
import net.minecraft.client.renderer.entity.layers.BeeStingerLayer;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.layers.ElytraLayer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.layers.ParrotOnShoulderLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BedBlockEntity;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrushableBlockEntity;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ConduitBlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.entity.EnchantmentTableBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.entity.TheEndPortalBlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/** Проверки для миксинов оптимизации (см. {@link Perf}). true — пропустить отрисовку / создание. */
public final class PerfHooks {
    private static long frame = -1;
    private static int entitiesThisFrame;
    private static final Map<Class<?>, String> LAYER_IDS = new HashMap<>();
    private static final Map<Object, String> PARTICLE_IDS = new HashMap<>();
    private static final Map<String, Integer> KEEP_EVERY = new HashMap<>();
    private static final Map<String, Integer> COUNTERS = new HashMap<>();

    static {
        KEEP_EVERY.put("p_smoke", 3);
        KEEP_EVERY.put("p_explosion", 2);
        KEEP_EVERY.put("p_potion", 4);
        KEEP_EVERY.put("p_portal", 4);
        KEEP_EVERY.put("p_firework", 3);
        KEEP_EVERY.put("p_crit", 3);
        KEEP_EVERY.put("p_totem", 4);
        KEEP_EVERY.put("p_bubble", 3);
        KEEP_EVERY.put("p_dust", 3);
        KEEP_EVERY.put("p_lava", 3);
        KEEP_EVERY.put("p_block_break", 3);
    }

    private PerfHooks() {}

    private static Vec3 cam() {
        return Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
    }

    // ------------------------------------------------------------------ сущности
    public static boolean skipEntity(Entity e) {
        Minecraft mc = Minecraft.getInstance();
        if (e == mc.player || e == mc.getCameraEntity() || (mc.player != null && (e.hasPassenger(mc.player) || mc.player.isPassengerOfSameVehicle(e)))) return false;
        if (e.isCurrentlyGlowing()) return false;
        double d2 = e.distanceToSqr(cam());
        String id = entityId(e);
        if (id != null && Perf.beyond(id, d2)) return true;
        if (Perf.on("e_invisible") && e instanceof LivingEntity le && !(e instanceof Player) && le.isInvisibleTo(mc.player)
                && !(e instanceof ArmorStand) && d2 > 16 * 16) return true;
        int cap = Perf.get("e_cap");
        if (cap > 0) {
            long now = mc.getFrameTimeNs();
            if (now != frame) {
                frame = now;
                entitiesThisFrame = 0;
            }
            if (++entitiesThisFrame > cap && d2 > 16 * 16) return true;
        }
        return false;
    }

    private static String entityId(Entity e) {
        if (e.getBoundingBox().getSize() > 8) return null; // боссы и огромные модели видны издалека
        if (e instanceof ItemEntity) return "e_item";
        if (e instanceof ExperienceOrb) return "e_xp";
        if (e instanceof ItemFrame) return "e_item_frame";
        if (e instanceof ArmorStand) return "e_armor_stand";
        if (e instanceof Painting) return "e_painting";
        if (e instanceof Projectile) return "e_projectile";
        if (e instanceof Player) return "e_player";
        if (e instanceof AbstractVillager) return "e_villager";
        if (e instanceof Enemy) return "e_hostile";
        if (e instanceof WaterAnimal) return "e_water";
        if (e instanceof AmbientCreature) return "e_ambient";
        if (e instanceof PathfinderMob || e instanceof Mob) return "e_passive";
        if (e instanceof AbstractMinecart || e instanceof Boat) return "e_vehicle";
        if (e instanceof FallingBlockEntity) return "e_falling";
        return "e_other";
    }

    /** Слой модели (броня, предмет в руке, плащ…) далеко — не рисовать. */
    public static boolean skipLayer(RenderLayer<?, ?> layer, Entity e) {
        String id = LAYER_IDS.computeIfAbsent(layer.getClass(), PerfHooks::layerId);
        if (id.isEmpty() || e == Minecraft.getInstance().player) return false;
        return Perf.beyond(id, e.distanceToSqr(cam()));
    }

    private static String layerId(Class<?> c) {
        if (HumanoidArmorLayer.class.isAssignableFrom(c)) return "l_armor";
        if (ItemInHandLayer.class.isAssignableFrom(c)) return "l_held";
        if (CapeLayer.class.isAssignableFrom(c)) return "l_cape";
        if (ElytraLayer.class.isAssignableFrom(c)) return "l_elytra";
        if (CustomHeadLayer.class.isAssignableFrom(c)) return "l_head";
        if (ArrowLayer.class.isAssignableFrom(c)) return "l_arrows";
        if (BeeStingerLayer.class.isAssignableFrom(c)) return "l_stingers";
        if (ParrotOnShoulderLayer.class.isAssignableFrom(c)) return "l_parrot";
        if (!c.getName().startsWith("net.minecraft.")) return "l_other"; // слои модов
        return "";
    }

    public static boolean skipNameTag(Entity e) {
        if (Perf.on("n_mob_tags") && !(e instanceof Player)) return true;
        return Perf.beyond("n_tag_dist", e.distanceToSqr(cam()));
    }

    public static boolean skipFire(Entity e) {
        return Perf.on("f_fire") && e != Minecraft.getInstance().player && e.distanceToSqr(cam()) > 12 * 12;
    }

    public static boolean skipLeash(Entity mob) {
        return Perf.beyond("x_leash", mob.distanceToSqr(cam()));
    }

    // ------------------------------------------------------------------ блоки-сущности
    public static boolean skipBlockEntity(BlockEntity be) {
        String id = blockEntityId(be);
        if (id == null) return false;
        if (id.equals("b_beacon")) return Perf.on("b_beacon");
        var p = be.getBlockPos();
        return Perf.beyond(id, cam().distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5));
    }

    private static String blockEntityId(BlockEntity be) {
        if (be instanceof ChestBlockEntity || be instanceof EnderChestBlockEntity) return "b_chest";
        if (be instanceof SignBlockEntity) return "b_sign";
        if (be instanceof BannerBlockEntity) return "b_banner";
        if (be instanceof SkullBlockEntity) return "b_skull";
        if (be instanceof BeaconBlockEntity) return "b_beacon";
        if (be instanceof EnchantmentTableBlockEntity) return "b_enchant_book";
        if (be instanceof SpawnerBlockEntity) return "b_spawner";
        if (be instanceof ShulkerBoxBlockEntity) return "b_shulker";
        if (be instanceof BedBlockEntity) return "b_bed";
        if (be instanceof BellBlockEntity) return "b_bell";
        if (be instanceof ConduitBlockEntity) return "b_conduit";
        if (be instanceof CampfireBlockEntity) return "b_campfire";
        if (be instanceof LecternBlockEntity) return "b_lectern";
        if (be instanceof DecoratedPotBlockEntity) return "b_pot";
        if (be instanceof BrushableBlockEntity) return "b_brushable";
        if (be instanceof TheEndPortalBlockEntity) return "b_end_portal";
        ResourceLocation key = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
        if (key != null && !key.getNamespace().equals("minecraft")) return "b_modded";
        return null;
    }

    // ------------------------------------------------------------------ частицы
    /** Создание частицы через ParticleEngine.createParticle (почти все частицы мира). */
    public static boolean skipParticle(ParticleOptions options, double x, double y, double z) {
        String id = PARTICLE_IDS.computeIfAbsent(options.getType(), t -> particleId(BuiltInRegistries.PARTICLE_TYPE.getKey(options.getType())));
        if (Perf.beyond("p_distance", cam().distanceToSqr(x, y, z))) return true;
        if (id.isEmpty() || !Perf.on(id)) return false;
        Integer every = KEEP_EVERY.get(id);
        if (every == null) return true; // выключено полностью
        int c = COUNTERS.merge(id, 1, Integer::sum);
        return c % every != 0;
    }

    private static String particleId(ResourceLocation key) {
        if (key == null || !key.getNamespace().equals("minecraft")) return "";
        String p = key.getPath();
        if (p.equals("rain")) return "p_rain";
        if (p.startsWith("dripping_") || p.startsWith("falling_") || p.startsWith("landing_") || p.equals("drip_lava") || p.equals("drip_water")) return "p_drip";
        if (p.contains("smoke")) return "p_smoke";
        if (p.startsWith("explosion") || p.equals("poof")) return "p_explosion";
        if (p.endsWith("entity_effect")) return "p_potion";
        if (p.equals("block")) return "p_footstep";
        if (p.endsWith("portal")) return "p_portal";
        if (p.endsWith("ash") || p.endsWith("spore") || p.equals("spore_blossom_air") || p.equals("mycelium")) return "p_biome";
        if (p.equals("firework") || p.equals("flash")) return "p_firework";
        if (p.equals("crit") || p.equals("enchanted_hit") || p.equals("enchant")) return "p_crit";
        if (p.equals("totem_of_undying")) return "p_totem";
        if (p.startsWith("bubble") || p.equals("current_down") || p.equals("underwater")) return "p_bubble";
        if (p.equals("happy_villager") || p.equals("angry_villager") || p.equals("heart") || p.equals("note")) return "p_social";
        if (p.startsWith("dust")) return "p_dust";
        if (p.equals("lava")) return "p_lava";
        return "";
    }

    /** Добавление любой частицы: общий лимит и частицы разрушения блоков. */
    public static boolean skipAdd(Particle particle, int currentCount) {
        int limit = Perf.get("p_limit");
        if (limit > 0 && currentCount >= limit) return true;
        if (particle instanceof TerrainParticle && Perf.on("p_block_break")) {
            int c = COUNTERS.merge("p_block_break", 1, Integer::sum);
            return c % 3 != 0;
        }
        return false;
    }
}
