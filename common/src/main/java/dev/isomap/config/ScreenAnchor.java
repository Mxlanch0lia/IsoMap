package dev.isomap.config;

public enum ScreenAnchor implements Labeled {
    TOP_LEFT(false, false),
    TOP_RIGHT(true, false),
    BOTTOM_LEFT(false, true),
    BOTTOM_RIGHT(true, true);

    public final boolean right;
    public final boolean bottom;

    ScreenAnchor(boolean right, boolean bottom) {
        this.right = right;
        this.bottom = bottom;
    }

    @Override
    public String labelKey() {
        return "isomap.anchor." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
