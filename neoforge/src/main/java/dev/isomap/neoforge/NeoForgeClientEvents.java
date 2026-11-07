package dev.isomap.neoforge;

import dev.isomap.client.ClientEvents;
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

/** Passes NeoForge's game-bus events to the shared {@link ClientEvents}. */
public final class NeoForgeClientEvents {
    /** Makes the vanilla turn formula {@code (s * 0.6 + 0.2)^3} evaluate to practically zero. */
    public static final double FROZEN_TURN_SENSITIVITY = -1.0 / 3.0;

    private NeoForgeClientEvents() {}

    @SubscribeEvent
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        ClientEvents.onLogin();
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientEvents.onLogout();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ClientEvents.onClientTick();
    }

    @SubscribeEvent
    public static void onGameShutdown(GameShuttingDownEvent event) {
        ClientEvents.onGameShutdown();
    }

    @SubscribeEvent
    public static void onPlayerTurn(CalculatePlayerTurnEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (ClientEvents.consumeTurn(mc.mouseHandler.getXVelocity(), mc.mouseHandler.getYVelocity())) {
            event.setMouseSensitivity(FROZEN_TURN_SENSITIVITY);
            event.setCinematicCameraEnabled(false);
        }
    }

    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        if (ClientEvents.onScroll(event.getScrollDeltaY())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onChat(ClientChatReceivedEvent event) {
        if (ClientEvents.onChat(event.getMessage(), event.getSender())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            ClientEvents.onRenderLevel(event.getCamera(), event.getModelViewMatrix());
        }
    }

    @SubscribeEvent
    public static void onFrameStart(RenderFrameEvent.Pre event) {
        ClientEvents.onFrameStart(event.getPartialTick().getGameTimeDeltaPartialTick(false));
    }
}
