package dev.isomap.ping;

import dev.isomap.platform.Platform;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/**
 * The server side of pings: it checks each ping and passes it to other players who have the mod. The channel is optional,
 * so players without the mod can join and servers without it stay reachable; the client then falls back to chat if allowed.
 * Each loader registers the channel itself and hands incoming pings to {@link #onServer}, on the server thread.
 */
public final class PingNetwork {
    public static final String PROTOCOL_VERSION = "1";
    private static final long MIN_INTERVAL_MS = 200L;
    private static final double MAX_COORDINATE = 3.0e7;

    private static final Map<UUID, Long> LAST_PING = new HashMap<>();
    private static int received;

    private PingNetwork() {}

    public static void onServer(PingPayload payload, ServerPlayer sender) {
        if (sender == null || !isValid(payload)) {
            return;
        }
        received++;
        long now = System.currentTimeMillis();
        Long last = LAST_PING.get(sender.getUUID());
        if (last != null && now - last < MIN_INTERVAL_MS) {
            return;
        }
        LAST_PING.put(sender.getUUID(), now);

        PingPayload stamped = new PingPayload(sender.getUUID(), sender.getGameProfile().getName(), sender.level().dimension().location(),
                payload.x(), payload.y(), payload.z(), payload.entityId());
        for (ServerPlayer player : sender.server.getPlayerList().getPlayers()) {
            if (player != sender && isAudience(sender, player) && Platform.get().canSend(player, PingPayload.TYPE)) {
                Platform.get().sendToPlayer(player, stamped);
            }
        }
    }

    /** Someone on a team pings for that team; without a team a ping is for everyone. */
    private static boolean isAudience(ServerPlayer sender, ServerPlayer player) {
        return sender.getTeam() == null || sender.isAlliedTo(player);
    }

    private static boolean isValid(PingPayload payload) {
        return isCoordinate(payload.x()) && isCoordinate(payload.y()) && isCoordinate(payload.z());
    }

    private static boolean isCoordinate(double value) {
        return Double.isFinite(value) && Math.abs(value) <= MAX_COORDINATE;
    }

    /** How many pings arrived from clients; lets the QA harness observe the channel. */
    public static int receivedCount() {
        return received;
    }
}
