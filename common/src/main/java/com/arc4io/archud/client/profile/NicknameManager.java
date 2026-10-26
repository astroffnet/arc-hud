package com.arc4io.archud.client.profile;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.config.ArcConfig;
import com.arc4io.archud.mixin.MinecraftUserAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.core.UUIDUtil;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Свой ник для одиночной игры и серверов без лицензии (offline-mode): подменяется профиль игрока
 * (имя и офлайн-UUID). Настоящий аккаунт запоминается и возвращается кнопкой «Сбросить».
 * На лицензионных серверах вход под чужим ником невозможен — для них нужно вернуть аккаунт.
 */
public final class NicknameManager {
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static User original;
    private static boolean applied;

    private NicknameManager() {}

    public static boolean isValid(String name) {
        return name != null && VALID.matcher(name).matches();
    }

    /** Ник настоящего аккаунта. */
    public static String accountName() {
        User u = original != null ? original : Minecraft.getInstance().getUser();
        return u.getName();
    }

    public static boolean isCustom() {
        return original != null;
    }

    /** Применить сохранённый ник при запуске (один раз). */
    public static void applySaved() {
        if (applied) return;
        applied = true;
        String n = ArcConfig.get().customNickname;
        if (isValid(n)) set(n, false);
    }

    public static void set(String name, boolean save) {
        if (!isValid(name)) return;
        Minecraft mc = Minecraft.getInstance();
        if (original == null) original = mc.getUser();
        if (name.equals(original.getName())) {
            reset();
            return;
        }
        UUID id = UUIDUtil.createOfflinePlayerUUID(name);
        User user = new User(name, id.toString().replace("-", ""), "0", Optional.empty(), Optional.empty(), User.Type.LEGACY);
        ((MinecraftUserAccessor) mc).archud$setUser(user);
        ArcHud.LOGGER.info("Arc HUD: nickname set to {}", name);
        if (save) {
            ArcConfig.get().customNickname = name;
            ArcConfig.save();
        }
    }

    public static void reset() {
        Minecraft mc = Minecraft.getInstance();
        if (original != null) ((MinecraftUserAccessor) mc).archud$setUser(original);
        original = null;
        ArcConfig.get().customNickname = "";
        ArcConfig.save();
    }
}
