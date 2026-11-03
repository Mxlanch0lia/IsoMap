package dev.isomap.client.map;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.isomap.IsoMap;
import dev.isomap.config.CaveMode;
import dev.isomap.config.IsoMapConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Everything the map knows about terrain, in three stages that keep the frame cost near zero:
 * <ol>
 *   <li>{@link #tick} samples a few chunks each game tick under a strict time budget;</li>
 *   <li>tiles whose chunks really changed are meshed on a background thread;</li>
 *   <li>{@link #uploadPending} moves a few finished meshes per frame into static GPU buffers.</li>
 * </ol>
 * Camera movement only changes a matrix, so turning or zooming the map never rebuilds anything.
 * All methods except the mesher task run on the client thread.
 */
public final class TerrainCache {
    private static final long SAMPLE_BUDGET_NANOS = 1_500_000L;
    private static final int MAX_CHUNK_CHECKS_PER_TICK = 1024;
    private static final int MAX_UPLOADS_PER_FRAME = 4;
    private static final int MAX_MESHES_IN_FLIGHT = 6;
    private static final int MAX_SNAPSHOTS = 32_768;
    private static final long EXPLORED_FLUSH_NANOS = 30_000_000_000L;
    private static final long TILE_IDLE_NANOS = 10_000_000_000L;
    private static final int INITIAL_MESH_BYTES = 64 * 1024;
    private static final int SLICE_ABOVE_PLAYER = 3;
    /** The cut may lag behind the player this far (below / above the ideal height) before chunks are read again. */
    private static final int SLICE_KEEP_BELOW = 2;
    private static final int SLICE_KEEP_ABOVE = 5;
    private static final int SLICE_RECENTER = 2;
    private static final int SLICE_ENTER_TICKS = 10;
    private static final int SLICE_LEAVE_TICKS = 40;
    private static final int UNDERGROUND_MARGIN = 6;
    private static final int DEEP_BELOW_SURROUNDINGS = 16;
    private static final int SURROUNDINGS_RADIUS = 16;
    private static final int SURROUNDINGS_STEP = 8;
    private static final int GROUND_RADIUS = 12;
    private static final int GROUND_STEP = 3;

    private static final ExecutorService MESH_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "IsoMap terrain mesher");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    /** Read by the mesher, hence concurrent. Snapshots themselves are immutable. */
    private final Map<Long, ChunkSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Long2ObjectMap<Tile> tiles = new Long2ObjectOpenHashMap<>();
    private final Queue<MeshResult> finishedMeshes = new ConcurrentLinkedQueue<>();
    private final AtomicInteger meshesInFlight = new AtomicInteger();

    @Nullable
    private ClientLevel level;
    /** Bumped when the level changes, so meshes started for the old one are thrown away. */
    private int epoch;
    private int scanGeneration;
    private int scanFrom = ChunkSampler.SURFACE;
    private boolean slicing;
    private int slicingVotes;
    private final int[] surroundingHeights = new int[25];
    private final int[] groundHeights = new int[81];
    private int[] offsets = new int[0];
    private int offsetsRadius = -1;
    private int cursor;
    private int lastPlayerChunkX = Integer.MIN_VALUE;
    private int lastPlayerChunkZ = Integer.MIN_VALUE;
    private int step = 1;
    private int extraCenterX;
    private int extraCenterZ;
    private int extraRadius;
    private int extraStep = 1;
    private boolean extraCenterActive;
    private long lastTrim;
    private long lastExploredFlush;
    private final ExploredStore explored = new ExploredStore();
    private boolean readingLayers = IsoMapConfig.TERRAIN_LAYERS.getAsBoolean();
    private long sampleNanos;
    private int sampledChunks;

    private static final class Tile {
        final int x;
        final int z;
        @Nullable
        VertexBuffer buffer;
        boolean dirty = true;
        boolean building;
        boolean surfaceMesh;
        int vertexCount;
        long lastWanted;

        Tile(int x, int z) {
            this.x = x;
            this.z = z;
        }
    }

    private record MeshResult(int epoch, int tileX, int tileZ, int step, int scan, @Nullable MeshData mesh, ByteBufferBuilder memory) {
        void release() {
            if (mesh != null) {
                mesh.close();
            }
            memory.close();
        }
    }

    static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX & 0xFFFFFFFFL) | ((long) chunkZ << 32);
    }

    private static int stepFor(int radiusChunks) {
        return radiusChunks <= 12 ? 1 : radiusChunks <= 22 ? 2 : 4;
    }

    /** Lets an open map screen show the explored world around the place it looks at, as coarsely as its zoom allows. */
    public void setScreenView(int blockX, int blockZ, int radiusChunks, int meshStep) {
        extraCenterX = blockX >> 4;
        extraCenterZ = blockZ >> 4;
        extraRadius = radiusChunks;
        extraStep = meshStep;
        extraCenterActive = true;
    }

    public void clearScreenView() {
        extraCenterActive = false;
    }

    /** Waits until everything explored is on disk; for when the game is closing. */
    public void saveExploredAndWait() {
        explored.flushAndWait();
    }

    private static java.nio.file.Path exploredDirectory(Minecraft mc, ClientLevel currentLevel) {
        String dimension = currentLevel.dimension().location().toString().replaceAll("[^a-z0-9._-]", "_");
        return net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("isomap").resolve("terrain")
                .resolve(dev.isomap.client.waypoint.WaypointStore.worldKey(mc)).resolve(dimension);
    }

    /** A chunk came back from disk: it joins the map unless something fresher is there. */
    private void addExplored(int chunkX, int chunkZ, ChunkSnapshot snapshot) {
        if (snapshots.putIfAbsent(chunkKey(chunkX, chunkZ), snapshot) == null) {
            markChunkDirty(chunkX, chunkZ);
        }
    }

    public void tick(Minecraft mc) {
        ClientLevel currentLevel = mc.level;
        LocalPlayer player = mc.player;
        if (currentLevel != level) {
            clear();
            level = currentLevel;
            if (currentLevel != null) {
                explored.open(exploredDirectory(mc, currentLevel));
            }
        }
        if (currentLevel == null || player == null) {
            return;
        }
        int radius = IsoMapConfig.RENDER_DISTANCE.getAsInt();
        if (radius != offsetsRadius) {
            rebuildOffsets(radius);
        }
        explored.drainLoaded(this::addExplored);
        int newStep = extraCenterActive ? Math.max(stepFor(radius), extraStep) : stepFor(radius);
        if (newStep != step) {
            step = newStep;
            markAllDirty();
        }
        updateScanHeight(currentLevel, player);
        if (readingLayers != IsoMapConfig.TERRAIN_LAYERS.getAsBoolean()) {
            readingLayers = !readingLayers;
            scanGeneration++;
            cursor = 0;
        }

        int playerChunkX = player.getBlockX() >> 4;
        int playerChunkZ = player.getBlockZ() >> 4;
        if (playerChunkX != lastPlayerChunkX || playerChunkZ != lastPlayerChunkZ) {
            lastPlayerChunkX = playerChunkX;
            lastPlayerChunkZ = playerChunkZ;
            cursor = 0;
        }
        sampleChunks(currentLevel, playerChunkX, playerChunkZ);
        scheduleMeshes(playerChunkX, playerChunkZ, radius);
    }

    private void rebuildOffsets(int radius) {
        offsetsRadius = radius;
        int side = radius * 2 + 1;
        int[] packed = new int[side * side];
        int n = 0;
        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                packed[n++] = ((x * x + z * z) << 16) | ((x + radius) << 8) | (z + radius);
            }
        }
        Arrays.sort(packed);
        offsets = new int[n];
        for (int i = 0; i < n; i++) {
            int x = ((packed[i] >> 8) & 0xFF) - radius;
            int z = (packed[i] & 0xFF) - radius;
            offsets[i] = ((x & 0xFFFF) << 16) | (z & 0xFFFF);
        }
        cursor = 0;
    }

    private void updateScanHeight(ClientLevel currentLevel, LocalPlayer player) {
        boolean ceiling = currentLevel.dimensionType().hasCeiling();
        boolean wantSlice = ceiling || (IsoMapConfig.CAVE_MODE.get() == CaveMode.AUTO && isUnderground(currentLevel, player));
        // Patches of sky light in a cave must not flip the whole map back and forth, so a change has to last a moment.
        if (wantSlice == slicing) {
            slicingVotes = 0;
        } else if (ceiling || ++slicingVotes >= (wantSlice ? SLICE_ENTER_TICKS : SLICE_LEAVE_TICKS)) {
            slicing = wantSlice;
            slicingVotes = 0;
        }

        int sliceHeight = player.getBlockY() + SLICE_ABOVE_PLAYER;
        int wanted;
        if (!slicing) {
            wanted = ChunkSampler.SURFACE;
        } else if (scanFrom != ChunkSampler.SURFACE && scanFrom >= sliceHeight - SLICE_KEEP_BELOW && scanFrom <= sliceHeight + SLICE_KEEP_ABOVE) {
            // Small steps up and down keep the current slice instead of re-reading every chunk.
            wanted = scanFrom;
        } else {
            wanted = sliceHeight + SLICE_RECENTER;
        }
        if (wanted != scanFrom) {
            // Surface and cave data never mix: on a switch everything is hidden and the new view spreads out from the player.
            boolean modeChanged = (wanted == ChunkSampler.SURFACE) != (scanFrom == ChunkSampler.SURFACE);
            scanFrom = wanted;
            if (modeChanged) {
                if (wanted == ChunkSampler.SURFACE) {
                    // Coming up: the explored surface takes back the places the cave view wrote over.
                    explored.forEachChunk((x, z, snapshot) -> {
                        ChunkSnapshot current = snapshots.get(chunkKey(x, z));
                        if (current == null || current.scanFrom != ChunkSampler.SURFACE) {
                            snapshots.put(chunkKey(x, z), snapshot);
                        }
                    });
                }
                markAllDirty();
            }
            scanGeneration++;
            cursor = 0;
        }
    }

    /**
     * Under a roof, or far below the land around the player. The second test is what catches an open shaft or a ravine,
     * where sky light reaches the player although the surface is out of any use for navigating.
     */
    private boolean isUnderground(ClientLevel currentLevel, LocalPlayer player) {
        BlockPos pos = player.blockPosition();
        int column = currentLevel.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ());
        if (pos.getY() < column - UNDERGROUND_MARGIN && currentLevel.getBrightness(LightLayer.SKY, pos) == 0) {
            return true;
        }
        int n = 0;
        for (int dz = -SURROUNDINGS_RADIUS; dz <= SURROUNDINGS_RADIUS; dz += SURROUNDINGS_STEP) {
            for (int dx = -SURROUNDINGS_RADIUS; dx <= SURROUNDINGS_RADIUS; dx += SURROUNDINGS_STEP) {
                surroundingHeights[n++] = currentLevel.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX() + dx, pos.getZ() + dz);
            }
        }
        Arrays.sort(surroundingHeights, 0, n);
        return pos.getY() < surroundingHeights[n / 2] - DEEP_BELOW_SURROUNDINGS;
    }

    /**
     * Typical height of the terrain shown around a position: the median of a small grid, so a shaft or a tree under the
     * player does not throw it off. The map turns around this level, which keeps the terrain under the player's marker
     * at any camera angle, however far above or below the ground the player is.
     */
    public int groundLevel(int blockX, int blockZ, int fallback) {
        int n = 0;
        // A grid fixed to the world: one that moved with every step would change its answer at every step.
        int gridX = Math.floorDiv(blockX, GROUND_STEP) * GROUND_STEP;
        int gridZ = Math.floorDiv(blockZ, GROUND_STEP) * GROUND_STEP;
        for (int dz = -GROUND_RADIUS; dz <= GROUND_RADIUS; dz += GROUND_STEP) {
            for (int dx = -GROUND_RADIUS; dx <= GROUND_RADIUS; dx += GROUND_STEP) {
                int height = groundAt(gridX + dx, gridZ + dz);
                if (height != ChunkSnapshot.NO_DATA) {
                    groundHeights[n++] = height;
                }
            }
        }
        if (n == 0) {
            return fallback;
        }
        Arrays.sort(groundHeights, 0, n);
        return groundHeights[n / 2];
    }

    private void sampleChunks(ClientLevel currentLevel, int centerX, int centerZ) {
        long now = System.nanoTime();
        long deadline = now + SAMPLE_BUDGET_NANOS;
        long refresh = IsoMapConfig.REFRESH_SECONDS.getAsInt() * 1_000_000_000L;
        int count = offsets.length;
        int checks = Math.min(count, MAX_CHUNK_CHECKS_PER_TICK);

        for (int i = 0; i < checks; i++) {
            int packed = offsets[cursor];
            cursor = (cursor + 1) % count;
            int ox = (short) (packed >> 16);
            int oz = (short) packed;
            int cx = centerX + ox;
            int cz = centerZ + oz;
            long key = chunkKey(cx, cz);
            ChunkSnapshot old = snapshots.get(key);

            int ring = Math.max(Math.abs(ox), Math.abs(oz));
            long maxAge = ring <= 2 ? refresh : ring <= 6 ? refresh * 2 : refresh * 4;
            if (old != null && old.scanGeneration == scanGeneration && now - old.sampledAt < maxAge) {
                continue;
            }
            LevelChunk chunk = currentLevel.getChunkSource().getChunkNow(cx, cz);
            if (chunk == null || chunk.isEmpty()) {
                continue;
            }
            long sampleStart = System.nanoTime();
            ChunkSnapshot fresh = ChunkSampler.sample(currentLevel, chunk, scanFrom, scanGeneration, now);
            sampleNanos += System.nanoTime() - sampleStart;
            sampledChunks++;
            snapshots.put(key, fresh);
            if (scanFrom == ChunkSampler.SURFACE) {
                explored.record(cx, cz, fresh);
            } else if (!currentLevel.dimensionType().hasCeiling() && explored.lacksSurface(cx, cz)) {
                // Walking through caves also explores what lies above them. A dimension with a ceiling has no such surface.
                explored.record(cx, cz, ChunkSampler.sample(currentLevel, chunk, ChunkSampler.SURFACE, scanGeneration, now));
            }
            if (old == null || old.contentHash != fresh.contentHash) {
                markChunkDirty(cx, cz);
            }
            if (System.nanoTime() > deadline) {
                break;
            }
        }
    }

    private void markChunkDirty(int chunkX, int chunkZ) {
        // A chunk on a tile's edge also changes the walls of the tile next door.
        int minX = Math.floorDiv(chunkX - 1, TileMesher.TILE_CHUNKS);
        int maxX = Math.floorDiv(chunkX + 1, TileMesher.TILE_CHUNKS);
        int minZ = Math.floorDiv(chunkZ - 1, TileMesher.TILE_CHUNKS);
        int maxZ = Math.floorDiv(chunkZ + 1, TileMesher.TILE_CHUNKS);
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                Tile tile = tiles.get(chunkKey(x, z));
                if (tile != null) {
                    tile.dirty = true;
                }
            }
        }
    }

    private void markAllDirty() {
        for (Tile tile : tiles.values()) {
            tile.dirty = true;
        }
    }

    private void wantTiles(List<Tile> out, int centerChunkX, int centerChunkZ, int radiusChunks, long now) {
        int minX = Math.floorDiv(centerChunkX - radiusChunks, TileMesher.TILE_CHUNKS);
        int maxX = Math.floorDiv(centerChunkX + radiusChunks, TileMesher.TILE_CHUNKS);
        int minZ = Math.floorDiv(centerChunkZ - radiusChunks, TileMesher.TILE_CHUNKS);
        int maxZ = Math.floorDiv(centerChunkZ + radiusChunks, TileMesher.TILE_CHUNKS);
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                long key = chunkKey(x, z);
                Tile tile = tiles.get(key);
                if (tile == null) {
                    tile = new Tile(x, z);
                    tiles.put(key, tile);
                }
                tile.lastWanted = now;
                explored.ensureLoaded(x * TileMesher.TILE_CHUNKS, z * TileMesher.TILE_CHUNKS);
                if (tile.dirty && !tile.building) {
                    // Most of a zoomed-out map is unexplored: such a tile has no reason to take a place in the meshing queue.
                    if (tile.buffer == null && !hasData(tile)) {
                        tile.dirty = false;
                    } else {
                        out.add(tile);
                    }
                }
            }
        }
    }

    private boolean hasData(Tile tile) {
        for (int z = 0; z < TileMesher.TILE_CHUNKS; z++) {
            for (int x = 0; x < TileMesher.TILE_CHUNKS; x++) {
                if (snapshots.containsKey(chunkKey(tile.x * TileMesher.TILE_CHUNKS + x, tile.z * TileMesher.TILE_CHUNKS + z))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void scheduleMeshes(int centerX, int centerZ, int radius) {
        long now = System.nanoTime();
        List<Tile> dirty = new ArrayList<>();
        wantTiles(dirty, centerX, centerZ, radius, now);
        if (extraCenterActive) {
            wantTiles(dirty, extraCenterX, extraCenterZ, Math.max(radius, extraRadius), now);
        }
        if (!dirty.isEmpty() && meshesInFlight.get() < MAX_MESHES_IN_FLIGHT) {
            // What the open map screen looks at comes first, otherwise what is around the player.
            int tileCenterX = Math.floorDiv(extraCenterActive ? extraCenterX : centerX, TileMesher.TILE_CHUNKS);
            int tileCenterZ = Math.floorDiv(extraCenterActive ? extraCenterZ : centerZ, TileMesher.TILE_CHUNKS);
            dirty.sort(Comparator.comparingInt(t -> Math.abs(t.x - tileCenterX) + Math.abs(t.z - tileCenterZ)));
            for (Tile tile : dirty) {
                if (meshesInFlight.get() >= MAX_MESHES_IN_FLIGHT) {
                    break;
                }
                submitMesh(tile);
            }
        }
        if (now - lastTrim > 5_000_000_000L) {
            lastTrim = now;
            trim(now, centerX, centerZ);
        }
        if (now - lastExploredFlush > EXPLORED_FLUSH_NANOS) {
            lastExploredFlush = now;
            explored.flush();
        }
    }

    private void submitMesh(Tile tile) {
        tile.dirty = false;
        tile.building = true;
        meshesInFlight.incrementAndGet();
        int meshEpoch = epoch;
        int meshStep = step;
        int meshScan = scanFrom;
        int x = tile.x;
        int z = tile.z;
        MESH_EXECUTOR.execute(() -> {
            ByteBufferBuilder memory = new ByteBufferBuilder(INITIAL_MESH_BYTES);
            MeshData mesh = null;
            try {
                mesh = new TileMesher(x, z, meshStep, meshScan, snapshots).build(memory);
            } catch (RuntimeException e) {
                IsoMap.LOGGER.error("Failed to mesh map tile {},{}", x, z, e);
            }
            finishedMeshes.add(new MeshResult(meshEpoch, x, z, meshStep, meshScan, mesh, memory));
        });
    }

    /** Uploads a few finished meshes. Called once per frame on the render thread. */
    public void uploadPending() {
        for (int i = 0; i < MAX_UPLOADS_PER_FRAME; i++) {
            MeshResult result = finishedMeshes.poll();
            if (result == null) {
                return;
            }
            meshesInFlight.decrementAndGet();
            Tile tile = result.epoch == epoch ? tiles.get(chunkKey(result.tileX, result.tileZ)) : null;
            if (tile == null) {
                result.release();
                continue;
            }
            tile.building = false;
            if (result.step != step) {
                tile.dirty = true;
            }
            if (result.mesh == null) {
                if (tile.buffer != null) {
                    tile.buffer.close();
                    tile.buffer = null;
                }
                result.memory.close();
                continue;
            }
            if (tile.buffer == null) {
                tile.buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            }
            tile.buffer.bind();
            tile.vertexCount = result.mesh.drawState().vertexCount();
            tile.buffer.upload(result.mesh);
            tile.surfaceMesh = result.scan == ChunkSampler.SURFACE;
            VertexBuffer.unbind();
            result.memory.close();
        }
    }

    private void trim(long now, int centerX, int centerZ) {
        var iterator = tiles.long2ObjectEntrySet().iterator();
        while (iterator.hasNext()) {
            Tile tile = iterator.next().getValue();
            if (!tile.building && now - tile.lastWanted > TILE_IDLE_NANOS) {
                if (tile.buffer != null) {
                    tile.buffer.close();
                }
                iterator.remove();
            }
        }
        int keepRadius = (int) Math.sqrt(MAX_SNAPSHOTS) / 2;
        int otherX = extraCenterActive ? extraCenterX : centerX;
        int otherZ = extraCenterActive ? extraCenterZ : centerZ;
        explored.evictFar(centerX, centerZ, otherX, otherZ, keepRadius);
        if (snapshots.size() > MAX_SNAPSHOTS) {
            snapshots.keySet().removeIf(key -> {
                int x = (int) (long) key;
                int z = (int) (key >> 32);
                return Math.max(Math.abs(x - centerX), Math.abs(z - centerZ)) > keepRadius
                        && Math.max(Math.abs(x - otherX), Math.abs(z - otherZ)) > keepRadius;
            });
        }
    }

    /** Visits every tile that has an uploaded mesh. */
    public void forEachBuffer(TileVisitor visitor) {
        for (Tile tile : tiles.values()) {
            // A mesh of the other view may wait long for its rebuild, for instance after a teleport; it must not show meanwhile.
            if (tile.buffer != null && tile.surfaceMesh == (scanFrom == ChunkSampler.SURFACE)) {
                visitor.visit(tile.buffer, tile.x * TileMesher.TILE_BLOCKS, tile.z * TileMesher.TILE_BLOCKS, TileMesher.TILE_BLOCKS);
            }
        }
    }

    @FunctionalInterface
    public interface TileVisitor {
        void visit(VertexBuffer buffer, int originBlockX, int originBlockZ, int sizeBlocks);
    }

    /** Height of the column at a block position, or {@link ChunkSnapshot#NO_DATA} if it has not been seen. */
    public int heightAt(int blockX, int blockZ) {
        ChunkSnapshot snapshot = snapshots.get(chunkKey(blockX >> 4, blockZ >> 4));
        return snapshot == null || !snapshot.isShownFor(scanFrom) ? ChunkSnapshot.NO_DATA : snapshot.heights[ChunkSnapshot.index(blockX & 15, blockZ & 15)];
    }

    /** Height of the ground in a column, under any crown or floating block; {@link ChunkSnapshot#NO_DATA} if it has not been seen. */
    public int groundAt(int blockX, int blockZ) {
        ChunkSnapshot snapshot = snapshots.get(chunkKey(blockX >> 4, blockZ >> 4));
        int column = ChunkSnapshot.index(blockX & 15, blockZ & 15);
        return snapshot == null || !snapshot.isShownFor(scanFrom) || snapshot.heights[column] == ChunkSnapshot.NO_DATA
                ? ChunkSnapshot.NO_DATA : snapshot.groundHeight(column);
    }

    /** How many separate layers a column has; 0 if it has not been seen. */
    public int layersAt(int blockX, int blockZ) {
        ChunkSnapshot snapshot = snapshots.get(chunkKey(blockX >> 4, blockZ >> 4));
        int column = ChunkSnapshot.index(blockX & 15, blockZ & 15);
        return snapshot == null || !snapshot.isShownFor(scanFrom) || snapshot.heights[column] == ChunkSnapshot.NO_DATA ? 0 : snapshot.layerCount(column);
    }

    /** Vertices of all uploaded meshes; lets the QA harness weigh the cost of layers. */
    public long meshVertexCount() {
        long total = 0L;
        for (Tile tile : tiles.values()) {
            if (tile.buffer != null) {
                total += tile.vertexCount;
            }
        }
        return total;
    }

    /** Average cost of reading one chunk in nanoseconds since the last call; for the QA harness. */
    public long takeAverageSampleNanos() {
        long average = sampledChunks == 0 ? 0L : sampleNanos / sampledChunks;
        sampleNanos = 0L;
        sampledChunks = 0;
        return average;
    }

    /** Forgets all terrain and frees every GPU buffer. Late meshes are released when they arrive. */
    public void clear() {
        explored.close();
        epoch++;
        for (Tile tile : tiles.values()) {
            if (tile.buffer != null) {
                tile.buffer.close();
            }
        }
        tiles.clear();
        snapshots.clear();
        MeshResult result;
        while ((result = finishedMeshes.poll()) != null) {
            meshesInFlight.decrementAndGet();
            result.release();
        }
        cursor = 0;
        scanFrom = ChunkSampler.SURFACE;
        slicing = false;
        slicingVotes = 0;
        lastPlayerChunkX = Integer.MIN_VALUE;
        lastPlayerChunkZ = Integer.MIN_VALUE;
    }

    public boolean isSlicing() {
        return scanFrom != ChunkSampler.SURFACE;
    }

    /** Top of the cave cut; nothing higher is drawn while slicing. */
    public int sliceTop() {
        return scanFrom + 1;
    }

    public int snapshotCount() {
        return snapshots.size();
    }

    public int gpuBufferCount() {
        int withBuffer = 0;
        for (Tile tile : tiles.values()) {
            if (tile.buffer != null) {
                withBuffer++;
            }
        }
        return withBuffer;
    }
}
