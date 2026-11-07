package dev.isomap.client.map;

public final class MapColors {
    private MapColors() {}

    public static int multiply(int rgb, float factor) {
        int r = clampByte(((rgb >> 16) & 0xFF) * factor);
        int g = clampByte(((rgb >> 8) & 0xFF) * factor);
        int b = clampByte((rgb & 0xFF) * factor);
        return (r << 16) | (g << 8) | b;
    }

    public static int mix(int from, int to, float t) {
        int r = clampByte(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = clampByte(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = clampByte((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (r << 16) | (g << 8) | b;
    }

    private static int clampByte(float value) {
        int v = (int) value;
        return v < 0 ? 0 : Math.min(v, 255);
    }
}
