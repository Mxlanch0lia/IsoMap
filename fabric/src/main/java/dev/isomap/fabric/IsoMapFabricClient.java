package dev.isomap.fabric;

import dev.isomap.client.ClientEvents;
import dev.isomap.client.input.KeyBindings;
import dev.isomap.client.ping.PingClient;
import dev.isomap.client.render.HudOverlay;
import dev.isomap.config.IsoMapConfig;
import dev.isomap.ping.PingPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

/**
 * Client entry point: passes Fabric API's events to the shared {@link ClientEvents}. What Fabric API has no event for
 * (the start of a frame, mouse turning and the wheel) comes through the mixins in {@code dev.isomap.fabric.mixin}.
 */
public final class IsoMapFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        FabricPlatform.install();
        IsoMapConfig.load();

        KeyBindings.ALL.forEach(KeyBindingHelper::registerKeyBinding);
        HudRenderCallback.EVENT.register(HudOverlay::render);

        ClientTickEvents.END_CLIENT_TICK.register(mc -> ClientEvents.onClientTick());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> ClientEvents.onLogin());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> ClientEvents.onLogout());
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> ClientEvents.onGameShutdown());

        // Player chat and system lines (tellraw, plugins) arrive through different events; pings may come through either.
        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signed, sender, params, receptionTimestamp) ->
                !ClientEvents.onChat(message, sender == null ? null : sender.getId()));
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> overlay || !ClientEvents.onChat(message, null));

        WorldRenderEvents.LAST.register(context -> ClientEvents.onRenderLevel(context.camera(), context.positionMatrix()));
        ClientPlayNetworking.registerGlobalReceiver(PingPayload.TYPE, (payload, context) -> PingClient.receive(payload));
    }
}
