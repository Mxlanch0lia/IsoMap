package dev.isomap.client.waypoint;

import dev.isomap.client.sound.UiSound;
import dev.isomap.config.IsoMapConfig;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;

/**
 * Detects the local player's deaths without any server support, from two signals:
 * the health edge seen while the death screen is up, and the death location the server sends with the respawn packet.
 * The second one covers {@code doImmediateRespawn}, where the health edge may never reach the client.
 * One death usually fires both, so {@link #isDuplicate} merges them.
 */
public final class DeathTracker {
    private static final long MERGE_WINDOW_MS = 30_000L;
    private static final int MERGE_DISTANCE = 16;

    /** How long after respawning the server's report of an already seen death is still expected. */
    private static final int ECHO_WINDOW_TICKS = 100;

    private static boolean wasDead;
    private static boolean baselineTaken;
    private static Optional<GlobalPos> lastServerDeath = Optional.empty();
    private static int echoTicksLeft;

    private DeathTracker() {}

    public static void reset() {
        wasDead = false;
        baselineTaken = false;
        lastServerDeath = Optional.empty();
        echoTicksLeft = 0;
    }

    public static void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }
        Optional<GlobalPos> serverDeath = player.getLastDeathLocation();
        if (!baselineTaken) {
            // The login packet repeats the death from an earlier session; that one is not new.
            baselineTaken = true;
            lastServerDeath = serverDeath;
        }

        boolean dead = player.isDeadOrDying();
        if (dead && !wasDead) {
            record(mc, player.level().dimension().location().toString(), player.blockPosition());
        }
        wasDead = dead;
        if (dead) {
            echoTicksLeft = ECHO_WINDOW_TICKS;
        } else if (echoTicksLeft > 0) {
            echoTicksLeft--;
        }

        if (!serverDeath.equals(lastServerDeath)) {
            lastServerDeath = serverDeath;
            // This death was already handled at the health edge, even if the setting then said to skip it.
            if (echoTicksLeft == 0) {
                serverDeath.ifPresent(pos -> record(mc, pos.dimension().location().toString(), pos.pos()));
            }
            echoTicksLeft = 0;
        }
    }

    private static boolean isDuplicate(WaypointStore store, String dimension, BlockPos pos) {
        long now = System.currentTimeMillis();
        for (Waypoint w : store.all()) {
            if (!w.death || !w.dimension.equals(dimension)) {
                continue;
            }
            boolean samePos = w.x == pos.getX() && w.y == pos.getY() && w.z == pos.getZ();
            boolean recentAndClose = now - w.createdAt < MERGE_WINDOW_MS
                    && Math.abs(w.x - pos.getX()) <= MERGE_DISTANCE
                    && Math.abs(w.y - pos.getY()) <= MERGE_DISTANCE
                    && Math.abs(w.z - pos.getZ()) <= MERGE_DISTANCE;
            if (samePos || recentAndClose) {
                return true;
            }
        }
        return false;
    }

    private static void record(Minecraft mc, String dimension, BlockPos pos) {
        WaypointStore store = WaypointStore.get();
        if (!IsoMapConfig.DEATH_ENABLED.getAsBoolean() || !store.isOpen() || isDuplicate(store, dimension, pos)) {
            return;
        }
        Waypoint w = new Waypoint();
        w.name = Component.translatable("isomap.death.name").getString();
        w.x = pos.getX();
        w.y = pos.getY();
        w.z = pos.getZ();
        w.dimension = dimension;
        w.color = 0xE5655C;
        w.death = true;
        w.addTag(Waypoint.DEATH_TAG);
        store.add(w);
        store.trimDeathMarkers(IsoMapConfig.DEATH_MAX.getAsInt());
        UiSound.WAYPOINT_ADD.play();
        if (IsoMapConfig.DEATH_CHAT.getAsBoolean() && mc.player != null) {
            mc.player.displayClientMessage(Component.translatable("isomap.death.message",
                    pos.getX(), pos.getY(), pos.getZ(), Dimensions.label(dimension)).withStyle(ChatFormatting.GRAY), false);
        }
    }
}
