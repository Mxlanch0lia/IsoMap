package dev.isomap.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.isomap.client.map.ChunkSnapshot;
import dev.isomap.client.map.TerrainCache;
import dev.isomap.config.IsoMapConfig;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;

/**
 * One map image: an off-screen colour and depth target that is re-rendered at the configured rate and shown with a single
 * textured quad every frame. Depth stays inside the target, so the map cannot disturb the GUI, and transparency is applied
 * to the finished picture rather than to overlapping terrain faces.
 */
public final class MapView implements AutoCloseable {
    private static final double TELEPORT_SNAP_BLOCKS = 48.0;
    private static final float DEPTH_RANGE_BLOCKS = 1024f;
    public static final double MIN_ZOOM = 0.25;
    public static final double MAX_ZOOM = 8.0;
    private static final double ZOOM_EASE_RATE = 16.0;
    private static final int MOVING_FPS = 144;
    private static final double SETTLED_ZOOM = 0.001;
    private static final double SETTLED_BLOCKS = 0.02;
    /** Below this tilt the near wall of a cave hides its floor. */
    private static final double CAVE_MIN_PITCH = 62.0;
    private static final double FLAT_PITCH = 90.0;
    private static final double TILT_RATE = 8.0;
    private static final double TILT_SNAP_DEGREES = 0.05;
    private static final double GROUND_DEAD_BAND = 2.0;
    private static final double GROUND_FOLLOW_RATE = 2.5;
    /** Blocks per second, so that walking past a cliff is no jolt either. */
    private static final double GROUND_MAX_SPEED = 5.0;

    @Nullable
    private RenderTarget target;
    private int targetWidth;
    private int targetHeight;
    private int pixelScale = 1;

    private double zoomTarget;
    private double zoom;
    private double panX;
    private double panZ;
    private double focusX;
    private double focusY;
    private double focusZ;
    private boolean focusReady;
    private double guiScale = 1.0;
    private long lastRenderNanos;
    private long lastUpdateNanos;
    private boolean forceRender;
    private double lastTargetY;
    private double minimumTilt;
    private double drawnPitch;
    private long renderCount;
    private float snapX;
    private float snapY;
    private boolean pixelSnapped;

    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f camera = new Matrix4f();
    private final Matrix4f tileMatrix = new Matrix4f();
    private final Vector4f projected = new Vector4f();

    public MapView(double zoom) {
        this.zoom = zoom;
        this.zoomTarget = zoom;
    }

    public double zoomTarget() {
        return zoomTarget;
    }

    public void setZoomTarget(double value) {
        zoomTarget = Mth.clamp(value, MIN_ZOOM, MAX_ZOOM);
    }

    public void multiplyZoom(double factor) {
        setZoomTarget(zoomTarget * factor);
    }

    public void pan(double dx, double dz) {
        panX += dx;
        panZ += dz;
    }

    public void resetPan() {
        panX = 0.0;
        panZ = 0.0;
    }

    public double focusX() {
        return focusX;
    }

    public double focusZ() {
        return focusZ;
    }

    public double focusY() {
        return focusY;
    }

    /** The tilt actually drawn; in caves it can be steeper than the camera setting. */
    public double pitchDegrees() {
        return drawnPitch;
    }

    /** How many images have been rendered so far; lets the QA harness measure the real update rate. */
    public long renderCount() {
        return renderCount;
    }

    public void requestRender() {
        forceRender = true;
    }

    /** Blocks per GUI unit at the current zoom, for converting a mouse drag into a pan. */
    public double blocksPerGuiUnit() {
        return 1.0 / Math.max(zoom, 0.001);
    }

