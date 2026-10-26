package com.arc4io.archud.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Перезапуск игры с теми же параметрами JVM и игры (тот же лаунчер-профиль, аккаунт, папка игры).
 * Новый процесс запускается из shutdown-хука, когда старый уже сохранил мир и закрылся.
 * После запуска {@link #resumeIfPending} возвращает игрока в тот же мир или на тот же сервер.
 */
public final class GameRestarter {
    private static final String RESUME_FILE = "archud-resume.txt";
    private static boolean resumed;

    private GameRestarter() {}

    /** Лаунчеры Prism / MultiMC передают параметры через stdin — их обёртку повторить нельзя. */
    private static Boolean supported;

    public static boolean supported() {
        if (supported == null) supported = check();
        return supported;
    }

    private static boolean check() {
        List<String> cmd = command();
        if (cmd == null) return false;
        String all = String.join(" ", cmd);
        return !all.contains("org.prismlauncher.") && !all.contains("org.multimc.") && !all.contains("org.polymc.");
    }

    static List<String> command() {
        try {
            ProcessHandle.Info info = ProcessHandle.current().info();
            String java = info.command().orElse(null);
            if (java == null) {
                boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
                java = Path.of(System.getProperty("java.home"), "bin", win ? "javaw.exe" : "java").toString();
            }
            List<String> cmd = new ArrayList<>();
            cmd.add(java);
            Optional<String[]> args = info.arguments();
            if (args.isPresent() && args.get().length > 0) {
                // Linux / macOS: точная командная строка процесса
                cmd.addAll(Arrays.asList(args.get()));
                return cmd;
            }
            // Windows: собираем из параметров JVM, classpath и sun.java.command
            String main = System.getProperty("sun.java.command");
            if (main == null || main.isBlank()) return null;
            cmd.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
            String cp = System.getProperty("java.class.path");
            String[] tok = main.trim().split(" ");
            if (tok[0].toLowerCase(Locale.ROOT).endsWith(".jar")) {
                cmd.add("-jar");
            } else if (cp != null && !cp.isEmpty()) {
                cmd.add("-cp");
                cmd.add(cp);
            }
            cmd.add(tok[0]);
            // аргументы игры склеены через пробел: «--ключ значение»; значение с пробелами (путь к папке) собираем обратно
            List<String> prog = new ArrayList<>();
            boolean expectValue = false;
            for (int i = 1; i < tok.length; i++) {
                String t = tok[i];
                if (t.startsWith("--")) {
                    prog.add(t);
                    expectValue = true;
                } else if (expectValue || prog.isEmpty()) {
                    prog.add(t);
                    expectValue = false;
                } else {
                    prog.set(prog.size() - 1, prog.get(prog.size() - 1) + " " + t);
                }
            }
            cmd.addAll(prog);
            return cmd;
        } catch (Throwable t) {
            ModWatcher.log("cannot build restart command: " + t);
            return null;
        }
    }

    public static void restart(Minecraft mc) {
        List<String> cmd = command();
        if (cmd == null || !supported()) {
            net.minecraft.client.gui.components.toasts.SystemToast.addOrUpdate(mc.getToasts(),
                    net.minecraft.client.gui.components.toasts.SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
                    Component.translatable("archud.mods.changed"), Component.translatable("archud.mods.restart_manual"));
            return;
        }
        // куда вернуться после перезапуска
        String resume = null;
        if (mc.getSingleplayerServer() != null) {
            Path root = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            resume = "world:" + root.getFileName();
        } else if (mc.getCurrentServer() != null) {
            resume = "server:" + mc.getCurrentServer().ip;
        }
        try {
            Path f = resumeFile(mc);
            if (resume != null) Files.writeString(f, resume, StandardCharsets.UTF_8);
            else Files.deleteIfExists(f);
        } catch (Exception ignored) {
        }
        Path workDir = Path.of(System.getProperty("user.dir"));
        ModWatcher.log("restarting: " + cmd.get(0) + " … (" + cmd.size() + " args), resume=" + resume);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                new ProcessBuilder(cmd).directory(workDir.toFile())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
            } catch (Exception e) {
                System.err.println("[Arc HUD] restart failed: " + e);
            }
        }, "ArcHud-Restart"));
        // как «Выйти в меню»: сохранить мир / отключиться, затем закрыть игру
        if (mc.level != null) {
            boolean local = mc.isLocalServer();
            mc.level.disconnect();
            if (local) mc.clearLevel(new GenericDirtMessageScreen(Component.translatable("menu.savingLevel")));
            else mc.clearLevel();
        }
        mc.stop();
    }

    private static Path resumeFile(Minecraft mc) {
        return mc.gameDirectory.toPath().resolve("config").resolve(RESUME_FILE);
    }

    /** Вызывается при первом показе главного меню: продолжить мир / сервер, из которого перезапускались. */
    public static void resumeIfPending(Screen title) {
        if (resumed) return;
        resumed = true;
        Minecraft mc = Minecraft.getInstance();
        Path f = resumeFile(mc);
        String r;
        try {
            if (!Files.exists(f)) return;
            r = Files.readString(f, StandardCharsets.UTF_8).trim();
            Files.deleteIfExists(f);
        } catch (Exception e) {
            return;
        }
        Screen parent = title != null ? title : new TitleScreen();
        if (r.startsWith("world:")) {
            String id = r.substring(6);
            mc.execute(() -> mc.createWorldOpenFlows().loadLevel(parent, id));
        } else if (r.startsWith("server:")) {
            String ip = r.substring(7);
            mc.execute(() -> ConnectScreen.startConnecting(parent, mc, ServerAddress.parseString(ip), new ServerData(ip, ip, false), false));
        }
    }
}
