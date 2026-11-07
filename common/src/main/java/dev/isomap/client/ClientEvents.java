package dev.isomap.client;

import dev.isomap.client.input.KeyBindings;
import dev.isomap.client.ping.PingClient;
import dev.isomap.client.ping.PingManager;
import dev.isomap.client.ping.PingWorldRenderer;
import dev.isomap.client.render.CameraAngles;
import dev.isomap.client.render.MapRuntime;
import dev.isomap.client.sound.UiSound;
import dev.isomap.client.ui.screen.MapScreen;
import dev.isomap.client.ui.screen.SettingsScreen;
import dev.isomap.client.ui.screen.WaypointEditScreen;
import dev.isomap.client.ui.screen.WaypointListScreen;
import dev.isomap.client.waypoint.DeathTracker;
import dev.isomap.client.waypoint.WaypointStore;
import dev.isomap.config.IsoMapConfig;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import javax.annotation.Nullable;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

/**
 * What the mod does on input, per tick and over a session. Loader-neutral: each loader's own glue calls these methods from
 * its events or, where there is no event, from a mixin.
 */
public final class ClientEvents {
    private static final double ZOOM_KEY_FACTOR_PER_TICK = 1.04;
    private static final double ZOOM_WHEEL_FACTOR = 1.15;
    private static final int STATE_SAVE_INTERVAL_TICKS = 100;

    private static int stateSaveCountdown = STATE_SAVE_INTERVAL_TICKS;
    /** Lets the QA harness keep stray mouse movement from turning the player during a run. */
    public static BooleanSupplier testTurnFreeze = () -> false;

    private ClientEvents() {}

    public static void onLogin() {
        Minecraft mc = Minecraft.getInstance();
        WaypointStore.get().open(WaypointStore.worldKey(mc));
        DeathTracker.reset();
    }

    public static void onLogout() {
        CameraAngles.storeIfDirty();
        saveZoom();
        IsoMapConfig.save();
        WaypointStore.get().close();
        PingClient.reset();
        DeathTracker.reset();
        // May run off the render thread during shutdown paths; GPU work must not.
        Minecraft.getInstance().execute(MapRuntime::onDisconnect);
    }

    public static void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        MapRuntime.terrain().tick(mc);
        DeathTracker.tick(mc);
        PingManager.tick(mc);
        handleKeys(mc);
        if (--stateSaveCountdown <= 0) {
            stateSaveCountdown = STATE_SAVE_INTERVAL_TICKS;
            CameraAngles.storeIfDirty();
            saveZoom();
            // Catches every change made since the last write, so a crash can lose only the last few seconds.
            IsoMapConfig.save();
        }
    }

    private static void saveZoom() {
        double zoom = MapRuntime.hudView().zoomTarget();
        if (Math.abs(zoom - IsoMapConfig.STATE_ZOOM.get()) > 1.0e-3) {
            IsoMapConfig.STATE_ZOOM.set(zoom);
        }
    }

    public static void onGameShutdown() {
        CameraAngles.storeIfDirty();
        IsoMapConfig.save();
        MapRuntime.terrain().saveExploredAndWait();
    }

    private static void handleKeys(Minecraft mc) {
        if (mc.player == null) {
            return;
        }
        while (KeyBindings.OPEN_MAP.consumeClick()) {
            mc.setScreen(new MapScreen(null));
        }
        while (KeyBindings.OPEN_SETTINGS.consumeClick()) {
            mc.setScreen(new SettingsScreen(null));
        }
        while (KeyBindings.OPEN_WAYPOINTS.consumeClick()) {
            mc.setScreen(new WaypointListScreen(null));
        }
        while (KeyBindings.CREATE_WAYPOINT.consumeClick()) {
            mc.setScreen(WaypointEditScreen.forNewAtPlayer(null));
        }
        while (KeyBindings.PING.consumeClick()) {
            PingClient.pingLookedAt(mc);
        }
        while (KeyBindings.TOGGLE_VISIBLE.consumeClick()) {
            boolean enabled = !IsoMapConfig.ENABLED.getAsBoolean();
            IsoMapConfig.ENABLED.set(enabled);
            IsoMapConfig.save();
            UiSound.toggle(enabled);
        }
        while (KeyBindings.TOP_VIEW.consumeClick()) {
            CameraAngles.toggleTopView();
            UiSound.CLICK.play();
        }
        while (KeyBindings.CAMERA_RESET.consumeClick()) {
            CameraAngles.reset();
            MapRuntime.hudView().setZoomTarget(IsoMapConfig.STATE_ZOOM.getDefault());
            UiSound.CLICK.play();
        }
        if (KeyBindings.ZOOM_IN.isDown()) {
            MapRuntime.hudView().multiplyZoom(ZOOM_KEY_FACTOR_PER_TICK);
        }
        if (KeyBindings.ZOOM_OUT.isDown()) {
            MapRuntime.hudView().multiplyZoom(1.0 / ZOOM_KEY_FACTOR_PER_TICK);
        }
    }

    /**
     * While the camera key is held, mouse movement turns the map instead of the player.
     *
     * @param dx mouse movement since the last turn, in screen pixels
     * @return true if the player must not turn with this movement
     */
    public static boolean consumeTurn(double dx, double dy) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null && KeyBindings.CAMERA_HOLD.isDown() && MapRuntime.isHudVisible(mc)) {
            CameraAngles.addMouseInput(dx, dy);
            return true;
        }
        return testTurnFreeze.getAsBoolean();
    }

    /**
     * The wheel keeps switching hotbar slots unless the camera key is held.
     *
     * @return true if the scroll was used for zooming and the game must not see it
     */
    public static boolean onScroll(double deltaY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null && IsoMapConfig.WHEEL_ZOOM.getAsBoolean() && KeyBindings.CAMERA_HOLD.isDown() && MapRuntime.isHudVisible(mc)) {
            MapRuntime.hudView().multiplyZoom(deltaY > 0 ? ZOOM_WHEEL_FACTOR : 1.0 / ZOOM_WHEEL_FACTOR);
            return true;
        }
        return false;
    }

    /** @return true if the line was a ping and must be hidden from chat */
    public static boolean onChat(Component message, @Nullable UUID sender) {
        return PingClient.onChat(message, sender);
    }

    /** After the level is drawn, with the model-view matrix it was drawn with. */
    public static void onRenderLevel(Camera camera, Matrix4f modelView) {
        PingWorldRenderer.render(camera, modelView);
    }

    /** Before anything of the frame is drawn. */
    public static void onFrameStart(float partialTick) {
        MapRuntime.onFrameStart(partialTick);
    }
}
