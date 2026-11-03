package dev.isomap.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.isomap.IsoMap;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import net.neoforged.fml.loading.FMLPaths;

/**
 * All persistent settings, stored in {@code config/isomap-client.json}; every value is editable in game.
 *
 * <p>This is deliberately not a {@code ModConfigSpec}. There, each save makes a watcher thread re-read the file a moment
 * later and swap the whole config, so a value changed in between silently reverts. Settings here change many times a second
 * from sliders and from the camera, so the values live in memory and the file is only ever written. Editing the file by
 * hand takes effect on the next launch.
 */
public final class IsoMapConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<Setting<?>> ALL = new ArrayList<>();
    private static boolean dirty;

    // General
    public static final Setting.Bool ENABLED = bool("general.enabled", true);
    public static final Setting.Bool SHOW_COORDS = bool("general.showCoords", true);
    public static final Setting.Bool SHOW_BIOME = bool("general.showBiome", false);
    public static final Setting.Bool SHOW_COMPASS = bool("general.showCompass", true);
    public static final Setting.Bool HIDE_WITH_DEBUG = bool("general.hideWithDebugScreen", true);
    public static final Setting.Bool UI_ANIMATIONS = bool("general.uiAnimations", true);

    // Minimap
    public static final Setting.Int SIZE = range("minimap.size", 132, 64, 320);
    public static final Setting.Choice<ScreenAnchor> ANCHOR = choice("minimap.anchor", ScreenAnchor.TOP_RIGHT);
    public static final Setting.Int OFFSET_X = range("minimap.offsetX", 6, 0, 500);
    public static final Setting.Int OFFSET_Y = range("minimap.offsetY", 6, 0, 500);
    public static final Setting.Int OPACITY = range("minimap.opacity", 100, 10, 100);
    public static final Setting.Int BACKGROUND_OPACITY = range("minimap.backgroundOpacity", 45, 0, 100);
    public static final Setting.Bool RENDER_3D = bool("minimap.render3d", true);
    public static final Setting.Bool TERRAIN_LAYERS = bool("minimap.terrainLayers", true);
    public static final Setting.Int RENDER_DISTANCE = range("minimap.renderDistanceChunks", 8, 2, 32);
    public static final Setting.Int UPDATE_FPS = range("minimap.updateFps", 30, 5, 60);
    public static final Setting.Int REFRESH_SECONDS = range("minimap.terrainRefreshSeconds", 2, 1, 30);
    public static final Setting.Int FOLLOW_SMOOTHING = range("minimap.followSmoothing", 35, 0, 100);
    public static final Setting.Decimal HEIGHT_SCALE = decimal("minimap.heightScale", 1.0, 0.25, 2.0);
    public static final Setting.Bool SUPERSAMPLE = bool("minimap.supersample", true);
    public static final Setting.Choice<CaveMode> CAVE_MODE = choice("minimap.caveMode", CaveMode.AUTO);

    // Camera
    public static final Setting.Decimal SENSITIVITY = decimal("camera.sensitivity", 1.0, 0.1, 3.0);
    public static final Setting.Bool INVERT_YAW = bool("camera.invertYaw", false);
    public static final Setting.Bool INVERT_PITCH = bool("camera.invertPitch", false);
    public static final Setting.Int PITCH_MIN = range("camera.pitchMin", 25, 15, 90);
    public static final Setting.Int PITCH_MAX = range("camera.pitchMax", 90, 15, 90);
    public static final Setting.Int YAW_LIMIT = range("camera.yawLimit", 180, 0, 180);
    public static final Setting.Int AUTO_RETURN_SECONDS = range("camera.autoReturnSeconds", 0, 0, 30);

    // Camera state remembered between sessions; not listed in any settings tab
    public static final Setting.Decimal STATE_ZOOM = decimal("state.zoom", 1.5, 0.25, 8.0);
    public static final Setting.Decimal STATE_PITCH = decimal("state.pitch", 55.0, 15.0, 90.0);
    public static final Setting.Decimal STATE_YAW = decimal("state.yawOffset", 0.0, -180.0, 180.0);

    // Waypoints
    public static final Setting.Bool SHOW_WAYPOINTS = bool("waypoints.showOnMap", true);
    public static final Setting.Choice<LabelMode> LABEL_MODE = choice("waypoints.labels", LabelMode.CLOSE);
    public static final Setting.Bool SHOW_DISTANCE = bool("waypoints.showDistance", true);
    public static final Setting.Bool EDGE_MARKERS = bool("waypoints.edgeMarkers", true);
    public static final Setting.Int MARKER_SIZE = range("waypoints.markerSize", 5, 3, 9);
    public static final Setting.Bool DEATH_ENABLED = bool("waypoints.deathMarkers", true);
    public static final Setting.Bool DEATH_VISIBLE = bool("waypoints.deathMarkersVisible", true);
    public static final Setting.Bool DEATH_CHAT = bool("waypoints.deathChatMessage", true);
    public static final Setting.Int DEATH_MAX = range("waypoints.deathMarkersMax", 20, 1, 100);

    // Pings
    public static final Setting.Bool PINGS_ENABLED = bool("pings.enabled", true);
    public static final Setting.Bool PINGS_THROUGH_CHAT = bool("pings.throughChat", false);
    public static final Setting.Int PING_SECONDS = range("pings.durationSeconds", 20, 5, 120);

    // Sounds
    public static final Setting.Bool SOUND_ENABLED = bool("sounds.enabled", true);
    public static final Setting.Int SOUND_VOLUME = range("sounds.volume", 60, 0, 100);
    public static final Setting.Bool SOUND_MENU = bool("sounds.menu", true);
    public static final Setting.Bool SOUND_HOVER = bool("sounds.hover", true);
    public static final Setting.Bool SOUND_CLICK = bool("sounds.click", true);
    public static final Setting.Bool SOUND_TOGGLE = bool("sounds.toggle", true);
    public static final Setting.Bool SOUND_WAYPOINT = bool("sounds.waypoint", true);
    public static final Setting.Bool SOUND_PING = bool("sounds.ping", true);

    // Controls
    public static final Setting.Bool WHEEL_ZOOM = bool("controls.wheelZoomWhileHoldingCameraKey", true);

    private IsoMapConfig() {}

    private static Setting.Bool bool(String path, boolean def) {
        return reg(new Setting.Bool(path, def));
    }

    private static Setting.Int range(String path, int def, int min, int max) {
        return reg(new Setting.Int(path, def, min, max));
    }

    private static Setting.Decimal decimal(String path, double def, double min, double max) {
        return reg(new Setting.Decimal(path, def, min, max));
    }

    private static <E extends Enum<E>> Setting.Choice<E> choice(String path, E def) {
        return reg(new Setting.Choice<>(path, def));
    }

    private static <T extends Setting<?>> T reg(T setting) {
        ALL.add(setting);
        return setting;
    }

    public static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("isomap-client.json");
    }

    static void markDirty() {
        dirty = true;
    }

    public static void resetAll() {
        ALL.forEach(Setting::reset);
        save();
    }

    /** Reads the file. A missing or damaged file means defaults; a damaged one is kept aside for recovery. */
    public static void load() {
        Path path = file();
        if (!Files.exists(path)) {
            return;
        }
        JsonObject json;
        try {
            json = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | JsonParseException | IllegalStateException e) {
            IsoMap.LOGGER.error("Could not read settings from {}", path, e);
            setAside(path);
            ALL.forEach(setting -> setting.read(com.google.gson.JsonNull.INSTANCE));
            return;
        }
        for (Setting<?> setting : ALL) {
            JsonElement value = json.get(setting.path());
            setting.read(value == null ? com.google.gson.JsonNull.INSTANCE : value);
        }
        dirty = false;
        // The removed heading-up mode left its offset relative to the player's view; on its own it would just turn the map.
        if (json.has("camera.mode")) {
            STATE_YAW.reset();
        }
    }

    private static void setAside(Path path) {
        try {
            Files.move(path, path.resolveSibling(path.getFileName() + ".corrupt-" + System.currentTimeMillis()));
        } catch (IOException e) {
            IsoMap.LOGGER.error("Could not set aside unreadable settings file {}", path, e);
        }
    }

    /** Writes the file if anything changed since the last write. */
    public static void save() {
        if (!dirty) {
            return;
        }
        dirty = false;
        JsonObject json = new JsonObject();
        for (Setting<?> setting : ALL) {
            json.add(setting.path(), setting.write());
        }
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(json), StandardCharsets.UTF_8);
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            IsoMap.LOGGER.error("Could not save settings to {}", path, e);
        }
    }
}
