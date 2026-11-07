package dev.isomap.client.render;

import dev.isomap.client.map.TerrainCache;
import dev.isomap.config.IsoMapConfig;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/** Owns the terrain cache and the map images, and renders those images before the frame's main pass starts. */
public final class MapRuntime {
    private static final TerrainCache TERRAIN = new TerrainCache();
    private static final MapView HUD_VIEW = new MapView(1.5);

    @Nullable
    private static MapView screenView;
    private static int screenViewWidth;
    private static int screenViewHeight;
    private static boolean hudZoomLoaded;

    private MapRuntime() {}

    public static TerrainCache terrain() {
        return TERRAIN;
    }

    public static MapView hudView() {
        if (!hudZoomLoaded) {
            hudZoomLoaded = true;
            HUD_VIEW.setZoomTarget(IsoMapConfig.STATE_ZOOM.get());
        }
        return HUD_VIEW;
    }

    public static void attachScreenView(MapView view, int width, int height) {
        screenView = view;
        screenViewWidth = width;
        screenViewHeight = height;
    }

    public static void detachScreenView(MapView view) {
        if (screenView == view) {
            screenView = null;
            view.close();
            TERRAIN.clearScreenView();
        }
    }

    public static boolean isHudVisible(Minecraft mc) {
        if (!IsoMapConfig.ENABLED.getAsBoolean() || mc.options.hideGui || mc.player == null || mc.level == null) {
            return false;
        }
        return !(IsoMapConfig.HIDE_WITH_DEBUG.getAsBoolean() && mc.getDebugOverlay().showDebugScreen());
    }

    /** Called at the very start of each frame, while no other render pass is active. */
    public static void onFrameStart(float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        TERRAIN.uploadPending();
        if (player == null || mc.level == null) {
            return;
        }
        MapView view = screenView;
        if (view != null) {
            // The full map covers the HUD one, so only one of them needs drawing.
            view.update(TERRAIN, player, partialTick, screenViewWidth, screenViewHeight, true);
        } else if (isHudVisible(mc)) {
            int size = HudOverlay.clampedSize(mc);
            hudView().update(TERRAIN, player, partialTick, size, size, true);
        } else {
            // The image takes video memory only while it is shown.
            HUD_VIEW.close();
        }
    }

    @Nullable
    public static MapView screenView() {
        return screenView;
    }

    public static boolean isScreenViewActive() {
        return screenView != null;
    }

    public static void onDisconnect() {
        TERRAIN.clear();
        HUD_VIEW.close();
    }
}
