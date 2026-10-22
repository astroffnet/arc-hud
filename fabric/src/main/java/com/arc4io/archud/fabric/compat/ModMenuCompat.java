package com.arc4io.archud.fabric.compat;

import com.arc4io.archud.client.screen.ArcConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Кнопка «Настроить» в Mod Menu.
 */
public class ModMenuCompat implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ArcConfigScreen::new;
    }
}
