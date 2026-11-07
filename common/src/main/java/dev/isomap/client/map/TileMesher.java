package dev.isomap.client.map;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.Arrays;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Turns chunk snapshots into the coloured quads of one tile: a flat top per layer of a column and walls wherever the
 * neighbouring column leaves that layer exposed. Undersides are never built, the camera is always above them.
 * Runs on the meshing thread and touches nothing but immutable snapshots. Vertices are relative to the tile's corner.
 */
final class TileMesher {
    static final int TILE_CHUNKS = 4;
    static final int TILE_BLOCKS = TILE_CHUNKS * 16;

    /** A tile reads one chunk ring around itself for neighbour heights. */
    private static final int LOOKUP_SIZE = TILE_CHUNKS + 2;
    private static final int MISSING_NEIGHBOUR_DROP = 6;
    private static final int SEA_LEVEL = 63;

    private static final float SHADE_NORTH = 0.80f;
    private static final float SHADE_SOUTH = 0.66f;
    private static final float SHADE_WEST = 0.74f;
    private static final float SHADE_EAST = 0.60f;
    private static final float WALL_BOTTOM_FADE = 0.82f;
    /** What lies under a crown or a floating block is in its shade, which also shows how high that block hangs. */
    private static final float SHADE_UNDER = 0.68f;

    /** The cut through rock is almost black with a hint of the rock itself; its edges outline the caves. */
    private static final int CUT_BASE_COLOR = 0x17191D;
    private static final float CUT_ROCK_TINT = 0.16f;
    private static final float CUT_RIM_TINT = 0.62f;
    private static final float CUT_WALL_BOTTOM_FADE = 0.42f;

    private final ChunkSnapshot[] lookup = new ChunkSnapshot[LOOKUP_SIZE * LOOKUP_SIZE];
    private final int originBlockX;
    private final int originBlockZ;
    private final int lookupBlockX;
    private final int lookupBlockZ;
    private final int step;

    TileMesher(int tileX, int tileZ, int step, int scanFrom, Map<Long, ChunkSnapshot> snapshots) {
        this.step = step;
        int firstChunkX = tileX * TILE_CHUNKS - 1;
        int firstChunkZ = tileZ * TILE_CHUNKS - 1;
        this.originBlockX = tileX * TILE_BLOCKS;
        this.originBlockZ = tileZ * TILE_BLOCKS;
        this.lookupBlockX = firstChunkX * 16;
        this.lookupBlockZ = firstChunkZ * 16;
        for (int z = 0; z < LOOKUP_SIZE; z++) {
            for (int x = 0; x < LOOKUP_SIZE; x++) {
                ChunkSnapshot snapshot = snapshots.get(TerrainCache.chunkKey(firstChunkX + x, firstChunkZ + z));
                lookup[z * LOOKUP_SIZE + x] = snapshot != null && snapshot.isShownFor(scanFrom) ? snapshot : null;
            }
        }
    }

    private int heightAt(int wx, int wz) {
        int rx = wx - lookupBlockX;
        int rz = wz - lookupBlockZ;
        int cx = rx >> 4;
        int cz = rz >> 4;
        if (cx < 0 || cz < 0 || cx >= LOOKUP_SIZE || cz >= LOOKUP_SIZE) {
            return ChunkSnapshot.NO_DATA;
        }
        ChunkSnapshot snapshot = lookup[cz * LOOKUP_SIZE + cx];
        return snapshot == null ? ChunkSnapshot.NO_DATA : snapshot.heights[ChunkSnapshot.index(rx & 15, rz & 15)];
    }

    @Nullable
    private ChunkSnapshot snapshotAt(int wx, int wz) {
        int cx = (wx - lookupBlockX) >> 4;
        int cz = (wz - lookupBlockZ) >> 4;
        return cx < 0 || cz < 0 || cx >= LOOKUP_SIZE || cz >= LOOKUP_SIZE ? null : lookup[cz * LOOKUP_SIZE + cx];
    }

