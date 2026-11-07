package dev.isomap.client.ui.widget;

import dev.isomap.client.sound.UiSound;
import dev.isomap.client.ui.Theme;
import dev.isomap.config.IsoMapConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;

/**
 * Base of all controls. Handles the shared feedback: a slight grow on hover, a short flash on press, dimming when inactive,
 * and the rate-limited hover sound. Subclasses only paint.
 */
public abstract class IsoWidget extends AbstractWidget {
    private static final float HOVER_GROW = 0.04f;
    private static final float HOVER_RATE = 22f;
    private static final float PRESS_RATE = 7f;

    private float hoverAmount;
    private float pressAmount;
    private int clickButton;
    private boolean wasHovered;
    private long lastFrame;
    private int clipTop = Integer.MIN_VALUE;
    private int clipBottom = Integer.MAX_VALUE;
    protected float growFactor = HOVER_GROW;

    protected IsoWidget(int x, int y, int width, int height, Component message) {
        super(x, y, width, height, message);
    }

    /** Limits mouse interaction for controls inside a scrolled area. */
    public void setClip(int top, int bottom) {
        clipTop = top;
        clipBottom = bottom;
    }

    @Override
    protected boolean clicked(double mouseX, double mouseY) {
        return super.clicked(mouseX, mouseY) && mouseY >= clipTop && mouseY < clipBottom;
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return super.isMouseOver(mouseX, mouseY) && mouseY >= clipTop && mouseY < clipBottom;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        boolean hovered = isHovered() && active;
        if (hovered && !wasHovered) {
            UiSound.HOVER.play();
        }
        wasHovered = hovered;

        long now = System.nanoTime();
        float dt = lastFrame == 0L ? 0f : Math.min(0.1f, (now - lastFrame) / 1.0e9f);
        lastFrame = now;
        boolean animated = IsoMapConfig.UI_ANIMATIONS.getAsBoolean();
        if (animated) {
            hoverAmount += ((hovered ? 1f : 0f) - hoverAmount) * (1f - (float) Math.exp(-dt * HOVER_RATE));
            pressAmount *= (float) Math.exp(-dt * PRESS_RATE);
        } else {
            hoverAmount = hovered ? 1f : 0f;
            pressAmount = 0f;
        }

        var pose = g.pose();
        pose.pushPose();
        if (animated) {
            float scale = 1f + growFactor * hoverAmount;
            float cx = getX() + width / 2f;
            float cy = getY() + height / 2f;
            pose.translate(cx, cy, 0f);
            pose.scale(scale, scale, 1f);
            pose.translate(-cx, -cy, 0f);
        }
        paint(g, mouseX, mouseY, partialTick, hoverAmount, pressAmount);
        pose.popPose();
    }

    protected abstract void paint(GuiGraphics g, int mouseX, int mouseY, float partialTick, float hover, float press);

    /** Fades a colour for the inactive state. */
    protected int dim(int color) {
        return active ? color : Theme.withAlpha(color, Theme.DISABLED_ALPHA);
    }

    /** Background and border every control shares, including hover brightening and the press flash. */
    protected void paintBox(GuiGraphics g, float hover, float press) {
        int fill = dim(Theme.mix(Theme.SURFACE, Theme.SURFACE_HOVER, hover));
        int border = dim(Theme.mix(Theme.BORDER, Theme.BORDER_HOVER, hover));
        Theme.frame(g, getX(), getY(), width, height, fill, border);
        if (press > 0.01f) {
            g.fill(getX() + 1, getY() + 1, getRight() - 1, getBottom() - 1, Theme.withAlpha(Theme.FLASH, press));
        }
    }

    // Plain Minecraft tells onClick nothing about the button; NeoForge does, Fabric does not. The button is noted here instead.
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        clickButton = button;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        pressAmount = 1f;
        onPressed(mouseX, mouseY, clickButton);
    }

    /** A valid click landed on this control; the press flash has already started. */
    protected abstract void onPressed(double mouseX, double mouseY, int button);

    @Override
    public void playDownSound(SoundManager manager) {
        // Each control plays its own sound from UiSound instead of the vanilla button click.
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
