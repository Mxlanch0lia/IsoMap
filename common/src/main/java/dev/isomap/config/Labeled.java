package dev.isomap.config;

import net.minecraft.network.chat.Component;

/** An enum option that can show itself in a settings control. */
public interface Labeled {
    String labelKey();

    default Component label() {
        return Component.translatable(labelKey());
    }
}
