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
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.CalculatePlayerTurnEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

/** Game-bus wiring: input, per-tick work and session lifecycle. */
public final class ClientEvents {
    /** Makes the vanilla turn formula {@code (s * 0.6 + 0.2)^3} evaluate to practically zero. */
    private static final double FROZEN_TURN_SENSITIVITY = -1.0 / 3.0;
    private static final double ZOOM_KEY_FACTOR_PER_TICK = 1.04;
    private static final double ZOOM_WHEEL_FACTOR = 1.15;
    private static final int STATE_SAVE_INTERVAL_TICKS = 100;

    private static int stateSaveCountdown = STATE_SAVE_INTERVAL_TICKS;

    private ClientEvents() {}

    @SubscribeEvent
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        Minecraft mc = Minecraft.getInstance();
        WaypointStore.get().open(WaypointStore.worldKey(mc));
        DeathTracker.reset();
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        CameraAngles.storeIfDirty();
        saveZoom();
        IsoMapConfig.save();
        WaypointStore.get().close();
        PingClient.reset();
        DeathTracker.reset();
        // May run off the render thread during shutdown paths; GPU work must not.
        Minecraft.getInstance().execute(MapRuntime::onDisconnect);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
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

    @SubscribeEvent
    public static void onGameShutdown(GameShuttingDownEvent event) {
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

    /** While the camera key is held, mouse movement turns the map instead of the player. */
    @SubscribeEvent
    public static void onPlayerTurn(CalculatePlayerTurnEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null && KeyBindings.CAMERA_HOLD.isDown() && MapRuntime.isHudVisible(mc)) {
            CameraAngles.addMouseInput(mc.mouseHandler.getXVelocity(), mc.mouseHandler.getYVelocity());
            event.setMouseSensitivity(FROZEN_TURN_SENSITIVITY);
            event.setCinematicCameraEnabled(false);
        }
    }

    /** The wheel keeps switching hotbar slots unless the camera key is held. */
    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null && IsoMapConfig.WHEEL_ZOOM.getAsBoolean() && KeyBindings.CAMERA_HOLD.isDown() && MapRuntime.isHudVisible(mc)) {
            MapRuntime.hudView().multiplyZoom(event.getScrollDeltaY() > 0 ? ZOOM_WHEEL_FACTOR : 1.0 / ZOOM_WHEEL_FACTOR);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onChat(ClientChatReceivedEvent event) {
        PingClient.onChat(event);
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        PingWorldRenderer.render(event);
    }

    @SubscribeEvent
    public static void onFrameStart(RenderFrameEvent.Pre event) {
        MapRuntime.onFrameStart(event.getPartialTick().getGameTimeDeltaPartialTick(false));
    }
}
