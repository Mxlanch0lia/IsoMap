package dev.isomap.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

public final class KeyBindings {
    private static final String CATEGORY = "key.categories.isomap";

    public static final KeyMapping OPEN_MAP = key("open_map", GLFW.GLFW_KEY_M);
    public static final KeyMapping OPEN_SETTINGS = key("open_settings", GLFW.GLFW_KEY_K);
    public static final KeyMapping OPEN_WAYPOINTS = key("open_waypoints", GLFW.GLFW_KEY_J);
    public static final KeyMapping CREATE_WAYPOINT = key("create_waypoint", GLFW.GLFW_KEY_B);
    public static final KeyMapping TOGGLE_VISIBLE = key("toggle_visible", GLFW.GLFW_KEY_N);
    public static final KeyMapping PING = key("ping", GLFW.GLFW_KEY_G);
    public static final KeyMapping ZOOM_IN = key("zoom_in", GLFW.GLFW_KEY_EQUAL);
    public static final KeyMapping ZOOM_OUT = key("zoom_out", GLFW.GLFW_KEY_MINUS);
    public static final KeyMapping CAMERA_HOLD = key("camera_hold", GLFW.GLFW_KEY_LEFT_ALT);
    public static final KeyMapping TOP_VIEW = key("top_view", GLFW.GLFW_KEY_Y);
    public static final KeyMapping CAMERA_RESET = key("camera_reset", GLFW.GLFW_KEY_UNKNOWN);

    public static final List<KeyMapping> ALL = List.of(
            OPEN_MAP, OPEN_SETTINGS, OPEN_WAYPOINTS, CREATE_WAYPOINT, TOGGLE_VISIBLE, PING,
            ZOOM_IN, ZOOM_OUT, CAMERA_HOLD, TOP_VIEW, CAMERA_RESET);

    private KeyBindings() {}

    private static KeyMapping key(String name, int code) {
        return new KeyMapping("key.isomap." + name, KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, code, CATEGORY);
    }

    public static void register(RegisterKeyMappingsEvent event) {
        ALL.forEach(event::register);
    }
}
