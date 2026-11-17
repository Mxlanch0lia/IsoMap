package dev.isomap.client.waypoint;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.isomap.IsoMap;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import dev.isomap.platform.Platform;

/**
 * Waypoints of the current world or server, one JSON file per world in {@code <game dir>/isomap/worlds}.
 * Every change is written at once through a temp file and an atomic rename, keeping the previous version as {@code .bak},
 * so a crash cannot lose more than the change in flight.
 */
public final class WaypointStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final WaypointStore INSTANCE = new WaypointStore();

    private final List<Waypoint> waypoints = new ArrayList<>();
    private final List<Waypoint> readOnlyView = Collections.unmodifiableList(waypoints);
    @Nullable
    private Path file;
    private int revision;

    private static final class FileData {
        int version = 1;
        List<Waypoint> waypoints = new ArrayList<>();
    }

    public static WaypointStore get() {
        return INSTANCE;
    }

    /** Stable file-system key for the world being played. */
    public static String worldKey(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server != null) {
            Path folder = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
            return key("sp_", folder == null ? "world" : folder.toString());
        }
        ServerData data = mc.getCurrentServer();
        return key("mp_", data == null ? "unknown" : data.ip.toLowerCase(Locale.ROOT));
    }

    private static String key(String prefix, String name) {
        String safe = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        if (safe.length() > 48) {
            safe = safe.substring(0, 48);
        }
        // The hash keeps names apart once sanitised: world folders may be entirely non-ASCII.
        return prefix + safe + "_" + Integer.toHexString(name.hashCode());
    }

    public void open(String worldKey) {
        close();
        file = Platform.get().gameDir().resolve("isomap").resolve("worlds").resolve(worldKey + ".json");
        load(file);
        revision++;
    }

    public void close() {
        waypoints.clear();
        file = null;
        revision++;
    }

    public boolean isOpen() {
        return file != null;
    }

    @Nullable
    public Path file() {
        return file;
    }

    /** Re-reads the file, discarding memory state. */
    public void reload() {
        if (file != null) {
            waypoints.clear();
            load(file);
            revision++;
        }
    }

    private void load(Path path) {
        Path backup = sibling(path, ".bak");
        if (Files.exists(path)) {
            if (read(path)) {
                return;
            }
            // Keep the unreadable file for manual recovery instead of overwriting it on the next save.
            quarantine(path);
        }
        if (Files.exists(backup) && read(backup)) {
            IsoMap.LOGGER.warn("Restored waypoints from backup {}", backup);
        }
    }

    private boolean read(Path path) {
        try {
            FileData data = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), FileData.class);
            if (data == null || data.waypoints == null) {
                return false;
            }
            for (Waypoint w : data.waypoints) {
                if (w != null && w.sanitize()) {
                    waypoints.add(w);
                }
            }
            return true;
        } catch (IOException | JsonParseException e) {
            IsoMap.LOGGER.error("Could not read waypoints from {}", path, e);
            return false;
        }
    }

    private static void quarantine(Path path) {
        try {
            Files.move(path, sibling(path, ".corrupt-" + System.currentTimeMillis()));
        } catch (IOException e) {
            IsoMap.LOGGER.error("Could not set aside unreadable waypoint file {}", path, e);
        }
    }

    private void save() {
        revision++;
        if (file == null) {
            return;
        }
        FileData data = new FileData();
        data.waypoints = waypoints;
        try {
            Files.createDirectories(file.getParent());
            Path temp = sibling(file, ".tmp");
            Files.writeString(temp, GSON.toJson(data), StandardCharsets.UTF_8);
            if (Files.exists(file)) {
                Files.copy(file, sibling(file, ".bak"), StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            IsoMap.LOGGER.error("Could not save waypoints to {}", file, e);
        }
    }

    private static Path sibling(Path path, String suffix) {
        return path.resolveSibling(path.getFileName() + suffix);
    }

    public List<Waypoint> all() {
        return readOnlyView;
    }

    /** Grows on every change; lets screens and renderers rebuild derived lists only when needed. */
    public int revision() {
        return revision;
    }

    @Nullable
    public Waypoint byId(String id) {
        for (Waypoint w : waypoints) {
            if (w.id.equals(id)) {
                return w;
            }
        }
        return null;
    }

    public void add(Waypoint waypoint) {
        waypoints.add(waypoint);
        save();
    }

    /** Stores an edited copy over the entry with the same id, or adds it if that entry is gone. */
    public void put(Waypoint edited) {
        for (int i = 0; i < waypoints.size(); i++) {
            if (waypoints.get(i).id.equals(edited.id)) {
                waypoints.set(i, edited);
                save();
                return;
            }
        }
        add(edited);
    }

    public void remove(Waypoint waypoint) {
        if (waypoints.removeIf(w -> w.id.equals(waypoint.id))) {
            save();
        }
    }

    public void setVisible(Waypoint waypoint, boolean visible) {
        waypoint.visible = visible;
        save();
    }

    public TreeSet<String> allTags() {
        TreeSet<String> tags = new TreeSet<>();
        for (Waypoint w : waypoints) {
            tags.addAll(w.tags);
        }
        return tags;
    }

    /** Removes the oldest death markers above the configured limit. */
    public void trimDeathMarkers(int max) {
        List<Waypoint> deaths = new ArrayList<>(waypoints.stream().filter(w -> w.death).toList());
        if (deaths.size() <= max) {
            return;
        }
        deaths.sort(Comparator.comparingLong(w -> w.createdAt));
        waypoints.removeAll(deaths.subList(0, deaths.size() - max));
        save();
    }
}
