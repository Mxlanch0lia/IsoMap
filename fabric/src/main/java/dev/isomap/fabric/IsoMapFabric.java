package dev.isomap.fabric;

import dev.isomap.ping.PingNetwork;
import dev.isomap.ping.PingPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/** The part loaded on both sides. On a server it only relays pings; nothing here may touch client-only classes. */
public final class IsoMapFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        FabricPlatform.install();
        // The channel is optional by nature on Fabric: a side without the mod simply never registers it.
        PayloadTypeRegistry.playC2S().register(PingPayload.TYPE, PingPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(PingPayload.TYPE, PingPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(PingPayload.TYPE, (payload, context) -> PingNetwork.onServer(payload, context.player()));
    }
}
