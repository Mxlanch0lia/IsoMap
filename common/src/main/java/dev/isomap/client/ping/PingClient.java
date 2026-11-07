package dev.isomap.client.ping;

import dev.isomap.client.waypoint.Dimensions;
import dev.isomap.config.IsoMapConfig;
import dev.isomap.ping.PingPayload;
import dev.isomap.platform.Platform;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Placing and receiving pings. A server with the mod carries them invisibly. On any other server they can, if the player
 * opts in, travel as ordinary chat messages: the message stays readable for players without the mod, and this class turns it
 * into a ping and hides it for those who have the mod.
 */
public final class PingClient {
    private static final double REACH_BLOCKS = 256.0;
    private static final double MAX_COORDINATE = 3.0e7;
    private static final long MIN_INTERVAL_MS = 300L;
    private static final long CHAT_ECHO_MS = 10_000L;
    private static final UUID UNKNOWN_SENDER = new UUID(0L, 0L);
    private static final Pattern CHAT_PING = Pattern.compile("\\[IsoMap\\] ping (-?\\d{1,8}) (-?\\d{1,8}) (-?\\d{1,8}) ([a-z0-9_.-]+:[a-z0-9_./-]+)");

    private static long lastSent;
    private static boolean hintShown;
    private static String lastChatPing = "";
    private static long lastChatPingAt;
    private static int hiddenLines;

    private PingClient() {}

    /** Pings whatever the player is looking at: an entity first, otherwise a block. */
    public static void pingLookedAt(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || !IsoMapConfig.PINGS_ENABLED.getAsBoolean()) {
            return;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1f);
        Vec3 end = eye.add(look.scale(REACH_BLOCKS));
        HitResult block = player.pick(REACH_BLOCKS, 1f, true);
        double reachSquared = block.getType() == HitResult.Type.MISS ? REACH_BLOCKS * REACH_BLOCKS : block.getLocation().distanceToSqr(eye);
        AABB box = player.getBoundingBox().expandTowards(look.scale(REACH_BLOCKS)).inflate(1.0);
        EntityHitResult entity = ProjectileUtil.getEntityHitResult(player, eye, end, box, e -> !e.isSpectator() && e.isPickable(), reachSquared);
        if (entity != null) {
            Entity target = entity.getEntity();
            send(mc, target.getX(), target.getY(), target.getZ(), target.getId());
        } else if (block instanceof BlockHitResult hit && block.getType() != HitResult.Type.MISS) {
            send(mc, hit.getLocation().x, hit.getLocation().y, hit.getLocation().z, PingPayload.NO_ENTITY);
        } else {
            player.displayClientMessage(Component.translatable("isomap.ping.too_far"), true);
        }
    }

    /** Pings a place on the map. */
    public static void pingAt(Minecraft mc, double x, double y, double z) {
        if (IsoMapConfig.PINGS_ENABLED.getAsBoolean()) {
            send(mc, x, y, z, PingPayload.NO_ENTITY);
        }
    }

    private static void send(Minecraft mc, double x, double y, double z, int entityId) {
        LocalPlayer player = mc.player;
        ClientPacketListener connection = mc.getConnection();
        long now = System.currentTimeMillis();
        if (player == null || connection == null || now - lastSent < MIN_INTERVAL_MS) {
            return;
        }
        lastSent = now;
        String dimension = Dimensions.current(mc);
        PingManager.add(player.getUUID(), player.getGameProfile().getName(), dimension, x, y, z, entityId);

        if (Platform.get().clientCanSend(PingPayload.TYPE)) {
            ResourceLocation key = ResourceLocation.tryParse(dimension);
            if (key != null) {
                Platform.get().sendToServer(new PingPayload(player.getUUID(), player.getGameProfile().getName(), key, x, y, z, entityId));
            }
        } else if (IsoMapConfig.PINGS_THROUGH_CHAT.getAsBoolean()) {
            sendThroughChat(mc, x, y, z);
        } else if (!hintShown) {
            hintShown = true;
            player.displayClientMessage(Component.translatable("isomap.ping.local_only"), true);
        }
    }

    /** Public only so the QA harness can exercise the fallback on a server that has the mod. */
    public static void sendThroughChat(Minecraft mc, double x, double y, double z) {
        ClientPacketListener connection = mc.getConnection();
        if (connection != null) {
            String body = String.format(Locale.ROOT, "%d %d %d %s", (long) Math.floor(x), (long) Math.floor(y), (long) Math.floor(z), Dimensions.current(mc));
            lastChatPing = body;
            lastChatPingAt = System.currentTimeMillis();
            connection.sendChat("[IsoMap] ping " + body);
        }
    }

    /** A ping relayed by the server. Runs only on a client. */
    public static void receive(PingPayload payload) {
        if (IsoMapConfig.PINGS_ENABLED.getAsBoolean()) {
            PingManager.add(payload.sender(), payload.senderName(), payload.dimension().toString(),
                    payload.x(), payload.y(), payload.z(), payload.entityId());
        }
    }

    /**
     * Turns chat pings into real ones, whether a player or a server plugin delivered the line.
     *
     * @param sender the player who wrote it, or null for a system message
     * @return true if the line is a ping and must be hidden from chat
     */
    public static boolean onChat(Component message, @Nullable UUID sender) {
        if (!IsoMapConfig.PINGS_ENABLED.getAsBoolean()) {
            return false;
        }
        Matcher matcher = CHAT_PING.matcher(message.getString());
        if (!matcher.find()) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return false;
        }
        hiddenLines++;
        String body = matcher.group(1) + " " + matcher.group(2) + " " + matcher.group(3) + " " + matcher.group(4);
        boolean own = player.getUUID().equals(sender)
                || (body.equals(lastChatPing) && System.currentTimeMillis() - lastChatPingAt < CHAT_ECHO_MS);
        double x = Double.parseDouble(matcher.group(1)) + 0.5;
        double y = Double.parseDouble(matcher.group(2));
        double z = Double.parseDouble(matcher.group(3)) + 0.5;
        if (own || Math.abs(x) > MAX_COORDINATE || Math.abs(z) > MAX_COORDINATE) {
            return true;
        }
        boolean known = sender != null && !sender.equals(UNKNOWN_SENDER);
        PingManager.add(known ? sender : UNKNOWN_SENDER, nameOf(mc, known ? sender : null), matcher.group(4), x, y, z, PingPayload.NO_ENTITY);
        return true;
    }

    /** How many chat lines were recognised as pings and hidden; lets the QA harness check it on any loader. */
    public static int hiddenLineCount() {
        return hiddenLines;
    }

    private static String nameOf(Minecraft mc, @Nullable UUID sender) {
        ClientPacketListener connection = mc.getConnection();
        PlayerInfo info = connection == null || sender == null ? null : connection.getPlayerInfo(sender);
        return info == null ? "?" : info.getProfile().getName();
    }

    /** Forgets session state on leaving a world. */
    public static void reset() {
        hintShown = false;
        lastChatPing = "";
        PingManager.clear();
    }
}
