package dev.isomap.neoforge;

import dev.isomap.IsoMap;
import dev.isomap.ping.PingNetwork;
import dev.isomap.ping.PingPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** The part loaded on both sides. On a server it only relays pings; nothing here may touch client-only classes. */
@Mod(IsoMap.MOD_ID)
public final class IsoMapNeoForge {
    public IsoMapNeoForge(IEventBus modBus) {
        NeoForgePlatform.install();
        modBus.addListener(IsoMapNeoForge::registerPayloads);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        // Optional: players without the mod can join, and servers without it stay reachable.
        event.registrar(PingNetwork.PROTOCOL_VERSION).optional().playBidirectional(PingPayload.TYPE, PingPayload.CODEC, (payload, context) -> {
            if (context.flow().isClientbound()) {
                // Only ever reached on a client; a dedicated server never loads this class.
                dev.isomap.client.ping.PingClient.receive(payload);
            } else if (context.player() instanceof ServerPlayer sender) {
                PingNetwork.onServer(payload, sender);
            }
        });
    }
}
