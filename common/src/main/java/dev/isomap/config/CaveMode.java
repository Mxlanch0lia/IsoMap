package dev.isomap.config;

public enum CaveMode implements Labeled {
    /** Underground and under a dimension ceiling the map slices terrain at the player's height. */
    AUTO,
    /** Always the top surface, except in dimensions with a ceiling such as the Nether. */
    OFF;

    @Override
    public String labelKey() {
        return "isomap.cave_mode." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
