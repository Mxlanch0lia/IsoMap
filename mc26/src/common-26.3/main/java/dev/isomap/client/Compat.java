package dev.isomap.client;

import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** The few calls whose shape differs between Minecraft 26.1 and 26.3. This is the 26.3 side. */
public final class Compat {
    private Compat() {}

    @Nullable
    public static Screen screen(Minecraft mc) {
        return mc.gui.screen();
    }

    public static void setScreen(Minecraft mc, @Nullable Screen screen) {
        mc.gui.setScreen(screen);
    }

    /** The camera the level is drawn from. */
    public static net.minecraft.client.Camera camera(Minecraft mc) {
        return mc.gameRenderer.mainCamera();
    }

    /** The projection the level was last drawn with. */
    public static org.joml.Matrix4f cameraProjection(Minecraft mc) {
        return mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.projectionMatrix;
    }

    /** F1: the player hid the HUD. */
    public static boolean hudHidden(Minecraft mc) {
        return mc.gui.hud.isHidden();
    }
}
