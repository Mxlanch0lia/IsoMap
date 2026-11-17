package dev.isomap.client.gpu;

import com.mojang.blaze3d.vertex.VertexConsumer;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;

/** GUI drawing beyond what {@link GuiGraphicsExtractor} offers: free-form coloured quads. */
public final class GuiAccess {
    private GuiAccess() {}

    static GuiRenderState state() {
        return Minecraft.getInstance().gameRenderer.gameRenderState().guiRenderState;
    }

    /**
     * Fills quads given as x,y corner pairs, four corners per quad, in the current transform of {@code g}.
     *
     * @param bounds the area the quads stay inside, in the same units
     */
    public static void quads(GuiGraphicsExtractor g, float[] corners, int color, int boundsX, int boundsY, int boundsW, int boundsH) {
        Matrix3x2f pose = new Matrix3x2f(g.pose());
        ScreenRectangle area = new ScreenRectangle(boundsX, boundsY, boundsW, boundsH).transformMaxBounds(pose);
        state().addGuiElement(new Quads(pose, corners.clone(), color, area));
    }

    private record Quads(Matrix3x2fc pose, float[] corners, int color, @Nullable ScreenRectangle bounds) implements GuiElementRenderState {
        @Override
        public void buildVertices(VertexConsumer out) {
            for (int i = 0; i + 1 < corners.length; i += 2) {
                out.addVertexWith2DPose(pose, corners[i], corners[i + 1]).setColor(color);
            }
        }

        @Override
        public com.mojang.renderpearl.api.pipeline.RenderPipeline pipeline() {
            return RenderPipelines.GUI;
        }

        @Override
        public TextureSetup textureSetup() {
            return TextureSetup.noTexture();
        }

        @Override
        public @Nullable ScreenRectangle scissorArea() {
            return null;
        }
    }
}
