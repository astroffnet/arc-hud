package com.arc4io.archud.mixin;

import net.minecraft.client.gui.screens.worldselection.EditGameRulesScreen;
import net.minecraft.world.level.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Optional;
import java.util.function.Consumer;

@Mixin(EditGameRulesScreen.class)
public interface EditGameRulesScreenAccessor {
    @Accessor("gameRules")
    GameRules archud$gameRules();

    @Accessor("exitCallback")
    Consumer<Optional<GameRules>> archud$exitCallback();
}
