package dev.isomap.client.map;

import dev.isomap.IsoMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.annotation.Nullable;

/**
 * The surface of every chunk the player has seen, kept on disk so the full map remembers it between sessions.
 *
 * <p>One compressed file per 32x32 chunks and per dimension. Files are read and written on one background thread, in the
 * order requested, so a read that follows a write always sees it. All methods run on the client thread.
 */
final class ExploredStore {
    private static final int REGION_CHUNKS = 32;
    private static final int REGION_SLOTS = REGION_CHUNKS * REGION_CHUNKS;
    private static final int MAGIC = 0x49534D50;
    private static final int FORMAT_VERSION = 2;
    private static final int FIRST_READABLE_VERSION = 1;
    private static final int LAYERS_VERSION = 2;
    private static final int COLUMNS = 256;
    private static final int GENERATION_FROM_DISK = -1;
    private static final long SHUTDOWN_WAIT_SECONDS = 10L;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "IsoMap explored map IO");
        thread.setDaemon(true);
        return thread;
    });

    private final Long2ObjectMap<Region> regions = new Long2ObjectOpenHashMap<>();
    private final Queue<LoadedRegion> loaded = new ConcurrentLinkedQueue<>();
    @Nullable
    private Path directory;
    /** Bumped on every close, so reads started for the previous dimension are thrown away. */
    private int session;

    private static final class Region {
        final int x;
        final int z;
        final ChunkSnapshot[] chunks = new ChunkSnapshot[REGION_SLOTS];
        boolean ready;
        boolean dirty;

        Region(int x, int z) {
            this.x = x;
            this.z = z;
        }
    }

    private record LoadedRegion(int session, int x, int z, ChunkSnapshot[] chunks) {}

    @FunctionalInterface
    interface ChunkVisitor {
        void visit(int chunkX, int chunkZ, ChunkSnapshot snapshot);
    }

    private static int slot(int chunkX, int chunkZ) {
        return ((chunkZ & (REGION_CHUNKS - 1)) << 5) | (chunkX & (REGION_CHUNKS - 1));
    }

    private static int regionOf(int chunk) {
        return chunk >> 5;
    }

    void open(Path dir) {
        close();
        directory = dir;
    }

    /** Hands everything unsaved to the writer and forgets the dimension. */
    void close() {
        flush();
        regions.clear();
        loaded.clear();
        directory = null;
        session++;
    }

    private Region region(int chunkX, int chunkZ) {
        int rx = regionOf(chunkX);
        int rz = regionOf(chunkZ);
        long key = TerrainCache.chunkKey(rx, rz);
        Region region = regions.get(key);
        if (region == null) {
            region = new Region(rx, rz);
            regions.put(key, region);
            requestRead(region);
        }
        return region;
    }

    private void requestRead(Region region) {
        Path file = directory == null ? null : regionFile(directory, region.x, region.z);
        if (file == null) {
            region.ready = true;
            return;
        }
        int requestSession = session;
        int x = region.x;
        int z = region.z;
        IO.execute(() -> loaded.add(new LoadedRegion(requestSession, x, z, read(file))));
    }

    /** Makes sure the saved chunks around this position are on their way into memory. */
    void ensureLoaded(int chunkX, int chunkZ) {
        region(chunkX, chunkZ);
    }

    void record(int chunkX, int chunkZ, ChunkSnapshot snapshot) {
        Region region = region(chunkX, chunkZ);
        int slot = slot(chunkX, chunkZ);
        ChunkSnapshot old = region.chunks[slot];
        region.chunks[slot] = snapshot;
        if (old == null || old.contentHash != snapshot.contentHash) {
            region.dirty = true;
        }
    }

    /** Whether nothing is known yet about this chunk's surface. An unfinished read counts as known, for now. */
    boolean lacksSurface(int chunkX, int chunkZ) {
        Region region = region(chunkX, chunkZ);
        return region.ready && region.chunks[slot(chunkX, chunkZ)] == null;
    }

    /** Takes in finished reads; what was seen since the read started is newer and stays. */
    void drainLoaded(ChunkVisitor visitor) {
        LoadedRegion result;
        while ((result = loaded.poll()) != null) {
            if (result.session != session) {
                continue;
            }
            Region region = regions.get(TerrainCache.chunkKey(result.x, result.z));
            if (region == null) {
                continue;
            }
            region.ready = true;
            for (int slot = 0; slot < REGION_SLOTS; slot++) {
                ChunkSnapshot snapshot = result.chunks[slot];
                if (snapshot != null && region.chunks[slot] == null) {
                    region.chunks[slot] = snapshot;
                    visitor.visit(result.x * REGION_CHUNKS + (slot & (REGION_CHUNKS - 1)), result.z * REGION_CHUNKS + (slot >> 5), snapshot);
                }
            }
        }
    }

    void forEachChunk(ChunkVisitor visitor) {
        for (Region region : regions.values()) {
            for (int slot = 0; slot < REGION_SLOTS; slot++) {
                ChunkSnapshot snapshot = region.chunks[slot];
                if (snapshot != null) {
                    visitor.visit(region.x * REGION_CHUNKS + (slot & (REGION_CHUNKS - 1)), region.z * REGION_CHUNKS + (slot >> 5), snapshot);
                }
            }
        }
    }

    /** Queues a write of every changed region. */
    void flush() {
        if (directory == null) {
            return;
        }
        for (Region region : regions.values()) {
            if (region.dirty) {
                region.dirty = false;
                Path file = regionFile(directory, region.x, region.z);
                ChunkSnapshot[] copy = region.chunks.clone();
                // A region still being read has not seen the file's chunks yet, so the writer merges them.
                boolean merge = !region.ready;
                IO.execute(() -> write(file, copy, merge));
            }
        }
    }

    /** Frees regions far from both centres. They are on disk and come back when wanted. */
    void evictFar(int centerX, int centerZ, int otherX, int otherZ, int keepChunks) {
        var iterator = regions.long2ObjectEntrySet().iterator();
        while (iterator.hasNext()) {
            Region region = iterator.next().getValue();
            int cx = region.x * REGION_CHUNKS + REGION_CHUNKS / 2;
            int cz = region.z * REGION_CHUNKS + REGION_CHUNKS / 2;
            boolean nearFirst = Math.max(Math.abs(cx - centerX), Math.abs(cz - centerZ)) <= keepChunks;
            boolean nearSecond = Math.max(Math.abs(cx - otherX), Math.abs(cz - otherZ)) <= keepChunks;
            if (!region.dirty && region.ready && !nearFirst && !nearSecond) {
                iterator.remove();
            }
        }
    }

    /** Saves what is pending and waits for the writer: the JVM is about to exit and its thread would die with it. */
    void flushAndWait() {
        flush();
        try {
            IO.submit(() -> {}).get(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            IsoMap.LOGGER.warn("Explored map may not be fully saved", e);
        }
    }

    private static Path regionFile(Path dir, int regionX, int regionZ) {
        return dir.resolve("r." + regionX + "." + regionZ + ".bin");
    }

    private static ChunkSnapshot[] read(Path file) {
        ChunkSnapshot[] chunks = new ChunkSnapshot[REGION_SLOTS];
        if (!Files.exists(file)) {
            return chunks;
        }
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new BufferedInputStream(Files.newInputStream(file))))) {
            int magic = in.readInt();
            int version = in.readInt();
            if (magic != MAGIC || version < FIRST_READABLE_VERSION || version > FORMAT_VERSION) {
                throw new IOException("unknown format");
            }
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                int slot = in.readShort();
                short[] heights = new short[COLUMNS];
                int[] colors = new int[COLUMNS];
                for (int c = 0; c < COLUMNS; c++) {
                    heights[c] = in.readShort();
                }
                for (int c = 0; c < COLUMNS; c++) {
                    colors[c] = in.readInt();
                }
                // Chunks saved by older versions have no layers until the player sees them again.
                ChunkSnapshot.Layers layers = version >= LAYERS_VERSION && in.readBoolean() ? readLayers(in) : null;
                chunks[slot] = new ChunkSnapshot(heights, colors, layers, ChunkSampler.SURFACE, GENERATION_FROM_DISK, 0L);
            }
        } catch (IOException | RuntimeException e) {
            // A damaged file only costs that piece of the map, and it is kept in case it can be recovered.
            IsoMap.LOGGER.error("Could not read explored map {}", file, e);
            setAside(file);
            return new ChunkSnapshot[REGION_SLOTS];
        }
        return chunks;
    }

    private static ChunkSnapshot.Layers readLayers(DataInputStream in) throws IOException {
        short[] topBottoms = readShorts(in, COLUMNS);
        short[] start = readShorts(in, COLUMNS + 1);
        int lower = in.readShort();
        short[] tops = readShorts(in, lower);
        short[] bottoms = readShorts(in, lower);
        int[] colors = new int[lower];
        for (int i = 0; i < lower; i++) {
            colors[i] = in.readInt();
        }
        return new ChunkSnapshot.Layers(topBottoms, start, tops, bottoms, colors);
    }

    private static short[] readShorts(DataInputStream in, int count) throws IOException {
        short[] values = new short[count];
        for (int i = 0; i < count; i++) {
            values[i] = in.readShort();
        }
        return values;
    }

    private static void writeLayers(DataOutputStream out, ChunkSnapshot.Layers layers) throws IOException {
        writeShorts(out, layers.topBottoms());
        writeShorts(out, layers.start());
        out.writeShort(layers.tops().length);
        writeShorts(out, layers.tops());
        writeShorts(out, layers.bottoms());
        for (int color : layers.colors()) {
            out.writeInt(color);
        }
    }

    private static void writeShorts(DataOutputStream out, short[] values) throws IOException {
        for (short value : values) {
            out.writeShort(value);
        }
    }

    private static void setAside(Path file) {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis()));
        } catch (IOException e) {
            IsoMap.LOGGER.error("Could not set aside {}", file, e);
        }
    }

    private static void write(Path file, ChunkSnapshot[] chunks, boolean merge) {
        try {
            if (merge) {
                ChunkSnapshot[] existing = read(file);
                for (int slot = 0; slot < REGION_SLOTS; slot++) {
                    if (chunks[slot] == null) {
                        chunks[slot] = existing[slot];
                    }
                }
            }
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            int count = 0;
            for (ChunkSnapshot snapshot : chunks) {
                if (snapshot != null) {
                    count++;
                }
            }
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(temp))))) {
                out.writeInt(MAGIC);
                out.writeInt(FORMAT_VERSION);
                out.writeInt(count);
                for (int slot = 0; slot < REGION_SLOTS; slot++) {
                    ChunkSnapshot snapshot = chunks[slot];
                    if (snapshot != null) {
                        out.writeShort(slot);
                        for (short height : snapshot.heights) {
                            out.writeShort(height);
                        }
                        for (int color : snapshot.colors) {
                            out.writeInt(color);
                        }
                        out.writeBoolean(snapshot.layers != null);
                        if (snapshot.layers != null) {
                            writeLayers(out, snapshot.layers);
                        }
                    }
                }
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            IsoMap.LOGGER.error("Could not save explored map {}", file, e);
        }
    }
}
