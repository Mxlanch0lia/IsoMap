package dev.isomap.client.gpu;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import javax.annotation.Nullable;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.util.ARGB;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * The map's own colour and depth image. Tiles are queued with {@link #add} and drawn in one pass by {@link #render};
 * {@link #blit} then puts the finished picture into the GUI. Depth stays inside this image, so the map cannot disturb the
 * GUI, and transparency is applied to the finished picture rather than to overlapping terrain faces.
 */
public final class MapCanvas implements AutoCloseable {
    private static final Vector4f WHITE = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f NO_OFFSET = new Vector3f();
    private static final Matrix4f NO_TEXTURE_TRANSFORM = new Matrix4f();

    @Nullable
    private GpuTexture color;
    @Nullable
    private GpuTextureView colorView;
    @Nullable
    private GpuTexture depth;
    @Nullable
    private GpuTextureView depthView;
    private int width;
    private int height;
    private boolean linear;
    private final ProjectionMatrixBuffer projection = new ProjectionMatrixBuffer("IsoMap map");
    private final List<TileMesh> meshes = new ArrayList<>();
    private final List<Matrix4f> matrices = new ArrayList<>();
    /**
     * Textures dropped this frame. The GUI is drawn after it is collected, so a picture queued earlier in the frame may still
     * read them; they are freed when the next frame starts.
     */
    private static final List<AutoCloseable> RETIRED = new ArrayList<>();

    /** Frees what earlier frames let go of. Call at the start of every frame. */
    public static void collectGarbage() {
        for (AutoCloseable resource : RETIRED) {
            try {
                resource.close();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        RETIRED.clear();
    }

    /** An orthographic projection centred on the image, reaching {@code depth} towards and away from the viewer. */
    public static void ortho(Matrix4f dest, float halfWidth, float halfHeight, float depth) {
        // Some GPU backends map depth to 0..1, others to -1..1.
        dest.setOrtho(-halfWidth, halfWidth, -halfHeight, halfHeight, -depth, depth, RenderSystem.getDevice().isZZeroToOne());
    }

    /** Makes sure the image has this size in pixels. Returns true if it had to be recreated. */
    public boolean ensureSize(int width, int height, boolean linear) {
        this.linear = linear;
        if (color != null && width == this.width && height == this.height) {
            return false;
        }
        releaseTextures();
        GpuDevice device = RenderSystem.getDevice();
        color = device.createTexture(() -> "IsoMap map colour",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                TextureFormat.RGBA8, width, height, 1, 1);
        colorView = device.createTextureView(color);
        depth = device.createTexture(() -> "IsoMap map depth", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST,
                TextureFormat.DEPTH32, width, height, 1, 1);
        depthView = device.createTextureView(depth);
        this.width = width;
        this.height = height;
        return true;
    }

    /** Queues a tile for the next {@link #render}, with the matrix that places it. */
    public void add(TileMesh mesh, Matrix4f modelView) {
        if (meshes.size() == matrices.size()) {
            matrices.add(new Matrix4f());
        }
        matrices.get(meshes.size()).set(modelView);
        meshes.add(mesh);
    }

    /** Clears the image and draws everything queued since the last call. */
    public void render(Matrix4f projectionMatrix) {
        try {
            if (color == null || colorView == null || depth == null || depthView == null) {
                return;
            }
            GpuDevice device = RenderSystem.getDevice();
            device.createCommandEncoder().clearColorAndDepthTextures(color, 0, depth, 1.0);
            if (meshes.isEmpty()) {
                return;
            }
            // Uniforms are written before the pass opens: no buffer may be written while one is open.
            DynamicUniforms.Transform[] transforms = new DynamicUniforms.Transform[meshes.size()];
            int maxIndices = 0;
            for (int i = 0; i < transforms.length; i++) {
                transforms[i] = new DynamicUniforms.Transform(matrices.get(i), WHITE, NO_OFFSET, NO_TEXTURE_TRANSFORM);
                maxIndices = Math.max(maxIndices, meshes.get(i).indexCount);
            }
            GpuBufferSlice[] slices = RenderSystem.getDynamicUniforms().writeTransforms(transforms);
            RenderSystem.AutoStorageIndexBuffer quads = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
            GpuBuffer indices = quads.getBuffer(maxIndices);

            GpuBufferSlice savedProjection = RenderSystem.getProjectionMatrixBuffer();
            ProjectionType savedType = RenderSystem.getProjectionType();
            RenderSystem.setProjectionMatrix(projection.getBuffer(projectionMatrix), ProjectionType.ORTHOGRAPHIC);
            try (RenderPass pass = device.createCommandEncoder()
                    .createRenderPass(() -> "IsoMap map", colorView, OptionalInt.empty(), depthView, OptionalDouble.empty())) {
                pass.setPipeline(MapPipelines.TERRAIN);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setIndexBuffer(indices, quads.type());
                for (int i = 0; i < slices.length; i++) {
                    TileMesh mesh = meshes.get(i);
                    pass.setUniform("DynamicTransforms", slices[i]);
                    pass.setVertexBuffer(0, mesh.vertices);
                    pass.drawIndexed(0, 0, mesh.indexCount, 1);
                }
            } finally {
                if (savedProjection != null) {
                    RenderSystem.setProjectionMatrix(savedProjection, savedType);
                }
            }
        } finally {
            meshes.clear();
        }
    }

    /** Puts the image into the GUI at a fractional position, faded by {@code alpha}. */
    public void blit(GuiGraphicsExtractor g, float x, float y, float w, float h, float alpha) {
        if (colorView == null) {
            return;
        }
        Matrix3x2f pose = new Matrix3x2f(g.pose()).translate(x, y).scale(w, h);
        // The image is premultiplied (clear where nothing was drawn), so the fade scales all four channels.
        int tint = ARGB.colorFromFloat(alpha, alpha, alpha, alpha);
        TextureSetup texture = TextureSetup.singleTexture(colorView,
                RenderSystem.getSamplerCache().getClampToEdge(linear ? FilterMode.LINEAR : FilterMode.NEAREST));
        // Rows run bottom-up on the GPU, hence v from 1 to 0.
        GuiAccess.state().addGuiElement(new BlitRenderState(
                RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA, texture, pose, 0, 0, 1, 1, 0f, 1f, 1f, 0f, tint, null));
    }

    private void releaseTextures() {
        if (colorView != null) {
            RETIRED.add(colorView);
        }
        if (color != null) {
            RETIRED.add(color);
        }
        if (depthView != null) {
            RETIRED.add(depthView);
        }
        if (depth != null) {
            RETIRED.add(depth);
        }
        color = null;
        colorView = null;
        depth = null;
        depthView = null;
        width = 0;
        height = 0;
    }

    @Override
    public void close() {
        releaseTextures();
        meshes.clear();
    }
}
