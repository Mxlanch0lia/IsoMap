package dev.isomap.client.gpu;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.isomap.IsoMap;
import java.util.Optional;
import net.minecraft.client.renderer.BindGroupLayouts;

/** The two ways the mod draws its own geometry, both with Minecraft's plain coloured-vertex shader. */
final class MapPipelines {
    /** Map terrain into the map's own image: depth tested, opaque, back faces dropped. */
    static final RenderPipeline TERRAIN = base("pipeline/map_terrain")
            .withColorTargetState(ColorTargetState.DEFAULT)
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
                .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                .withVertexShader("core/position_color")
                .withFragmentShader("core/position_color")
                .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.QUADS);
    }
}
