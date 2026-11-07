package dev.isomap.client.map;

import dev.isomap.config.IsoMapConfig;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * Reads a chunk's visible surface: height and a colour per column, from the vanilla map palette plus biome tint.
 *
 * <p>Underground the world is cut by a horizontal plane just above the player, like an architect's section. Rock at that
 * plane becomes a dark cut surface, and open space shows its floor, fading with depth and warmed by nearby torches.
 */
final class ChunkSampler {
    /** Pass as {@code scanFrom} to take the top surface instead of slicing at a height. */
    static final int SURFACE = Integer.MAX_VALUE;

    private static final int MAX_WATER_DEPTH = 24;
    private static final int MAX_CAVE_DEPTH = 64;
    private static final int CAVE_FADE_DEPTH = 32;
    private static final int FALLBACK_ROCK_COLOR = 0x707070;
    private static final int BOTTOMLESS_COLOR = 0x0A0B0D;
    private static final int TORCH_LIGHT_COLOR = 0xFFB060;
    private static final int COLUMNS = 256;
    /** This many solid blocks in a row are the ground; anything thinner with air under it hangs. */
    private static final int GROUND_THICKNESS = 5;

    // Sampling runs on the client thread only. The results are copied out, so most chunks allocate nothing for layers.
    private static final short[] TOP_BOTTOMS = new short[COLUMNS];
    private static final short[] LOWER_START = new short[COLUMNS + 1];
    private static final short[] LOWER_TOPS = new short[COLUMNS * (ChunkSnapshot.MAX_LAYERS - 1)];
    private static final short[] LOWER_BOTTOMS = new short[COLUMNS * (ChunkSnapshot.MAX_LAYERS - 1)];
    private static final int[] LOWER_COLORS = new int[COLUMNS * (ChunkSnapshot.MAX_LAYERS - 1)];

    private ChunkSampler() {}