    /**
     * Advances the camera and re-renders if the update interval has passed.
     *
     * @param width  size of the image on screen in GUI units
     * @param height size of the image on screen in GUI units
     */
    public void update(TerrainCache terrain, LocalPlayer player, float partialTick, int width, int height, boolean drivesAngles) {
        Minecraft mc = Minecraft.getInstance();
        guiScale = mc.getWindow().getGuiScale();
        int supersample = IsoMapConfig.SUPERSAMPLE.getAsBoolean() ? 2 : 1;
        ensureTarget(Math.max(1, (int) Math.round(width * guiScale)) * supersample,
                Math.max(1, (int) Math.round(height * guiScale)) * supersample, supersample);

        long now = System.nanoTime();
        double dt = lastUpdateNanos == 0L ? 0.05 : Math.min(0.25, (now - lastUpdateNanos) / 1.0e9);
        double targetX = Mth.lerp(partialTick, player.xOld, player.getX()) + panX;
        double targetZ = Mth.lerp(partialTick, player.zOld, player.getZ()) + panZ;
        // A flat map is simply the view from straight above; in caves a low camera would hide the floors behind the near wall.
        double wantedTilt = !IsoMapConfig.RENDER_3D.getAsBoolean() ? FLAT_PITCH : terrain.isSlicing() ? CAVE_MIN_PITCH : 0.0;
        boolean moving = CameraAngles.isMoving()
                || Math.abs(wantedTilt - minimumTilt) > 0.0
                || Math.abs(zoomTarget - zoom) > zoom * SETTLED_ZOOM
                || Math.abs(targetX - focusX) > SETTLED_BLOCKS
                || Math.abs(targetZ - focusZ) > SETTLED_BLOCKS
                || Math.abs(lastTargetY - focusY) > SETTLED_BLOCKS;
        // The update rate is for a map at rest. A camera in motion gets every frame: an image costs a fraction of a
        // millisecond, and turning at a low rate is exactly what looks broken.
        long interval = 1_000_000_000L / (moving ? MOVING_FPS : IsoMapConfig.UPDATE_FPS.getAsInt());
        if (!forceRender && now - lastRenderNanos < interval) {
            return;
        }
        renderCount++;
        forceRender = false;
        lastUpdateNanos = now;
        lastRenderNanos = now;

        double followAlpha = CameraAngles.followAlpha(dt);
        if (drivesAngles) {
            CameraAngles.update(dt);
        }
        zoom += (zoomTarget - zoom) * (1.0 - Math.exp(-dt * ZOOM_EASE_RATE));

        // The camera turns around the terrain under the player, not around the player's own height: otherwise the ground
        // shifts away from the marker in proportion to how far the player is above or below it, and orbits it when turning.
        double playerY = Mth.lerp(partialTick, player.yOld, player.getY());
        double groundY = terrain.groundLevel(Mth.floor(targetX), Mth.floor(targetZ), Mth.floor(playerY)) + 1.0;
        // The level moves in whole blocks and flickers between neighbours on terraces. Being one block off is invisible;
        // following that flicker shakes the whole tilted picture.
        if (!focusReady || Math.abs(groundY - lastTargetY) >= GROUND_DEAD_BAND) {
            lastTargetY = groundY;
        }
        double targetY = lastTargetY;
        // A new map starts at its proper tilt; only a change while it is on screen animates.
        minimumTilt = focusReady ? minimumTilt + (wantedTilt - minimumTilt) * (1.0 - Math.exp(-dt * TILT_RATE)) : wantedTilt;
        if (Math.abs(wantedTilt - minimumTilt) < TILT_SNAP_DEGREES) {
            minimumTilt = wantedTilt;
        }
        drawnPitch = Math.max(CameraAngles.pitchDegrees(), minimumTilt);
        boolean teleported = Math.abs(targetX - focusX) > TELEPORT_SNAP_BLOCKS
                || Math.abs(targetY - focusY) > TELEPORT_SNAP_BLOCKS
                || Math.abs(targetZ - focusZ) > TELEPORT_SNAP_BLOCKS;
        if (!focusReady || teleported) {
            focusY = targetY;
        } else {
            // Slow whatever the follow setting says: the ground level is a statistic, not something the eye should track.
            double glide = (targetY - focusY) * (1.0 - Math.exp(-dt * GROUND_FOLLOW_RATE));
            focusY += Mth.clamp(glide, -dt * GROUND_MAX_SPEED, dt * GROUND_MAX_SPEED);
        }
        if (!focusReady || teleported || panX != 0.0 || panZ != 0.0) {
            focusX = targetX;
            focusZ = targetZ;
            focusReady = true;
        } else {
            focusX += (targetX - focusX) * followAlpha;
            focusZ += (targetZ - focusZ) * followAlpha;
        }

        buildMatrices();
        // Without antialiasing, sliding the terrain by a fraction of a pixel makes every block edge crawl. While only the
        // position changes, whole-pixel steps keep the raster identical. Turning or zooming changes it anyway, and there the
        // steps themselves would show.
        boolean cameraStill = !CameraAngles.isMoving() && wantedTilt == minimumTilt && Math.abs(zoomTarget - zoom) <= zoom * SETTLED_ZOOM;
        snapX = 0f;
        snapY = 0f;
        pixelSnapped = cameraStill;
        if (cameraStill) {
            double viewX = camera.m00() * focusX + camera.m10() * focusY + camera.m20() * focusZ;
            double viewY = camera.m01() * focusX + camera.m11() * focusY + camera.m21() * focusZ;
            snapX = (float) (viewX - Math.rint(viewX));
            snapY = (float) (viewY - Math.rint(viewY));
        }
        draw(terrain, mc);
    }

