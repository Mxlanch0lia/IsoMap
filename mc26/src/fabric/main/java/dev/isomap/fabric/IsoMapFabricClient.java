package dev.isomap.fabric;

import dev.isomap.IsoMap;
import dev.isomap.client.ClientEvents;
import dev.isomap.client.input.KeyBindings;
import dev.isomap.client.ping.PingClient;
import dev.isomap.client.render.HudOverlay;
import dev.isomap.config.IsoMapConfig;
import dev.isomap.ping.PingPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;

/**
 * Client entry point: passes Fabric API's events to the shared {@link ClientEvents}. What Fabric API has no event for
 * (the start of a frame, mouse turning and the wheel) comes through the mixins in {@code dev.isomap.fabric.mixin}.
 */
public final class IsoMapFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        FabricPlatform.install();
        IsoMapConfig.load();

        KeyMapping.Category.register(KeyBindings.CATEGORY.id());
        KeyBindings.ALL.forEach(KeyMappingHelper::registerKeyMapping);
        HudElementRegistry.addLast(IsoMap.id("minimap"), HudOverlay::render);

        ClientTickEvents.END_CLIENT_TICK.register(mc -> ClientEvents.onClientTick());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> ClientEvents.onLogin());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> ClientEvents.onLogout());
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> ClientEvents.onGameShutdown());

        // Player chat and system lines (tellraw, plugins) arrive through different events; pings may come through either.
        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signed, sender, params, receptionTimestamp) ->
                !ClientEvents.onChat(message, sender == null ? null : sender.id()));
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> overlay || !ClientEvents.onChat(message, null));

        LevelRenderEvents.END_MAIN.register(context -> ClientEvents.onRenderLevel(dev.isomap.client.Compat.camera(Minecraft.getInstance()),
                new Matrix4f(context.levelState().cameraRenderState.viewRotationMatrix)));
        ClientPlayNetworking.registerGlobalReceiver(PingPayload.TYPE, (payload, context) -> PingClient.receive(payload));
    }
}
