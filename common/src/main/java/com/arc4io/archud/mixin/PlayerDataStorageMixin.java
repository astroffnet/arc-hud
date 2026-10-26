package com.arc4io.archud.mixin;

import com.arc4io.archud.server.NicknameStorage;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** playerdata/&lt;ник&gt;.dat вместо playerdata/&lt;uuid&gt;.dat (см. {@link NicknameStorage}). */
@Mixin(value = PlayerDataStorage.class, priority = 500)
public abstract class PlayerDataStorageMixin {
    @Inject(method = "load", at = @At("HEAD"))
    private void archud$migrate(Player player, CallbackInfoReturnable<?> cir) {
        NicknameStorage.migratePlayerData(((PlayerDataStorageAccessor) (Object) this).archud$getPlayerDir(), player);
    }

    @Redirect(method = {"save", "load"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;getStringUUID()Ljava/lang/String;"), require = 0)
    private String archud$fileKey(Player player) {
        return NicknameStorage.enabled() ? NicknameStorage.key(player) : player.getStringUUID();
    }
}
