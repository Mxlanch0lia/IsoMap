package dev.isomap.client.ping;

import dev.isomap.client.Compat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import dev.isomap.client.gpu.WorldOverlay;
import dev.isomap.client.waypoint.Dimensions;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Pings as holograms in the world: a beam of light, a floating diamond and a ripple spreading over the ground, all visible
 * through walls. They keep roughly the same apparent size at any distance, so a far ping can still be found at a glance.
 */
public final class PingWorldRenderer {
    private static final float FADE_MS = 2500f;
    private static final float APPEAR_MS = 260f;
    private static final double MAX_DRAW_DISTANCE = 96.0;
    private static final float SIZE_PER_BLOCK = 0.028f;
    private static final float MIN_SIZE = 0.45f;
    private static final float RIPPLE_PERIOD_MS = 1400f;
    /** Up close the beam and the ripple thin out, so a ping at your feet does not cover what you are looking at. */
    private static final float NEAR_FADE_BLOCKS = 12f;
    private static final float MIN_NEAR_FADE = 0.2f;
    /** Labels found while drawing the holograms, in GUI units; the HUD draws them, since text cannot go into the world here. */
    private static final List<Label> LABELS = new ArrayList<>();

    private record Label(String text, int color, int background, float x, float y) {}

    private PingWorldRenderer() {}

    /** Called once per frame after the level is drawn, with the matrix it was drawn with. */
    public static void render(Camera camera, Matrix4f modelView) {
        Minecraft mc = Minecraft.getInstance();
        LABELS.clear();
        if (Compat.hudHidden(mc) || mc.player == null || PingManager.active().isEmpty()) {
            return;
        }
        Vec3 eye = camera.position();
        String dimension = Dimensions.current(mc);
        long now = System.currentTimeMillis();
        long lifetime = PingManager.lifetimeMs();
        boolean any = PingManager.active().stream().anyMatch(ping -> ping.dimension.equals(dimension));
        if (!any) {
            return;
        }
        WorldOverlay.quads(modelView, out -> {
            for (Ping ping : PingManager.active()) {
                if (ping.dimension.equals(dimension)) {
                    drawHologram(out, ping, camera, eye, now, lifetime);
                }
            }
        });
        Matrix4f toScreen = new Matrix4f(Compat.cameraProjection(mc)).mul(modelView);
        for (Ping ping : PingManager.active()) {
            if (ping.dimension.equals(dimension)) {
                placeLabel(mc, toScreen, ping, eye, now, lifetime);
            }
        }
    }

    private static float alphaOf(Ping ping, long now, long lifetime) {
        float age = now - ping.createdAt;
        return Math.max(0f, Math.min(1f, (lifetime - age) / FADE_MS));
    }

    /** Far pings are drawn nearer along the same line of sight: the look is the same, and they stay inside the far plane. */
    private static Vec3 anchor(Ping ping, Vec3 eye) {
        Vec3 offset = new Vec3(ping.x - eye.x, ping.y - eye.y, ping.z - eye.z);
        double distance = offset.length();
        return distance > MAX_DRAW_DISTANCE ? offset.scale(MAX_DRAW_DISTANCE / distance) : offset;
    }

    private static float sizeAt(Vec3 anchor) {
        return Math.max(MIN_SIZE, (float) anchor.length() * SIZE_PER_BLOCK);
    }

    private static void drawHologram(VertexConsumer out, Ping ping, Camera camera, Vec3 eye, long now, long lifetime) {
        float alpha = alphaOf(ping, now, lifetime);
        if (alpha <= 0f) {
            return;
        }
        Vec3 anchor = anchor(ping, eye);
        float size = sizeAt(anchor);
        float age = now - ping.createdAt;
        float appear = Math.min(1f, age / APPEAR_MS);
        float pop = 1f + (1f - appear) * 1.2f;
        Vector3f left = new Vector3f(camera.leftVector());
        Vector3f up = new Vector3f(camera.upVector());
        float ax = (float) anchor.x;
        float ay = (float) anchor.y;
        float az = (float) anchor.z;

        // Beam: a soft column that dies out towards the top.
        float beamHeight = size * 11f;
        float beamWidth = size * 0.09f;
        float nearFade = Math.max(MIN_NEAR_FADE, Math.min(1f, (float) anchor.length() / NEAR_FADE_BLOCKS));
        int beamBottom = argb(ping.color, 0.62f * alpha * nearFade);
        int beamTop = argb(ping.color, 0f);
        out.addVertex(ax + left.x * beamWidth, ay, az + left.z * beamWidth).setColor(beamBottom);
        out.addVertex(ax - left.x * beamWidth, ay, az - left.z * beamWidth).setColor(beamBottom);
        out.addVertex(ax - left.x * beamWidth, ay + beamHeight, az - left.z * beamWidth).setColor(beamTop);
        out.addVertex(ax + left.x * beamWidth, ay + beamHeight, az + left.z * beamWidth).setColor(beamTop);

        // Ripple on the ground: a flat diamond ring spreading out and fading, again and again.
        float phase = (age % RIPPLE_PERIOD_MS) / RIPPLE_PERIOD_MS;
        float rippleRadius = size * (0.5f + phase * 2.6f);
        flatRing(out, ax, ay + 0.03f, az, rippleRadius, size * 0.1f, argb(ping.color, (1f - phase) * 0.85f * alpha * nearFade));

        // The diamond floats above the target and bobs gently, always facing the viewer.
        float bob = (float) Math.sin(age / 420.0) * size * 0.12f;
        float cy = ay + size * 2.6f + bob;
        float outer = size * 0.95f * pop;
        float thickness = size * 0.2f;
        faceRing(out, ax, cy, az, left, up, outer, thickness, argb(0x000000, 0.45f * alpha), size * 0.09f);
        faceRing(out, ax, cy, az, left, up, outer, thickness, argb(ping.color, alpha), 0f);
        float inner = size * 0.36f * pop * (1f + 0.14f * (float) Math.sin(age / 180.0));
        faceDiamond(out, ax, cy, az, left, up, inner, argb(0xFFFFFF, 0.95f * alpha));
    }

