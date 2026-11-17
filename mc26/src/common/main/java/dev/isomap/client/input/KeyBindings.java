package dev.isomap.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import net.minecraft.client.KeyMapping;

public final class KeyBindings {
    /** Each loader registers it, so the keys get their own heading in the controls screen. */
    public static final KeyMapping.Category CATEGORY = new KeyMapping.Category(dev.isomap.IsoMap.id("main"));

    public static final KeyMapping OPEN_MAP = key("open_map", InputConstants.KEY_M);
    public static final KeyMapping OPEN_SETTINGS = key("open_settings", InputConstants.KEY_K);
    public static final KeyMapping OPEN_WAYPOINTS = key("open_waypoints", InputConstants.KEY_J);
    public static final KeyMapping CREATE_WAYPOINT = key("create_waypoint", InputConstants.KEY_B);
    public static final KeyMapping TOGGLE_VISIBLE = key("toggle_visible", InputConstants.KEY_N);
    public static final KeyMapping PING = key("ping", InputConstants.KEY_G);
    public static final KeyMapping ZOOM_IN = key("zoom_in", InputConstants.KEY_EQUALS);
    public static final KeyMapping ZOOM_OUT = key("zoom_out", InputConstants.KEY_MINUS);
    public static final KeyMapping CAMERA_HOLD = key("camera_hold", InputConstants.KEY_LALT);
    public static final KeyMapping TOP_VIEW = key("top_view", InputConstants.KEY_Y);
    public static final KeyMapping CAMERA_RESET = key("camera_reset", InputConstants.UNKNOWN.getValue());

    public static final List<KeyMapping> ALL = List.of(
            OPEN_MAP, OPEN_SETTINGS, OPEN_WAYPOINTS, CREATE_WAYPOINT, TOGGLE_VISIBLE, PING,
            ZOOM_IN, ZOOM_OUT, CAMERA_HOLD, TOP_VIEW, CAMERA_RESET);

    private KeyBindings() {}

    private static KeyMapping key(String name, int code) {
        return new KeyMapping("key.isomap." + name, code, CATEGORY);
    }
}
