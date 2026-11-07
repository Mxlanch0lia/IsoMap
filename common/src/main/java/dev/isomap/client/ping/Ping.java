package dev.isomap.client.ping;

import java.util.UUID;

/** A short-lived marker someone placed for the others. Follows an entity while that entity is known. */
public final class Ping {
    private static final int[] COLORS = {0x4FE3FF, 0xFFD24F, 0xFF6FD8, 0x7CFF6B, 0xFF8A4F, 0xB48EFF};

    public final UUID sender;
    public final String senderName;
    public final String dimension;
    public final int entityId;
    public final long createdAt;
    public final int color;
    public double x;
    public double y;
    public double z;

    Ping(UUID sender, String senderName, String dimension, double x, double y, double z, int entityId, long createdAt) {
        this.sender = sender;
        this.senderName = senderName;
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.entityId = entityId;
        this.createdAt = createdAt;
        // Everyone has their own colour, and the same one on every screen.
        this.color = COLORS[Math.floorMod(sender.hashCode(), COLORS.length)];
    }
}
