package dev.isomap.client.map;

import java.util.Arrays;
import javax.annotation.Nullable;

/**
 * Immutable surface data of one chunk. Safe to read from the meshing thread once published.
 *
 * <p>A column is a stack of layers from the top down: a floating block and the ground under it, or a crown, its trunk and
 * the ground. Layer 0 lives in {@link #heights} and {@link #colors}; a chunk where every column is one solid piece keeps
 * nothing else, so plains cost no more than they used to.
 */
public final class ChunkSnapshot {
    /** Height of a column with nothing to draw. */
    public static final short NO_DATA = Short.MIN_VALUE;
    /** Bottom of a layer that simply goes on down into the ground. */
    static final short SOLID = Short.MIN_VALUE;
    static final int MAX_LAYERS = 4;
    /** Set in a colour when the column is rock cut through by the cave slice; the low bits hold the rock's own colour. */
    static final int CUT_FLAG = 1 << 24;
    /** Slices this close to each other are similar enough to stay on screen until the chunk is re-read. */
    private static final int COMPATIBLE_SLICE_RANGE = 16;

    /**
     * The part of the columns below their top: {@code start[c]} to {@code start[c + 1]} are the indices of column c's lower
     * layers in the other arrays. {@code topBottoms[c]} is where column c's top layer ends.
     */
    record Layers(short[] topBottoms, short[] start, short[] tops, short[] bottoms, int[] colors) {
        int hash() {
            return 31 * (31 * (31 * Arrays.hashCode(topBottoms) + Arrays.hashCode(tops)) + Arrays.hashCode(bottoms)) + Arrays.hashCode(colors);
        }
    }

    public final short[] heights;
    public final int[] colors;
    @Nullable
    final Layers layers;
    public final int contentHash;
    public final int scanFrom;
    public final int scanGeneration;
    public final long sampledAt;

    ChunkSnapshot(short[] heights, int[] colors, @Nullable Layers layers, int scanFrom, int scanGeneration, long sampledAt) {
        this.heights = heights;
        this.colors = colors;
        this.layers = layers;
        this.contentHash = 31 * (31 * Arrays.hashCode(heights) + Arrays.hashCode(colors)) + (layers == null ? 0 : layers.hash());
        this.scanFrom = scanFrom;
        this.scanGeneration = scanGeneration;
        this.sampledAt = sampledAt;
    }

    public static int index(int localX, int localZ) {
        return (localZ << 4) | localX;
    }

    int layerCount(int column) {
        return layers == null ? 1 : 1 + layers.start[column + 1] - layers.start[column];
    }

    int layerTop(int column, int layer) {
        return layer == 0 ? heights[column] : layers.tops[layers.start[column] + layer - 1];
    }

    /** The lowest block of a layer, or {@link #SOLID}. */
    int layerBottom(int column, int layer) {
        if (layers == null) {
            return SOLID;
        }
        return layer == 0 ? layers.topBottoms[column] : layers.bottoms[layers.start[column] + layer - 1];
    }

    int layerColor(int column, int layer) {
        return layer == 0 ? colors[column] : layers.colors[layers.start[column] + layer - 1];
    }

    /** Height of the column's lowest layer: the ground under whatever hangs over it. */
    int groundHeight(int column) {
        return layerTop(column, layerCount(column) - 1);
    }

    /** Surface data must not float above a cave slice, nor a slice lie under the open sky. */
    boolean isShownFor(int currentScan) {
        if (scanFrom == ChunkSampler.SURFACE || currentScan == ChunkSampler.SURFACE) {
            return scanFrom == currentScan;
        }
        return Math.abs(scanFrom - currentScan) <= COMPATIBLE_SLICE_RANGE;
    }
}
