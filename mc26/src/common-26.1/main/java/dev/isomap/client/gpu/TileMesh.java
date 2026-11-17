package dev.isomap.client.gpu;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;

/** One map tile on the GPU: a static vertex buffer of coloured quads. Indices come from Minecraft's shared quad index buffer. */
public final class TileMesh implements AutoCloseable {
    final GpuBuffer vertices;
    final int indexCount;
    private final int vertexCount;

    private TileMesh(GpuBuffer vertices, int vertexCount, int indexCount) {
        this.vertices = vertices;
        this.vertexCount = vertexCount;
        this.indexCount = indexCount;
    }

    /** A builder for coloured quads in the format the map draws. Any thread. */
    public static BufferBuilder builder(ByteBufferBuilder memory) {
        return new BufferBuilder(memory, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
    }

    /** Copies a finished mesh to the GPU. Render thread only; the mesh stays with the caller. */
    public static TileMesh upload(MeshData mesh) {
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "IsoMap tile", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
        return new TileMesh(buffer, mesh.drawState().vertexCount(), mesh.drawState().indexCount());
    }

    public int vertexCount() {
        return vertexCount;
    }

    @Override
    public void close() {
        vertices.close();
    }
}
