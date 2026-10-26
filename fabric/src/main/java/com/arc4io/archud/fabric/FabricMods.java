package com.arc4io.archud.fabric;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.client.mods.ArcModListScreen;
import com.arc4io.archud.client.mods.ModEntry;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.Person;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Список модов для Fabric без Mod Menu. Настройки модов берутся из их точек входа «modmenu» (так моды Fabric
 * отдают свой экран настроек). Если Mod Menu не установлен, классов его API нет — тогда Arc HUD сам создаёт
 * совместимые интерфейсы {@code com.terraformersmc.modmenu.api.*} (байткодом, под имена классов игры в этом
 * запуске) и добавляет их в загрузчик, чтобы точки входа модов загрузились.
 */
public final class FabricMods {
    private static Map<String, Function<Screen, Screen>> factories;
    private static boolean shimTried;

    private FabricMods() {}

    public static List<ModEntry> mods() {
        Map<String, Function<Screen, Screen>> cfg = configFactories();
        List<ModEntry> out = new ArrayList<>();
        for (ModContainer c : FabricLoader.getInstance().getAllMods()) {
            ModMetadata md = c.getMetadata();
            String id = md.getId();
            if (id.equals("java")) continue;
            ModEntry.Logo logo = null;
            Optional<String> icon = md.getIconPath(64);
            if (icon.isPresent()) {
                String path = icon.get();
                logo = () -> {
                    Optional<Path> p = c.findPath(path);
                    return p.isPresent() && Files.exists(p.get()) ? Files.newInputStream(p.get()) : null;
                };
            }
            String authors = md.getAuthors().stream().map(Person::getName).collect(Collectors.joining(", "));
            String url = md.getContact().get("homepage").or(() -> md.getContact().get("sources")).orElse(null);
            boolean lib = id.equals("minecraft") || id.equals("fabricloader") || badges(md).contains("library");
            String parent = modmenuParent(md);
            if (parent == null) parent = c.getContainingMod().map(m -> m.getMetadata().getId()).orElse(null);
            // модули Fabric API (в среде разработки они не вложены в fabric-api)
            if (parent == null && !id.equals("fabric-api") && (md.containsCustomValue("fabric-api:module-lifecycle") || id.startsWith("fabric-")))
                parent = "fabric-api";
            out.add(new ModEntry(id, md.getName(), md.getVersion().getFriendlyString(), md.getDescription(), authors, url,
                    lib, logo, cfg.get(id), parent));
        }
        return out;
    }

