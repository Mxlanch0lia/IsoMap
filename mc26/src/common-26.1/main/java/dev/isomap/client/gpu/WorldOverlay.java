package dev.isomap.client.gpu;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Drawing over the finished world, seen through walls: the ping holograms. */
public final class WorldOverlay {
    private WorldOverlay() {}

    /**
     * Draws coloured quads right now, with the camera matrix the level was drawn with; positions are relative to the camera.
     */
    public static void quads(Matrix4f modelView, Consumer<VertexConsumer> build) {
        BufferBuilder out = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        build.accept(out);
        try (MeshData mesh = out.build()) {
            if (mesh == null) {
                return;
            }
            GpuBufferSlice transform = RenderSystem.getDynamicUniforms()
                    .writeTransform(modelView, new Vector4f(1f, 1f, 1f, 1f), new Vector3f(), new Matrix4f());
            GpuBuffer vertices = DefaultVertexFormat.POSITION_COLOR.uploadImmediateVertexBuffer(mesh.vertexBuffer());
            RenderSystem.AutoStorageIndexBuffer quads = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
            GpuBuffer indices = quads.getBuffer(mesh.drawState().indexCount());
            RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "IsoMap pings",
                    target.getColorTextureView(), OptionalInt.empty(), target.useDepth ? target.getDepthTextureView() : null, OptionalDouble.empty())) {
                pass.setPipeline(MapPipelines.SEE_THROUGH);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", transform);
                pass.setVertexBuffer(0, vertices);
                pass.setIndexBuffer(indices, quads.type());
                pass.drawIndexed(0, 0, mesh.drawState().indexCount(), 1);
            }
        }
    }
}
