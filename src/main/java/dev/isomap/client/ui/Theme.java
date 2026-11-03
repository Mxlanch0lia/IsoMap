package dev.isomap.client.ui;

import net.minecraft.client.gui.GuiGraphics;

/** The whole visual language: a few colours and hard-edged rectangles. No textures, no rounded corners. */
public final class Theme {
    public static final int PANEL = 0xD0101215;
    public static final int PANEL_HEADER = 0x40FFFFFF;
    public static final int SURFACE = 0x16FFFFFF;
    public static final int SURFACE_HOVER = 0x30FFFFFF;
    public static final int BORDER = 0x55FFFFFF;
    public static final int BORDER_HOVER = 0xA0FFFFFF;
    public static final int TEXT = 0xFFE9EBEE;
    public static final int TEXT_DIM = 0xFFA3A8B0;
    public static final int ACCENT = 0xFF6FB1FF;
    public static final int ACCENT_WARM = 0xFFFF8A73;
    public static final int DANGER = 0xFFE5655C;
    public static final int FLASH = 0x60FFFFFF;
    public static final float DISABLED_ALPHA = 0.42f;

    private Theme() {}

    /** Multiplies the colour's alpha channel by {@code factor}. */
    public static int withAlpha(int argb, float factor) {
        int alpha = (int) (((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, factor)));
        return (alpha << 24) | (argb & 0xFFFFFF);
    }

    public static int mix(int from, int to, float t) {
        float clamped = Math.max(0f, Math.min(1f, t));
        int a = mixChannel(from >>> 24, to >>> 24, clamped);
        int r = mixChannel((from >> 16) & 0xFF, (to >> 16) & 0xFF, clamped);
        int g = mixChannel((from >> 8) & 0xFF, (to >> 8) & 0xFF, clamped);
        int b = mixChannel(from & 0xFF, to & 0xFF, clamped);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int mixChannel(int from, int to, float t) {
        return Math.round(from + (to - from) * t);
    }

    public static void frame(GuiGraphics g, int x, int y, int width, int height, int fill, int border) {
        g.fill(x, y, x + width, y + height, fill);
        g.renderOutline(x, y, width, height, border);
    }

    public static void panel(GuiGraphics g, int x, int y, int width, int height) {
        frame(g, x, y, width, height, PANEL, BORDER);
    }
}
