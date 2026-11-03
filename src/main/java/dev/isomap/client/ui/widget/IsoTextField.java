package dev.isomap.client.ui.widget;

import dev.isomap.client.sound.UiSound;
import dev.isomap.client.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * Vanilla text editing inside a flat frame. The inner {@link EditBox} is unbordered, which draws its text right at the
 * widget's corner, so the widget sits inset and this class paints and accepts clicks on the larger outer box.
 */
public class IsoTextField extends EditBox {
    private static final int PADDING_X = 4;

    private final int paddingY;
    private final int outerHeight;
    private boolean wasHovered;
    private boolean invalid;

    public IsoTextField(int x, int y, int width, int height, Component hint) {
        super(Minecraft.getInstance().font, x + PADDING_X, y + (height - 8) / 2, width - PADDING_X * 2, 9, hint);
        this.paddingY = (height - 8) / 2;
        this.outerHeight = height;
        setBordered(false);
        setTextShadow(false);
        setTextColor(Theme.TEXT);
        setHint(hint.copy().withColor(Theme.TEXT_DIM));
    }

    public void setInvalid(boolean value) {
        invalid = value;
    }

    private boolean overOuter(double mouseX, double mouseY) {
        return visible && mouseX >= getX() - PADDING_X && mouseX < getX() + width + PADDING_X
                && mouseY >= getY() - paddingY && mouseY < getY() - paddingY + outerHeight;
    }

    @Override
    protected boolean clicked(double mouseX, double mouseY) {
        return active && overOuter(mouseX, mouseY);
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return overOuter(mouseX, mouseY);
    }

    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        boolean hovered = overOuter(mouseX, mouseY);
        if (hovered && !wasHovered) {
            UiSound.HOVER.play();
        }
        wasHovered = hovered;
        int border = invalid ? Theme.DANGER : isFocused() ? Theme.ACCENT : hovered ? Theme.BORDER_HOVER : Theme.BORDER;
        Theme.frame(g, getX() - PADDING_X, getY() - paddingY, width + PADDING_X * 2, outerHeight, hovered ? Theme.SURFACE_HOVER : Theme.SURFACE, border);
        super.renderWidget(g, mouseX, mouseY, partialTick);
    }
}