    private static int columnOf(int wx, int wz) {
        return ChunkSnapshot.index(wx & 15, wz & 15);
    }

    private boolean isLower(int wx, int wz, int height) {
        int other = heightAt(wx, wz);
        return other != ChunkSnapshot.NO_DATA && other < height;
    }

    /** Colour of a column's top: light comes from the north-west, higher ground is slightly brighter. */
    private int topColor(int wx, int wz, int height, int raw) {
        int rgb = raw & 0xFFFFFF;
        if ((raw & ChunkSnapshot.CUT_FLAG) != 0) {
            boolean rim = isLower(wx, wz - step, height) || isLower(wx, wz + step, height)
                    || isLower(wx - step, wz, height) || isLower(wx + step, wz, height);
            return MapColors.mix(CUT_BASE_COLOR, rgb, rim ? CUT_RIM_TINT : CUT_ROCK_TINT);
        }
        int north = heightAt(wx, wz - step);
        int west = heightAt(wx - step, wz);
        float slope = 0f;
        if (north != ChunkSnapshot.NO_DATA) {
            slope += (height - north) * 0.07f;
        }
        if (west != ChunkSnapshot.NO_DATA) {
            slope += (height - west) * 0.04f;
        }
        slope = Math.max(-0.28f, Math.min(0.22f, slope / step));
        float altitude = Math.max(-0.10f, Math.min(0.12f, (height - SEA_LEVEL) / 400f));
        return MapColors.multiply(rgb, 1f + slope + altitude);
    }

