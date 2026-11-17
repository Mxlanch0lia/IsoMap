package dev.isomap.client.gpu;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.isomap.IsoMap;
import java.util.Optional;

/** The two ways the mod draws its own geometry, both with Minecraft's plain coloured-vertex shader. */
final class MapPipelines {
    /** Map terrain into the map's own image: depth tested, opaque, back faces dropped. */
    static final RenderPipeline TERRAIN = base("pipeline/map_terrain")
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .withCull(true)
            .build();

    /** Ping holograms in the world: blended and visible through walls. */
    static final RenderPipeline SEE_THROUGH = base("pipeline/ping_hologram")
            .withDepthStencilState(Optional.empty())
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withCull(false)
            .build();

    private MapPipelines() {}

    private static RenderPipeline.Builder base(String location) {
        return RenderPipeline.builder()
                .withLocation(IsoMap.id(location))
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                .withVertexShader("core/position_color")
                .withFragmentShader("core/position_color")
                .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS);
    }
}
