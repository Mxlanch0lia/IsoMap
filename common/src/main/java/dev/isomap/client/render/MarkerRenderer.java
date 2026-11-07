package dev.isomap.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.isomap.client.map.ChunkSnapshot;
import dev.isomap.client.ping.Ping;
import dev.isomap.client.ping.PingManager;
import dev.isomap.client.ui.Theme;
import dev.isomap.client.waypoint.Dimensions;
import dev.isomap.client.waypoint.Waypoint;
import dev.isomap.client.waypoint.WaypointStore;
import dev.isomap.config.IsoMapConfig;
import dev.isomap.config.LabelMode;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Everything drawn on top of the map image: waypoints, the player, compass letters. Squares and rectangles, except for the player's arrow. */
public final class MarkerRenderer {
    private static final int CLOSE_LABEL_BLOCKS = 64;
    private static final int LABEL_MAX_WIDTH = 96;
    private static final double MAX_WAYPOINT_OFFSET = 48.0;
    private static final double MAX_PLAYER_OFFSET = 8.0;
    private static final int PING_EDGE = 7;
    private static final int PING_PULSE_MS = 1400;
    private static final int PING_RIPPLE_SIZE = 9;
    private static final float ARROW_TIP = 6.5f;
    private static final float ARROW_BACK = 5f;
    private static final float ARROW_SIDE = 4.5f;
    private static final float ARROW_NOTCH = 2.5f;
    private static final Vector3f POINT = new Vector3f();
    private static final Vector3f ORIGIN = new Vector3f();

    private MarkerRenderer() {}