    /** A diamond frame facing the camera, built of its four sides. {@code grow} widens it for a dark outline. */
    private static void faceRing(VertexConsumer out, float x, float y, float z, Vector3f left, Vector3f up, float radius, float thickness, int color, float grow) {
        float rOut = radius + grow;
        float rIn = radius - thickness - grow;
        float[][] corners = {{0f, 1f}, {1f, 0f}, {0f, -1f}, {-1f, 0f}};
        for (int i = 0; i < 4; i++) {
            float[] a = corners[i];
            float[] b = corners[(i + 1) % 4];
            facePoint(out, x, y, z, left, up, a[0] * rOut, a[1] * rOut, color);
            facePoint(out, x, y, z, left, up, b[0] * rOut, b[1] * rOut, color);
            facePoint(out, x, y, z, left, up, b[0] * rIn, b[1] * rIn, color);
            facePoint(out, x, y, z, left, up, a[0] * rIn, a[1] * rIn, color);
        }
    }

    private static void faceDiamond(VertexConsumer out, float x, float y, float z, Vector3f left, Vector3f up, float radius, int color) {
        facePoint(out, x, y, z, left, up, 0f, radius, color);
        facePoint(out, x, y, z, left, up, radius, 0f, color);
        facePoint(out, x, y, z, left, up, 0f, -radius, color);
        facePoint(out, x, y, z, left, up, -radius, 0f, color);
    }

    private static void facePoint(VertexConsumer out, float x, float y, float z, Vector3f left, Vector3f up, float dx, float dy, int color) {
        out.addVertex(x + left.x * dx + up.x * dy, y + left.y * dx + up.y * dy, z + left.z * dx + up.z * dy).setColor(color);
    }

    private static void flatRing(VertexConsumer out, float x, float y, float z, float radius, float thickness, int color) {
        float rIn = Math.max(0f, radius - thickness);
        float[][] corners = {{0f, 1f}, {1f, 0f}, {0f, -1f}, {-1f, 0f}};
        for (int i = 0; i < 4; i++) {
            float[] a = corners[i];
            float[] b = corners[(i + 1) % 4];
            out.addVertex(x + a[0] * radius, y, z + a[1] * radius).setColor(color);
            out.addVertex(x + b[0] * radius, y, z + b[1] * radius).setColor(color);
            out.addVertex(x + b[0] * rIn, y, z + b[1] * rIn).setColor(color);
            out.addVertex(x + a[0] * rIn, y, z + a[1] * rIn).setColor(color);
        }
    }

    /** Projects the point above the diamond onto the screen and keeps the label for {@link #drawLabels}. */
    private static void placeLabel(Minecraft mc, Matrix4f toScreen, Ping ping, Vec3 eye, long now, long lifetime) {
        float alpha = alphaOf(ping, now, lifetime);
        if (alpha <= 0.05f) {
            return;
        }
        Vec3 anchor = anchor(ping, eye);
        float size = sizeAt(anchor);
        Vector4f clip = toScreen.transform(new Vector4f((float) anchor.x, (float) anchor.y + size * 4.9f, (float) anchor.z, 1f));
        if (clip.w <= 0.05f) {
            return;
        }
        float x = (clip.x / clip.w * 0.5f + 0.5f) * mc.getWindow().getGuiScaledWidth();
        float y = (0.5f - clip.y / clip.w * 0.5f) * mc.getWindow().getGuiScaledHeight();
        long distance = Math.round(Math.sqrt(eye.distanceToSqr(ping.x, ping.y, ping.z)));
        LABELS.add(new Label(ping.senderName + "  " + distance + "m", argb(ping.color, alpha), argb(0x000000, 0.5f * alpha), x, y));
    }

    /** The labels of the holograms drawn this frame, over everything in the world. */
    public static void drawLabels(GuiGraphicsExtractor g) {
        Font font = Minecraft.getInstance().font;
        for (Label label : LABELS) {
            int width = font.width(label.text());
            int left = Math.round(label.x() - width / 2f);
            int top = Math.round(label.y()) - 9;
            g.fill(left - 2, top - 1, left + width + 2, top + 9, label.background());
            g.text(font, label.text(), left, top, label.color(), false);
        }
    }

    private static int argb(int rgb, float alpha) {
        return ((int) (Math.max(0f, Math.min(1f, alpha)) * 255f) << 24) | (rgb & 0xFFFFFF);
    }
}