    static ChunkSnapshot sample(ClientLevel level, LevelChunk chunk, int scanFrom, int generation, long now) {
        short[] heights = new short[256];
        int[] colors = new int[256];
        BlockColors blockColors = Minecraft.getInstance().getBlockColors();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        int minY = level.getMinBuildHeight();
        boolean slicing = scanFrom != SURFACE;
        // A cave slice shows what is under one plane, so a stack of layers has no meaning there.
        boolean readLayers = !slicing && IsoMapConfig.TERRAIN_LAYERS.getAsBoolean();
        boolean anyLayers = false;
        int extra = 0;
        Arrays.fill(TOP_BOTTOMS, ChunkSnapshot.SOLID);
        Arrays.fill(LOWER_START, (short) 0);

        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int index = ChunkSnapshot.index(lx, lz);
                int wx = baseX + lx;
                int wz = baseZ + lz;
                int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
                int lowest = minY;

                if (slicing) {
                    y = Math.min(y, scanFrom);
                    pos.set(wx, y, wz);
                    BlockState atSlice = chunk.getBlockState(pos);
                    if (y == scanFrom && isSurface(level, atSlice, pos) && atSlice.getFluidState().isEmpty()) {
                        int rock = atSlice.getMapColor(level, pos).col;
                        heights[index] = (short) y;
                        colors[index] = ChunkSnapshot.CUT_FLAG | (rock == 0 ? FALLBACK_ROCK_COLOR : rock);
                        continue;
                    }
                    lowest = Math.max(minY, scanFrom - MAX_CAVE_DEPTH);
                }

                BlockState state = null;
                while (y >= lowest) {
                    pos.set(wx, y, wz);
                    BlockState candidate = chunk.getBlockState(pos);
                    if (isSurface(level, candidate, pos)) {
                        state = candidate;
                        break;
                    }
                    y--;
                }
                if (state == null) {
                    if (slicing) {
                        heights[index] = (short) lowest;
                        colors[index] = BOTTOMLESS_COLOR;
                    } else {
                        heights[index] = ChunkSnapshot.NO_DATA;
                    }
                    continue;
                }
                heights[index] = (short) y;
                int rgb = surfaceColor(level, chunk, blockColors, state, pos, y, minY);
                colors[index] = slicing ? caveFloorColor(level, pos, rgb, scanFrom - y) : rgb;
                if (readLayers) {
                    extra = scanLayersBelow(level, chunk, blockColors, pos, wx, wz, y, state, minY, index, extra);
                    anyLayers |= extra > 0 || TOP_BOTTOMS[index] != ChunkSnapshot.SOLID;
                }
            }
        }
        return new ChunkSnapshot(heights, colors, anyLayers ? copyLayers(extra) : null, scanFrom, generation, now);
    }

    /**
     * Walks down a column from its top block and notes each separate piece: a new layer starts below a gap of air, and where
     * leaves meet anything else, which is what tells a crown from its trunk. A few solid blocks in a row count as the ground,
     * and the search ends there.
     *
     * @return the new number of lower layers stored so far in this chunk
     */
    private static int scanLayersBelow(ClientLevel level, LevelChunk chunk, BlockColors blockColors, BlockPos.MutableBlockPos pos,
            int wx, int wz, int topY, BlockState topState, int minY, int column, int extra) {
        int next = extra;
        BlockState state = topState;
        int y = topY;
        int layer = 0;
        while (true) {
            int bottom = ChunkSnapshot.SOLID;
            boolean more = false;
            if (state.getFluidState().isEmpty() && layer < ChunkSnapshot.MAX_LAYERS - 1) {
                boolean leaves = state.is(BlockTags.LEAVES);
                int thickness = 1;
                int below = y - 1;
                bottom = y;
                while (true) {
                    if (below < minY) {
                        break;
                    }
                    pos.set(wx, below, wz);
                    BlockState candidate = chunk.getBlockState(pos);
                    if (!isSurface(level, candidate, pos)) {
                        // A gap: on to whatever is under it, or nothing at all over the void.
                        while (--below >= minY) {
                            pos.setY(below);
                            candidate = chunk.getBlockState(pos);
                            if (isSurface(level, candidate, pos)) {
                                break;
                            }
                        }
                        if (below >= minY) {
                            state = candidate;
                            more = true;
                        }
                        break;
                    }
                    // Snow on a crown belongs to the crown.
                    if (below == y - 1 && state.is(Blocks.SNOW)) {
                        leaves = candidate.is(BlockTags.LEAVES);
                    }
                    if (!candidate.getFluidState().isEmpty() || candidate.is(BlockTags.LEAVES) != leaves) {
                        state = candidate;
                        more = true;
                        break;
                    }
                    if (!leaves && ++thickness >= GROUND_THICKNESS) {
                        bottom = ChunkSnapshot.SOLID;
                        break;
                    }
                    bottom = below;
                    below--;
                }
                if (more) {
                    y = below;
                }
            }

            if (layer == 0) {
                TOP_BOTTOMS[column] = (short) bottom;
            } else {
                LOWER_BOTTOMS[next - 1] = (short) bottom;
            }
            if (!more) {
                break;
            }
            layer++;
            pos.set(wx, y, wz);
            LOWER_TOPS[next] = (short) y;
            LOWER_COLORS[next] = surfaceColor(level, chunk, blockColors, state, pos, y, minY);
            next++;
        }
        LOWER_START[column + 1] = (short) next;
        return next;
    }

    private static ChunkSnapshot.Layers copyLayers(int extra) {
        for (int c = 1; c <= COLUMNS; c++) {
            // Columns that were skipped as empty hold no lower layers.
            if (LOWER_START[c] < LOWER_START[c - 1]) {
                LOWER_START[c] = LOWER_START[c - 1];
            }
        }
        return new ChunkSnapshot.Layers(TOP_BOTTOMS.clone(), LOWER_START.clone(), Arrays.copyOf(LOWER_TOPS, extra),
                Arrays.copyOf(LOWER_BOTTOMS, extra), Arrays.copyOf(LOWER_COLORS, extra));
    }

    /** Skips what would only be noise on a map: air, plants, torches and other blocks without collision. */
    private static boolean isSurface(ClientLevel level, BlockState state, BlockPos pos) {
        if (state.isAir()) {
            return false;
        }
        if (!state.getFluidState().isEmpty() || state.is(Blocks.SNOW)) {
            return true;
        }
        return state.getMapColor(level, pos) != MapColor.NONE && !state.getCollisionShape(level, pos).isEmpty();
    }

    private static int surfaceColor(ClientLevel level, LevelChunk chunk, BlockColors blockColors, BlockState state,
            BlockPos.MutableBlockPos pos, int y, int minY) {
        MapColor mapColor = state.getMapColor(level, pos);
        boolean fluid = !state.getFluidState().isEmpty();
        int rgb = mapColor.col;

        if (mapColor == MapColor.GRASS || mapColor == MapColor.PLANT || mapColor == MapColor.WATER) {
            int tint = blockColors.getColor(state, level, pos, 0);
            if (tint != -1) {
                rgb = tint & 0xFFFFFF;
            }
        }
        if (state.is(BlockTags.LEAVES)) {
            rgb = MapColors.multiply(rgb, 0.78f);
        }
        if (fluid && mapColor == MapColor.WATER) {
            int depth = 0;
            for (int dy = y - 1; dy >= minY && depth < MAX_WATER_DEPTH; dy--) {
                pos.setY(dy);
                if (chunk.getBlockState(pos).getFluidState().isEmpty()) {
                    break;
                }
                depth++;
            }
            pos.setY(y);
            rgb = MapColors.multiply(rgb, 1.08f - 0.5f * depth / MAX_WATER_DEPTH);
        }
        return rgb;
    }

    /** Floors dim with depth below the cut, so the shape of a cave reads at a glance; light from torches and lava warms them. */
    private static int caveFloorColor(ClientLevel level, BlockPos.MutableBlockPos pos, int rgb, int depth) {
        int y = pos.getY();
        pos.setY(y + 1);
        int light = level.getBrightness(LightLayer.BLOCK, pos);
        pos.setY(y);
        float fade = 1f - 0.55f * Math.min(depth, CAVE_FADE_DEPTH) / CAVE_FADE_DEPTH;
        int shaded = MapColors.multiply(rgb, fade * (0.82f + 0.32f * light / 15f));
        return light >= 8 ? MapColors.mix(shaded, TORCH_LIGHT_COLOR, 0.16f * (light - 7) / 8f) : shaded;
    }
}