    /**
     * @param cx,cy centre of the map image in GUI units
     * @param halfW,halfH half of its size
     * @return the waypoint under the mouse, if any
     */
    @Nullable
    public static Waypoint drawWaypoints(GuiGraphics g, MapView view, int cx, int cy, int halfW, int halfH, float alpha,
            double mouseX, double mouseY) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || !IsoMapConfig.SHOW_WAYPOINTS.getAsBoolean()) {
            return null;
        }
        String dimension = Dimensions.current(mc);
        boolean deathVisible = IsoMapConfig.DEATH_VISIBLE.getAsBoolean();
        boolean edges = IsoMapConfig.EDGE_MARKERS.getAsBoolean();
        int size = IsoMapConfig.MARKER_SIZE.getAsInt();
        LabelMode labels = IsoMapConfig.LABEL_MODE.get();
        Waypoint hovered = null;

        for (Waypoint w : WaypointStore.get().all()) {
            if (!w.visible || !w.dimension.equals(dimension) || (w.death && !deathVisible)) {
                continue;
            }
            projectWaypoint(view, w, POINT);
            float px = POINT.x;
            float py = POINT.y;
            boolean inside = Math.abs(px) <= halfW && Math.abs(py) <= halfH;
            if (!inside && !edges) {
                continue;
            }
            int half = inside ? size / 2 : Math.max(1, size / 2 - 1);
            int x = cx + Mth.floor(Mth.clamp(px, -halfW + half + 1, halfW - half - 1));
            int y = cy + Mth.floor(Mth.clamp(py, -halfH + half + 1, halfH - half - 1));
            if (inside) {
                drawStem(g, view, w, x, y, half, alpha);
            }
            drawMarker(g, x, y, half, w, inside ? alpha : alpha * 0.7f);

            boolean underMouse = Math.abs(mouseX - x) <= half + 2 && Math.abs(mouseY - y) <= half + 2;
            if (underMouse) {
                hovered = w;
            }
            double distance = Math.sqrt(player.distanceToSqr(w.x + 0.5, w.y + 0.5, w.z + 0.5));
            boolean label = inside && (underMouse || labels == LabelMode.ALL || (labels == LabelMode.CLOSE && distance <= CLOSE_LABEL_BLOCKS));
            if (label) {
                drawLabel(g, mc.font, w, distance, x, y - half - 11, cx - halfW, cx + halfW, cy - halfH, alpha);
            }
        }
        return hovered;
    }

    /**
     * Projects a waypoint at its real height, so one set on a mountain stays on the mountain. Only heights with no meaning on
     * the current map are limited: nothing is shown above a cave cut, and a point far above or below the terrain at its column
     * stays near it instead of leaving the map.
     */
    public static void projectWaypoint(MapView view, Waypoint w, Vector3f out) {
        double ground = groundAt(view, w);
        double shown = Mth.clamp((double) w.y, ground - MAX_WAYPOINT_OFFSET, ground + MAX_WAYPOINT_OFFSET);
        view.project(w.x + 0.5, limitToCut(shown), w.z + 0.5, out);
    }

    private static double groundAt(MapView view, Waypoint w) {
        int height = MapRuntime.terrain().heightAt(w.x, w.z);
        return height == ChunkSnapshot.NO_DATA ? view.focusY() : height + 1.0;
    }

    private static double limitToCut(double y) {
        return MapRuntime.terrain().isSlicing() ? Math.min(y, MapRuntime.terrain().sliceTop()) : y;
    }

    /**
     * Projects the player. A small height difference is kept so the marker sits on the hill the player stands on; a large one
     * is limited, because the camera turns around the ground near the player and the marker must stay over that spot.
     */
    public static void projectPlayer(MapView view, double x, double y, double z, Vector3f out) {
        view.projectFree(x, limitToCut(Mth.clamp(y, view.focusY() - MAX_PLAYER_OFFSET, view.focusY() + MAX_PLAYER_OFFSET)), z, out);
    }

    /** A thin pin from the marker down to the terrain, which is what makes its height readable. Verticals stay vertical at any camera angle. */
    private static void drawStem(GuiGraphics g, MapView view, Waypoint w, int x, int y, int half, float alpha) {
        view.project(w.x + 0.5, groundAt(view, w), w.z + 0.5, ORIGIN);
        view.project(w.x + 0.5, limitToCut(Mth.clamp((double) w.y, groundAt(view, w) - MAX_WAYPOINT_OFFSET, groundAt(view, w) + MAX_WAYPOINT_OFFSET)), w.z + 0.5, POINT);
        int length = Math.round(ORIGIN.y - POINT.y);
        if (Math.abs(length) <= half + 1) {
            return;
        }
        int top = Math.min(y, y + length);
        int bottom = Math.max(y, y + length);
        g.fill(x - 1, top, x + 2, bottom + 1, Theme.withAlpha(0x90000000, alpha));
        g.fill(x, top, x + 1, bottom + 1, Theme.withAlpha(0xFF000000 | w.color, alpha));
        // A foot where the pin meets the ground.
        int footY = y + length;
        g.fill(x - 1, footY, x + 2, footY + 1, Theme.withAlpha(0xFF000000 | w.color, alpha));
    }

    private static void drawMarker(GuiGraphics g, int x, int y, int half, Waypoint w, float alpha) {
        int color = Theme.withAlpha(0xFF000000 | w.color, alpha);
        int shadow = Theme.withAlpha(0xD0000000, alpha);
        g.fill(x - half - 1, y - half - 1, x + half + 2, y + half + 2, shadow);
        if (w.death) {
            // A cross built from two rectangles tells death markers apart without relying on colour.
            g.fill(x - half, y, x + half + 1, y + 1, color);
            g.fill(x, y - half, x + 1, y + half + 1, color);
        } else {
            g.fill(x - half, y - half, x + half + 1, y + half + 1, color);
        }
    }

    private static void drawLabel(GuiGraphics g, Font font, Waypoint w, double distance, int x, int y, int minX, int maxX, int minY, float alpha) {
        String text = font.plainSubstrByWidth(w.name, LABEL_MAX_WIDTH);
        if (IsoMapConfig.SHOW_DISTANCE.getAsBoolean()) {
            text += " " + Math.round(distance) + "m";
        }
        drawText(g, font, text, x, y, minX, maxX, minY, Theme.withAlpha(Theme.TEXT, alpha), alpha);
    }

    private static void drawText(GuiGraphics g, Font font, String text, int x, int y, int minX, int maxX, int minY, int color, float alpha) {
        int width = font.width(text);
        int left = Mth.clamp(x - width / 2, minX + 2, Math.max(minX + 2, maxX - width - 2));
        int top = Math.max(y, minY + 2);
        g.fill(left - 2, top - 1, left + width + 2, top + 9, Theme.withAlpha(0xA0000000, alpha));
        g.drawString(font, text, left, top, color, false);
    }

    /** Pings: pulsing diamonds in their sender's colour, unlike any waypoint. Far ones wait at the edge of the map. */
    public static void drawPings(GuiGraphics g, MapView view, int cx, int cy, int halfW, int halfH, float alpha) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        String dimension = Dimensions.current(mc);
        long now = System.currentTimeMillis();
        long lifetime = PingManager.lifetimeMs();
        var pose = g.pose();
        for (Ping ping : PingManager.active()) {
            if (!ping.dimension.equals(dimension)) {
                continue;
            }
            view.project(ping.x, limitToCut(ping.y), ping.z, POINT);
            boolean inside = Math.abs(POINT.x) <= halfW && Math.abs(POINT.y) <= halfH;
            int x = cx + Mth.floor(Mth.clamp(POINT.x, -halfW + PING_EDGE, halfW - PING_EDGE));
            int y = cy + Mth.floor(Mth.clamp(POINT.y, -halfH + PING_EDGE, halfH - PING_EDGE));
            long age = now - ping.createdAt;
            float fade = alpha * Math.max(0f, Math.min(1f, (lifetime - age) / 2500f));
            int color = Theme.withAlpha(0xFF000000 | ping.color, fade);
            float phase = (age % PING_PULSE_MS) / (float) PING_PULSE_MS;

            pose.pushPose();
            pose.translate(x, y, 0f);
            pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(45f));
            int ripple = 3 + Math.round(phase * PING_RIPPLE_SIZE);
            g.renderOutline(-ripple, -ripple, ripple * 2, ripple * 2, Theme.withAlpha(0xFF000000 | ping.color, fade * (1f - phase)));
            g.fill(-5, -5, 5, 5, Theme.withAlpha(0xFF000000, fade));
            g.fill(-4, -4, 4, 4, color);
            g.fill(-2, -2, 2, 2, Theme.withAlpha(0xFFFFFFFF, fade));
            pose.popPose();

            if (inside) {
                double distance = Math.sqrt(player.distanceToSqr(ping.x, ping.y, ping.z));
                drawText(g, mc.font, ping.senderName + " " + Math.round(distance) + "m", x, y - 17, cx - halfW, cx + halfW, cy - halfH, color, fade);
            }
        }
    }

    /** The player: an arrow along the projected view direction. */
    public static void drawPlayer(GuiGraphics g, MapView view, LocalPlayer player, float partialTick, int cx, int cy, float alpha) {
        double x = Mth.lerp(partialTick, player.xOld, player.getX());
        double y = Mth.lerp(partialTick, player.yOld, player.getY());
        double z = Mth.lerp(partialTick, player.zOld, player.getZ());
        projectPlayer(view, x, y, z, ORIGIN);
        double yaw = Math.toRadians(player.getViewYRot(partialTick));
        projectPlayer(view, x - Math.sin(yaw), y, z + Math.cos(yaw), POINT);
        float angle = (float) Math.atan2(POINT.x - ORIGIN.x, -(POINT.y - ORIGIN.y));

        var pose = g.pose();
        pose.pushPose();
        pose.translate(cx + ORIGIN.x, cy + ORIGIN.y, 0f);
        pose.mulPose(com.mojang.math.Axis.ZP.rotation(angle));
        g.flush();
        Matrix4f matrix = pose.last().pose();
        VertexConsumer out = g.bufferSource().getBuffer(RenderType.gui());
        arrow(out, matrix, 1.3f, Theme.withAlpha(0xFF000000, alpha));
        arrow(out, matrix, 1f, Theme.withAlpha(0xFFFFFFFF, alpha));
        g.flush();
        pose.popPose();
    }

    /** A navigation arrow pointing up: two quads meeting along its centre line, with a notch at the back. */
    private static void arrow(VertexConsumer out, Matrix4f matrix, float scale, int color) {
        float tip = -ARROW_TIP * scale;
        float back = ARROW_BACK * scale;
        float side = ARROW_SIDE * scale;
        float notch = ARROW_NOTCH * scale;
        out.addVertex(matrix, 0f, tip, 0f).setColor(color);
        out.addVertex(matrix, -side, back, 0f).setColor(color);
        out.addVertex(matrix, 0f, notch, 0f).setColor(color);
        out.addVertex(matrix, 0f, notch, 0f).setColor(color);
        out.addVertex(matrix, 0f, tip, 0f).setColor(color);
        out.addVertex(matrix, 0f, notch, 0f).setColor(color);
        out.addVertex(matrix, side, back, 0f).setColor(color);
        out.addVertex(matrix, side, back, 0f).setColor(color);
    }

    /**
     * North, east, south and west letters along the map's edge, wherever the camera has turned them.
     *
     * @param edgeDistance how far from the edge they sit: positive is outside the map, negative inside
     */
    public static void drawCompass(GuiGraphics g, Font font, MapView view, int cx, int cy, int halfW, int halfH, float edgeDistance, float alpha) {
        drawLetter(g, font, view, "N", 0, -1, Theme.ACCENT_WARM, cx, cy, halfW, halfH, edgeDistance, alpha);
        drawLetter(g, font, view, "E", 1, 0, Theme.TEXT_DIM, cx, cy, halfW, halfH, edgeDistance, alpha);
        drawLetter(g, font, view, "S", 0, 1, Theme.TEXT_DIM, cx, cy, halfW, halfH, edgeDistance, alpha);
        drawLetter(g, font, view, "W", -1, 0, Theme.TEXT_DIM, cx, cy, halfW, halfH, edgeDistance, alpha);
    }

    private static void drawLetter(GuiGraphics g, Font font, MapView view, String letter, int dx, int dz, int color,
            int cx, int cy, int halfW, int halfH, float edgeDistance, float alpha) {
        view.project(view.focusX(), 0.0, view.focusZ(), ORIGIN);
        view.project(view.focusX() + dx, 0.0, view.focusZ() + dz, POINT);
        float ux = POINT.x - ORIGIN.x;
        float uy = POINT.y - ORIGIN.y;
        double length = Math.hypot(ux, uy);
        if (length < 1.0e-4) {
            return;
        }
        // Scale the direction until it touches the rectangle: a square map has no circular rim to follow.
        double scale = Math.min((halfW + edgeDistance) / Math.max(Math.abs(ux), 1.0e-4f), (halfH + edgeDistance) / Math.max(Math.abs(uy), 1.0e-4f));
        int x = cx + Math.round((float) (ux * scale));
        int y = cy + Math.round((float) (uy * scale));
        g.fill(x - 5, y - 5, x + 5, y + 5, Theme.withAlpha(0x90000000, alpha));
        g.drawString(font, letter, x - font.width(letter) / 2, y - 4, Theme.withAlpha(color, alpha), false);
    }
}