    private static List<String> badges(ModMetadata md) {
        List<String> out = new ArrayList<>();
        try {
            CustomValue mm = md.getCustomValue("modmenu");
            if (mm != null && mm.getType() == CustomValue.CvType.OBJECT) {
                CustomValue b = mm.getAsObject().get("badges");
                if (b != null && b.getType() == CustomValue.CvType.ARRAY) for (CustomValue v : b.getAsArray()) out.add(v.getAsString());
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static String modmenuParent(ModMetadata md) {
        try {
            CustomValue mm = md.getCustomValue("modmenu");
            if (mm != null && mm.getType() == CustomValue.CvType.OBJECT) {
                CustomValue p = mm.getAsObject().get("parent");
                if (p != null) return p.getType() == CustomValue.CvType.STRING ? p.getAsString() : p.getAsObject().get("id").getAsString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------ экраны настроек модов
    private static synchronized Map<String, Function<Screen, Screen>> configFactories() {
        if (factories != null) return factories;
        Map<String, Function<Screen, Screen>> out = new HashMap<>();
        out.put(ArcHud.MODID, parent -> new com.arc4io.archud.client.screen.ArcConfigScreen(parent));
        if (!FabricLoader.getInstance().isModLoaded("modmenu")) installShim();
        for (EntrypointContainer<Object> c : FabricLoader.getInstance().getEntrypointContainers("modmenu", Object.class)) {
            String id = c.getProvider().getMetadata().getId();
            try {
                Object api = c.getEntrypoint();
                Method own = api.getClass().getMethod("getModConfigScreenFactory");
                // только если мод действительно переопределил метод (иначе по умолчанию «нет экрана»)
                if (!own.getDeclaringClass().isInterface()) {
                    Object f = own.invoke(api);
                    if (f != null) out.putIfAbsent(id, wrap(f));
                }
                Object provided = api.getClass().getMethod("getProvidedConfigScreenFactories").invoke(api);
                if (provided instanceof Map<?, ?> m) {
                    for (Map.Entry<?, ?> e : m.entrySet()) if (e.getValue() != null) out.putIfAbsent(String.valueOf(e.getKey()), wrap(e.getValue()));
                }
            } catch (Throwable t) {
                ArcHud.LOGGER.warn("Arc HUD: mod menu entrypoint of {} failed: {}", id, t.toString());
            }
        }
        factories = out;
        return out;
    }

    private static Function<Screen, Screen> wrap(Object factory) {
        return parent -> {
            try {
                for (Method m : factory.getClass().getMethods()) {
                    if (m.getName().equals("create") && m.getParameterCount() == 1 && !m.isBridge()) {
                        m.setAccessible(true);
                        Object s = m.invoke(factory, parent);
                        return s instanceof Screen screen ? screen : null;
                    }
                }
                for (Method m : factory.getClass().getMethods()) {
                    if (m.getName().equals("create") && m.getParameterCount() == 1) {
                        m.setAccessible(true);
                        Object s = m.invoke(factory, parent);
                        return s instanceof Screen screen ? screen : null;
                    }
                }
            } catch (Throwable t) {
                ArcHud.LOGGER.warn("Arc HUD: config screen failed", t);
            }
            return null;
        };
    }

    // ------------------------------------------------------------------ совместимый API Mod Menu
    /** Создать интерфейсы API Mod Menu и подключить их к загрузчику Fabric. */
    private static void installShim() {
        if (shimTried) return;
        shimTried = true;
        try {
            var mr = FabricLoader.getInstance().getMappingResolver();
            String screen = mr.mapClassName("intermediary", "net.minecraft.class_437").replace('.', '/');
            String text = mr.mapClassName("intermediary", "net.minecraft.class_2561").replace('.', '/');
            Path dir = FabricLoader.getInstance().getGameDir().resolve(".archud").resolve("modmenu-api");
            Path pkg = dir.resolve("com/terraformersmc/modmenu/api");
            Files.createDirectories(pkg);
            String api = "com/terraformersmc/modmenu/api/";
            Files.write(pkg.resolve("ConfigScreenFactory.class"), iface(api + "ConfigScreenFactory", cw -> {
                cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "create", "(L" + screen + ";)L" + screen + ";", null, null).visitEnd();
            }));
            Files.write(pkg.resolve("UpdateInfo.class"), iface(api + "UpdateInfo", cw -> {
                cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "isUpdateAvailable", "()Z", null, null).visitEnd();
                cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "getDownloadLink", "()Ljava/lang/String;", null, null).visitEnd();
            }));
            Files.write(pkg.resolve("UpdateChecker.class"), iface(api + "UpdateChecker", cw -> {
                cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "checkForUpdates", "()L" + api + "UpdateInfo;", null, null).visitEnd();
            }));
            String shim = "com/arc4io/archud/fabric/FabricMods";
            Files.write(pkg.resolve("ModMenuApi.class"), iface(api + "ModMenuApi", cw -> {
                MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "getModConfigScreenFactory", "()L" + api + "ConfigScreenFactory;", null, null);
                mv.visitCode();
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(1, 1);
                mv.visitEnd();
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "getProvidedConfigScreenFactories", "()Ljava/util/Map;", null, null);
                mv.visitCode();
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Map", "of", "()Ljava/util/Map;", true);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(1, 1);
                mv.visitEnd();
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "getUpdateChecker", "()L" + api + "UpdateChecker;", null, null);
                mv.visitCode();
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(1, 1);
                mv.visitEnd();
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "getProvidedUpdateCheckers", "()Ljava/util/Map;", null, null);
                mv.visitCode();
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Map", "of", "()Ljava/util/Map;", true);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(1, 1);
                mv.visitEnd();
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "attachModpackBadges", "(Ljava/util/function/Consumer;)V", null, null);
                mv.visitCode();
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(0, 2);
                mv.visitEnd();
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "createModsScreen", "(L" + screen + ";)L" + screen + ";", null, null);
                mv.visitCode();
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, shim, "createModsScreen", "(L" + screen + ";)L" + screen + ";", false);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(1, 1);
                mv.visitEnd();
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "createModsButtonText", "()L" + text + ";", null, null);
                mv.visitCode();
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, shim, "createModsButtonText", "()L" + text + ";", false);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(1, 0);
                mv.visitEnd();
            }));
            net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().addToClassPath(dir);
            ArcHud.LOGGER.info("Arc HUD: Mod Menu API compatibility enabled (Mod Menu is not installed)");
        } catch (Throwable t) {
            ArcHud.LOGGER.warn("Arc HUD: cannot provide Mod Menu API", t);
        }
    }

    private static byte[] iface(String name, java.util.function.Consumer<ClassWriter> body) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, name, null, "java/lang/Object", null);
        body.accept(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Для созданного ModMenuApi.createModsScreen. */
    public static Screen createModsScreen(Screen parent) {
        return new ArcModListScreen(parent);
    }

    /** Для созданного ModMenuApi.createModsButtonText. */
    public static Component createModsButtonText() {
        return Component.translatable("archud.menu.mods");
    }
}
