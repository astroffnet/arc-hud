package com.arc4io.archud.mixin;

import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.server.LanServerPinger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Доступ к полям сервера «Открыть для сети» — чтобы закрыть игру для сети из панели хоста. */
@Mixin(IntegratedServer.class)
public interface IntegratedServerAccessor {
    @Accessor("publishedPort")
    void archud$setPublishedPort(int port);

    @Accessor("publishedPort")
    int archud$getPublishedPort();

    @Accessor("lanPinger")
    LanServerPinger archud$getLanPinger();

    @Accessor("lanPinger")
    void archud$setLanPinger(LanServerPinger pinger);
}