    /** @return the finished mesh, or null if the tile has nothing to draw. Owned by {@code buffer}. */
    @Nullable
    MeshData build(ByteBufferBuilder buffer) {
        BufferBuilder out = new BufferBuilder(buffer, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        // Each layer depth merges along the row by itself, so the ground under a forest still shrinks.
        int[] runStart = new int[ChunkSnapshot.MAX_LAYERS];
        int[] runHeight = new int[ChunkSnapshot.MAX_LAYERS];
        int[] runColor = new int[ChunkSnapshot.MAX_LAYERS];
        for (int lz = 0; lz < TILE_BLOCKS; lz += step) {
            int wz = originBlockZ + lz;
            Arrays.fill(runStart, -1);
            for (int lx = 0; lx < TILE_BLOCKS; lx += step) {
                int wx = originBlockX + lx;
                ChunkSnapshot snapshot = snapshotAt(wx, wz);
                int column = columnOf(wx, wz);
                int count = snapshot == null || snapshot.heights[column] == ChunkSnapshot.NO_DATA ? 0 : snapshot.layerCount(column);
                for (int layer = 0; layer < ChunkSnapshot.MAX_LAYERS; layer++) {
                    boolean exists = layer < count;
                    int height = exists ? snapshot.layerTop(column, layer) : 0;
                    int raw = exists ? snapshot.layerColor(column, layer) : 0;
                    // A trunk's top is pressed against the crown above it: nothing can ever see it.
                    boolean visibleTop = exists && (layer == 0 || snapshot.layerBottom(column, layer - 1) != height + 1);
                    int color = visibleTop ? (layer == 0 ? topColor(wx, wz, height, raw) : MapColors.multiply(raw & 0xFFFFFF, SHADE_UNDER)) : 0;
                    // Equal neighbours in a row share one quad: oceans and plains shrink a lot.
                    if (runStart[layer] >= 0 && (!visibleTop || height != runHeight[layer] || color != runColor[layer])) {
                        top(out, runStart[layer], lx, lz, runHeight[layer], runColor[layer]);
                        runStart[layer] = -1;
                    }
                    if (visibleTop && runStart[layer] < 0) {
                        runStart[layer] = lx;
                        runHeight[layer] = height;
                        runColor[layer] = color;
                    }
                    if (exists) {
                        walls(out, wx, wz, lx, lz, height, snapshot.layerBottom(column, layer), raw);
                    }
                }
            }
            for (int layer = 0; layer < ChunkSnapshot.MAX_LAYERS; layer++) {
                if (runStart[layer] >= 0) {
                    top(out, runStart[layer], TILE_BLOCKS, lz, runHeight[layer], runColor[layer]);
                }
            }
        }
        return out.build();
    }

    private void top(BufferBuilder out, int x0, int x1, int z0, int height, int rgb) {
        int color = 0xFF000000 | rgb;
        float y = height + 1;
        int z1 = z0 + step;
        out.addVertex(x0, y, z0).setColor(color);
        out.addVertex(x0, y, z1).setColor(color);
        out.addVertex(x1, y, z1).setColor(color);
        out.addVertex(x1, y, z0).setColor(color);
    }

    private void walls(BufferBuilder out, int wx, int wz, int lx, int lz, int height, int bottom, int raw) {
        int rgb = raw & 0xFFFFFF;
        // Cave walls are the lit side of the rock and fall off into the dark towards the floor.
        float fade = (raw & ChunkSnapshot.CUT_FLAG) != 0 ? CUT_WALL_BOTTOM_FADE : WALL_BOTTOM_FADE;
        int x1 = lx + step;
        int z1 = lz + step;
        side(out, wx, wz - step, height, bottom, rgb, SHADE_NORTH, fade, x1, lz, lx, lz);
        side(out, wx, wz + step, height, bottom, rgb, SHADE_SOUTH, fade, lx, z1, x1, z1);
        side(out, wx - step, wz, height, bottom, rgb, SHADE_WEST, fade, lx, lz, lx, z1);
        side(out, wx + step, wz, height, bottom, rgb, SHADE_EAST, fade, x1, z1, x1, lz);
    }

    /** One side of a layer: only the stretches that no layer of the neighbouring column covers get a wall. */
    private void side(BufferBuilder out, int nx, int nz, int height, int bottom, int rgb, float shade, float fade, int x0, int z0, int x1, int z1) {
        ChunkSnapshot neighbour = snapshotAt(nx, nz);
        int column = columnOf(nx, nz);
        int lowest = bottom == ChunkSnapshot.SOLID ? Integer.MIN_VALUE : bottom;
        int cursor = height;
        if (neighbour != null && neighbour.heights[column] != ChunkSnapshot.NO_DATA) {
            int count = neighbour.layerCount(column);
            for (int layer = 0; layer < count; layer++) {
                int otherTop = neighbour.layerTop(column, layer);
                int otherBottom = neighbour.layerBottom(column, layer);
                if (otherTop < cursor) {
                    int end = Math.max(otherTop + 1, lowest);
                    quad(out, rgb, shade, fade, cursor + 1, end, x0, z0, x1, z1);
                    cursor = end - 1;
                }
                if (otherBottom == ChunkSnapshot.SOLID || cursor < lowest) {
                    return;
                }
                cursor = Math.min(cursor, otherBottom - 1);
                if (cursor < lowest) {
                    return;
                }
            }
        }
        // Open all the way down, or an unknown neighbour: a solid layer has no bottom of its own, so it ends in a short skirt.
        int end = bottom == ChunkSnapshot.SOLID ? cursor + 1 - MISSING_NEIGHBOUR_DROP : lowest;
        quad(out, rgb, shade, fade, cursor + 1, end, x0, z0, x1, z1);
    }

    /** A wall from (x0, z0) to (x1, z1); the corner order keeps its outside facing the camera for back-face culling. */
    private static void quad(BufferBuilder out, int rgb, float shade, float bottomFade, float top, float bottom, int x0, int z0, int x1, int z1) {
        int upper = 0xFF000000 | MapColors.multiply(rgb, shade);
        int lower = 0xFF000000 | MapColors.multiply(rgb, shade * bottomFade);
        out.addVertex(x0, top, z0).setColor(upper);
        out.addVertex(x0, bottom, z0).setColor(lower);
        out.addVertex(x1, bottom, z1).setColor(lower);
        out.addVertex(x1, top, z1).setColor(upper);
    }
}
