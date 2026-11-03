package dev.isomap.client.ui.widget;

import dev.isomap.client.ui.Theme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/** Scroll offset and a rectangular scrollbar for a clipped column of rows. */
public final class ScrollArea {
    private static final int BAR_WIDTH = 3;
    private static final double WHEEL_STEP = 18.0;
    private static final int MIN_THUMB = 12;

    private int x;
    private int y;
    private int width;
    private int height;
    private int contentHeight;
    private double offset;
    private boolean dragging;

    public void setBounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        clamp();
    }

    public void setContentHeight(int value) {
        contentHeight = value;
        clamp();
    }

    public int offset() {
        return (int) offset;
    }

    public void scrollToTop() {
        offset = 0.0;
    }

    public int top() {
        return y;
    }

    public int bottom() {
        return y + height;
    }

    /** Width left for rows once the scrollbar is accounted for. */
    public int contentWidth() {
        return width - BAR_WIDTH - 3;
    }

    private int maxOffset() {
        return Math.max(0, contentHeight - height);
    }

    private void clamp() {
        offset = Mth.clamp(offset, 0.0, maxOffset());
    }

    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!contains(mouseX, mouseY) || maxOffset() == 0) {
            return false;
        }
        offset -= delta * WHEEL_STEP;
        clamp();
        return true;
    }

    private int thumbHeight() {
        return Math.max(MIN_THUMB, height * height / Math.max(contentHeight, 1));
    }

    public boolean mouseClicked(double mouseX, double mouseY) {
        if (maxOffset() > 0 && mouseX >= x + width - BAR_WIDTH - 2 && mouseX < x + width && mouseY >= y && mouseY < y + height) {
            dragging = true;
            scrollToMouse(mouseY);
            return true;
        }
        return false;
    }

    public boolean mouseDragged(double mouseY) {
        if (dragging) {
            scrollToMouse(mouseY);
            return true;
        }
        return false;
    }

    public void mouseReleased() {
        dragging = false;
    }

    private void scrollToMouse(double mouseY) {
        int thumb = thumbHeight();
        double t = (mouseY - y - thumb / 2.0) / Math.max(1, height - thumb);
        offset = Mth.clamp(t, 0.0, 1.0) * maxOffset();
    }

    public void renderBar(GuiGraphics g) {
        if (maxOffset() == 0) {
            return;
        }
        int barX = x + width - BAR_WIDTH;
        g.fill(barX, y, barX + BAR_WIDTH, y + height, Theme.SURFACE);
        int thumb = thumbHeight();
        int thumbY = y + (int) ((height - thumb) * (offset / maxOffset()));
        g.fill(barX, thumbY, barX + BAR_WIDTH, thumbY + thumb, dragging ? Theme.TEXT : Theme.TEXT_DIM);
    }
}
