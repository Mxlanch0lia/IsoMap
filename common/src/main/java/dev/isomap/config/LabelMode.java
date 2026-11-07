package dev.isomap.config;

public enum LabelMode implements Labeled {
    NONE,
    /** Only waypoints close to the player. */
    CLOSE,
    ALL;

    @Override
    public String labelKey() {
        return "isomap.label_mode." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