    private void ensureTarget(int width, int height, int supersample) {
        pixelScale = supersample;
        if (target != null && width == targetWidth && height == targetHeight) {
            return;
        }
        close();
        target = new TextureTarget(width, height, true, Minecraft.ON_OSX);
        target.setClearColor(0f, 0f, 0f, 0f);
        target.setFilterMode(supersample > 1 ? GL11.GL_LINEAR : GL11.GL_NEAREST);
        targetWidth = width;
        targetHeight = height;
        forceRender = true;
        Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
    }

    private void buildMatrices() {
        float halfWidth = targetWidth / 2f;
        float halfHeight = targetHeight / 2f;
        float scale = (float) (zoom * guiScale * pixelScale);
        float depth = DEPTH_RANGE_BLOCKS * Math.max(scale, 1f);
        projection.setOrtho(-halfWidth, halfWidth, -halfHeight, halfHeight, -depth, depth);

        // Vertical exaggeration lives in the matrix, so changing it needs no re-meshing.
        float heightScale = IsoMapConfig.HEIGHT_SCALE.get().floatValue();
        camera.identity()
                .scale(scale)
                .rotateX((float) Math.toRadians(drawnPitch))
                .rotateY((float) Math.toRadians(CameraAngles.yawDegrees()))
                .scale(1f, heightScale, 1f);
    }

    private void draw(TerrainCache terrain, Minecraft mc) {
        RenderTarget renderTarget = target;
        if (renderTarget == null) {
            return;
        }
        // A clear only reaches the depth buffer while depth writes are on.
        RenderSystem.depthMask(true);
        renderTarget.clear(Minecraft.ON_OSX);
        renderTarget.bindWrite(true);

        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        ShaderInstance shader = RenderSystem.getShader();

        if (shader != null) {
            float viewRadius = (float) (Math.hypot(targetWidth, targetHeight) / 2.0 / (zoom * guiScale * pixelScale));
            // One program setup per image; only the matrix changes from tile to tile.
            shader.setDefaultUniforms(VertexFormat.Mode.QUADS, camera, projection, mc.getWindow());
            shader.apply();
            terrain.forEachBuffer((buffer, originX, originZ, size) -> {
                float half = size / 2f;
                float centerX = (float) (originX + half - focusX);
                float centerZ = (float) (originZ + half - focusZ);
                // Coarse culling on the ground plane; the tile's own diagonal keeps corners safe.
                float reach = viewRadius + half * 1.5f + 64f;
                if (centerX * centerX + centerZ * centerZ > reach * reach) {
                    return;
                }
                tileMatrix.set(camera).translate((float) (originX - focusX), (float) -focusY, (float) (originZ - focusZ));
                tileMatrix.m30(tileMatrix.m30() + snapX).m31(tileMatrix.m31() + snapY);
                if (shader.MODEL_VIEW_MATRIX != null) {
                    shader.MODEL_VIEW_MATRIX.set(tileMatrix);
                    shader.MODEL_VIEW_MATRIX.upload();
                }
                buffer.bind();
                buffer.draw();
            });
            shader.clear();
        }
        VertexBuffer.unbind();

        RenderSystem.disableDepthTest();
        mc.getMainRenderTarget().bindWrite(true);
    }

