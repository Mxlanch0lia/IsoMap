package dev.isomap.smoke;

import javax.annotation.Nullable;
import net.minecraft.client.gui.screens.Screen;

/** What the QA harness does differently per loader: it feeds input through the loader's own path, as the game would. */
public interface SmokePlatform {
    /**
     * Sends a mouse turn through the loader's turn hook.
     *
     * @return true if the player was kept from turning
     */
    boolean turnFrozen();

    /**
     * Sends one notch of the wheel through the loader's scroll hook.
     *
     * @return true if the mod used it and the game did not
     */
    boolean scrollConsumed();

    /** The loader's list of mods, if it has one, for the gallery pictures. */
    @Nullable
    Screen modListScreen(Screen parent);

    static SmokePlatform get() {
        return Holder.instance;
    }

    static void set(SmokePlatform platform) {
        Holder.instance = platform;
    }

    final class Holder {
        private static SmokePlatform instance;

        private Holder() {}
    }
}
