package com.arc4io.archud.mixin;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Camera.class)
public interface CameraInvoker {
    @Invoker("setRotation")
    void archud$setRotation(float yaw, float pitch);

    @Invoker("setPosition")
    void archud$setPosition(double x, double y, double z);

    @Invoker("move")
    void archud$move(double forward, double up, double left);

    @Invoker("getMaxZoom")
    double archud$getMaxZoom(double distance);
}
