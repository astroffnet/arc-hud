package com.arc4io.archud.mixin;

import com.arc4io.archud.server.NicknameStorage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;
import java.nio.file.Path;

/** advancements/&lt;ник&gt;.json и stats/&lt;ник&gt;.json вместо файлов по UUID (см. {@link NicknameStorage}). */
@Mixin(PlayerList.class)
public abstract class PlayerListStorageMixin {
    @Unique
    private Player archud$statsPlayer;
    @Unique
    private ServerPlayer archud$advPlayer;

    @Inject(method = "getPlayerStats", at = @At("HEAD"))
    private void archud$statsHead(Player player, CallbackInfoReturnable<?> cir) {
        archud$statsPlayer = player;
    }

    @ModifyArg(method = "getPlayerStats", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/stats/ServerStatsCounter;<init>(Lnet/minecraft/server/MinecraftServer;Ljava/io/File;)V"), index = 1, require = 0)
    private File archud$statsFile(File file) {
        return NicknameStorage.byNick(file, archud$statsPlayer);
    }

    @Inject(method = "getPlayerAdvancements", at = @At("HEAD"))
    private void archud$advHead(ServerPlayer player, CallbackInfoReturnable<?> cir) {
        archud$advPlayer = player;
    }

    @ModifyArg(method = "getPlayerAdvancements", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/PlayerAdvancements;<init>(Lcom/mojang/datafixers/DataFixer;Lnet/minecraft/server/players/PlayerList;Lnet/minecraft/server/ServerAdvancementManager;Ljava/nio/file/Path;Lnet/minecraft/server/level/ServerPlayer;)V"),
            index = 3, require = 0)
    private Path archud$advFile(Path path) {
        return NicknameStorage.byNick(path, archud$advPlayer);
    }
}