    /** Draws the finished image as a quad in GUI space. */
    public void blit(GuiGraphics graphics, float x, float y, float width, float height, float alpha) {
        if (target == null) {
            return;
        }
        graphics.flush();
        RenderSystem.setShaderTexture(0, target.getColorTextureId());
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        Matrix4f pose = graphics.pose().last().pose();
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        builder.addVertex(pose, x, y, 0f).setUv(0f, 1f).setColor(1f, 1f, 1f, alpha);
        builder.addVertex(pose, x, y + height, 0f).setUv(0f, 0f).setColor(1f, 1f, 1f, alpha);
        builder.addVertex(pose, x + width, y + height, 0f).setUv(1f, 0f).setColor(1f, 1f, 1f, alpha);
        builder.addVertex(pose, x + width, y, 0f).setUv(1f, 1f).setColor(1f, 1f, 1f, alpha);
        BufferUploader.drawWithShader(builder.buildOrThrow());
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
    }

    /**
     * Projects a world position into GUI units relative to the image centre, using the matrices of the last rendered
     * image, so markers stay glued to the terrain even when the image updates less often than the screen.
     *
     * @param out receives x (right) and y (down); z holds depth towards the viewer
     */
    public void project(double worldX, double worldY, double worldZ, Vector3f out) {
        projectFree(worldX, worldY, worldZ, out);
        float toGui = (float) (1.0 / (guiScale * pixelScale));
        out.x += snapX * toGui;
        out.y -= snapY * toGui;
    }

    /** As {@link #project}, without the whole-pixel steps of the terrain: for what moves smoothly over it, like the player. */
    public void projectFree(double worldX, double worldY, double worldZ, Vector3f out) {
        projected.set((float) (worldX - focusX), (float) (worldY - focusY), (float) (worldZ - focusZ), 1f);
        camera.transform(projected);
        float toGui = (float) (1.0 / (guiScale * pixelScale));
        out.set(projected.x * toGui, -projected.y * toGui, projected.z * toGui);
    }

    /** Whether the last image was moved in whole pixels; it is not while the camera turns, tilts or zooms. */
    public boolean pixelSnapped() {
        return pixelSnapped;
    }

    /** Image pixels per GUI unit. */
    public double pixelsPerGuiUnit() {
        return guiScale * pixelScale;
    }

    /**
     * Finds the terrain column under a point of the image by marching along the view ray.
     *
     * @return the block position hit, or null if the ray meets no known terrain
     */
    @Nullable
    public BlockPos pick(TerrainCache terrain, double guiX, double guiY, int minY, int maxY) {
        float toPixels = (float) (guiScale * pixelScale);
        Matrix4f inverse = new Matrix4f(camera).invert();
        Vector3f origin = inverse.transformPosition(new Vector3f((float) guiX * toPixels, (float) -guiY * toPixels, 0f));
        Vector3f direction = inverse.transformDirection(new Vector3f(0f, 0f, -1f));
        if (direction.y > -1.0e-4f) {
            return null;
        }
        float length = Math.max(Math.max(Math.abs(direction.x), Math.abs(direction.z)), Math.abs(direction.y));
        direction.div(length);

        // Start where the ray enters the top of the world and stop at the bottom.
        double startT = (maxY - (focusY + origin.y)) / direction.y;
        double x = focusX + origin.x + direction.x * startT;
        double y = focusY + origin.y + direction.y * startT;
        double z = focusZ + origin.z + direction.z * startT;
        int steps = (int) Math.ceil((maxY - minY) / Math.abs(direction.y)) + 2;
        for (int i = 0; i < steps; i++) {
            int blockX = Mth.floor(x);
            int blockZ = Mth.floor(z);
            int height = terrain.heightAt(blockX, blockZ);
            if (height != ChunkSnapshot.NO_DATA && y <= height + 1) {
                return new BlockPos(blockX, height, blockZ);
            }
            x += direction.x;
            y += direction.y;
            z += direction.z;
        }
        return null;
    }

    @Override
    public void close() {
        if (target != null) {
            target.destroyBuffers();
            target = null;
        }
        targetWidth = 0;
        targetHeight = 0;
    }
}
