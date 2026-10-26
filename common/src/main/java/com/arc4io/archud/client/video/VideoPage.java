package com.arc4io.archud.client.video;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Страница настроек: группы (с необязательным заголовком) и настройки. */
public final class VideoPage {
    public final Component name;
    public final List<Group> groups = new ArrayList<>();

    public record Group(Component title, List<VideoEntry> entries) {}

    public VideoPage(Component name) {
        this.name = name;
    }

    public VideoPage group(Component title, List<VideoEntry> entries) {
        if (!entries.isEmpty()) groups.add(new Group(title, entries));
        return this;
    }
}
