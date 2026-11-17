package dev.isomap.client.gpu;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;

/** Drawing over the finished world, seen through walls: the ping holograms. */
public final class WorldOverlay {
    private static final int INITIAL_BYTES = 16 * 1024;

    private WorldOverlay() {}

    /**
     * Draws coloured quads right now, with the camera matrix the level was drawn with; positions are relative to the camera.
     */
    public static void quads(Matrix4f modelView, Consumer<VertexConsumer> build) {
        try (ByteBufferBuilder memory = new ByteBufferBuilder(INITIAL_BYTES)) {
            BufferBuilder out = TileMesh.builder(memory);
            build.accept(out);
            try (MeshData mesh = out.build()) {
                if (mesh == null) {
                    return;
                }
                RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
                if (target.getColorTextureView() == null) {
                    return;
                }
                GpuBufferSlice transform = RenderSystem.getDynamicUniforms().writeTransform(modelView);
                // A buffer for this frame only; freed with the textures the next frame lets go of.
                GpuBuffer vertices = RenderSystem.getDevice().createBuffer(() -> "IsoMap pings", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
                MapCanvas.retire(vertices);
                RenderSystem.AutoStorageIndexBuffer quads = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
                GpuBuffer indices = quads.getBuffer(mesh.drawState().indexCount());
                try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "IsoMap pings",
                        target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
                    pass.setPipeline(RenderSystem.getCompiledPipeline(MapPipelines.SEE_THROUGH));
                    RenderSystem.bindDefaultUniforms(pass);
                    pass.setUniform("DynamicTransforms", transform);
                    pass.setVertexBuffer(0, vertices.slice());
                    pass.setIndexBuffer(indices, quads.type());
                    pass.drawIndexed(mesh.drawState().indexCount(), 1, 0, 0, 0);
                }
            }
        }
    }
}
