package dev.isomap.client.ping;

import dev.isomap.client.sound.UiSound;
import dev.isomap.config.IsoMapConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/** The pings alive right now. They live in memory only and vanish on their own. */
public final class PingManager {
    /** Anyone can send pings, so a flood must only cost its sender's older pings. */
    private static final int MAX_PER_SENDER = 3;
    private static final int MAX_TOTAL = 24;

    private static final List<Ping> ACTIVE = new ArrayList<>();
    private static final List<Ping> VIEW = Collections.unmodifiableList(ACTIVE);

    private PingManager() {}

    public static List<Ping> active() {
        return VIEW;
    }

    static void add(UUID sender, String name, String dimension, double x, double y, double z, int entityId) {
        long sameSender = ACTIVE.stream().filter(p -> p.sender.equals(sender)).count();
        if (sameSender >= MAX_PER_SENDER) {
            for (int i = 0; i < ACTIVE.size(); i++) {
                if (ACTIVE.get(i).sender.equals(sender)) {
                    ACTIVE.remove(i);
                    break;
                }
            }
        }
        if (ACTIVE.size() >= MAX_TOTAL) {
            ACTIVE.remove(0);
        }
        ACTIVE.add(new Ping(sender, name, dimension, x, y, z, entityId, System.currentTimeMillis()));
        UiSound.PING.play();
    }

    public static void tick(Minecraft mc) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long lifetime = lifetimeMs();
        ACTIVE.removeIf(ping -> now - ping.createdAt > lifetime);
        if (mc.level == null) {
            return;
        }
        String dimension = mc.level.dimension().location().toString();
        for (Ping ping : ACTIVE) {
            if (ping.entityId != dev.isomap.ping.PingPayload.NO_ENTITY && ping.dimension.equals(dimension)) {
                Entity entity = mc.level.getEntity(ping.entityId);
                if (entity != null) {
                    ping.x = entity.getX();
                    ping.y = entity.getY();
                    ping.z = entity.getZ();
                }
            }
        }
    }

    public static long lifetimeMs() {
        return IsoMapConfig.PING_SECONDS.getAsInt() * 1000L;
    }

    public static void clear() {
        ACTIVE.clear();
    }
}
