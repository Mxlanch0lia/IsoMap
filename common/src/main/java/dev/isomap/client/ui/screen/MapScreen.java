package dev.isomap.client.ui.screen;

import dev.isomap.client.input.KeyBindings;
import dev.isomap.client.ping.PingClient;
import dev.isomap.client.render.CameraAngles;
import dev.isomap.client.render.MapRuntime;
import dev.isomap.client.render.MapView;
import dev.isomap.client.render.MarkerRenderer;
import dev.isomap.client.ui.Theme;
import dev.isomap.client.ui.widget.IsoButton;
import dev.isomap.client.waypoint.Dimensions;
import dev.isomap.client.waypoint.Waypoint;
import dev.isomap.client.waypoint.WaypointTeleport;
import dev.isomap.config.IsoMapConfig;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * The large interactive map with the mouse free. Left drag turns and tilts the camera (the same angles the HUD map uses),
 * right drag pans, the wheel zooms, a click opens a waypoint, a double click creates one, and a right click opens a small
 * menu for the place under the cursor.
 */
public class MapScreen extends IsoScreen {
    private static final int MARGIN = 6;
    private static final int TOOLBAR_HEIGHT = 16;
    private static final int STATUS_HEIGHT = 12;
    private static final int BUTTON_PADDING = 14;
    private static final float COMPASS_INSIDE = -6f;
    private static final int MAX_VIEW_RADIUS_CHUNKS = 64;
    private static final double FINE_MESH_BLOCKS_PER_UNIT = 1.4;
    private static final double MEDIUM_MESH_BLOCKS_PER_UNIT = 2.6;
    private static final int MAP_BACKGROUND = 0xF00E1013;
    private static final double WHEEL_FACTOR = 1.15;
    private static final double CLICK_SLOP = 3.0;
    private static final long DOUBLE_CLICK_MS = 300L;
    private static final double MIN_PAN_SINE = 0.3;
    private static final int MENU_PADDING = 3;
    private static final int MENU_TITLE_HEIGHT = 16;
    private static final int MENU_ROW = 14;

    private final MapView view = new MapView(MapRuntime.hudView().zoomTarget());
    private int mapX;
    private int mapY;
    private int mapW;
    private int mapH;
    private int dragButton = -1;
    private double dragDistance;
    private long lastClickAt;
    @Nullable
    private Waypoint hovered;
    @Nullable
    private BlockPos menuTarget;
    private String menuTitle = "";
    private int menuX;
    private int menuY;
    private int menuW;
    private int menuH;
    private final List<IsoButton> menuButtons = new ArrayList<>();

    public MapScreen(@Nullable Screen parent) {
        super(Component.translatable("isomap.map.title"), parent);
    }

    @Override
    protected void init() {
        super.init();
        // A resize rebuilds every widget, the menu's buttons among them.
        menuButtons.clear();
        menuTarget = null;
        mapX = MARGIN;
        mapY = MARGIN + TOOLBAR_HEIGHT + 3;
        mapW = width - MARGIN * 2;
        mapH = height - mapY - MARGIN - STATUS_HEIGHT;
        MapRuntime.attachScreenView(view, mapW, mapH);

        int x = MARGIN;
        int topViewX = x;
        x = toolbarButton(x, Component.translatable("isomap.map.top_view"), b -> CameraAngles.toggleTopView());
        // A flat map already is the top view.
        children().stream().filter(c -> c instanceof IsoButton b && b.getX() == topViewX).forEach(c -> ((IsoButton) c).active = IsoMapConfig.RENDER_3D.getAsBoolean());
        toolbarButton(x, Component.translatable("isomap.map.reset"), b -> resetView());

        int right = width - MARGIN;
        right = rightButton(right, Component.translatable("isomap.map.settings"), b -> minecraft.setScreen(new SettingsScreen(this)));
        right = rightButton(right, Component.translatable("isomap.map.waypoints"), b -> minecraft.setScreen(new WaypointListScreen(this)));
        rightButton(right, Component.translatable("isomap.map.new_waypoint"), b -> minecraft.setScreen(WaypointEditScreen.forNewAtPlayer(this)));
    }

    private int toolbarButton(int x, Component label, java.util.function.Consumer<IsoButton> action) {
        int w = font.width(label) + BUTTON_PADDING;
        addRenderableWidget(new IsoButton(x, MARGIN, w, TOOLBAR_HEIGHT, label, action));
        return x + w + 2;
    }

    private int rightButton(int right, Component label, java.util.function.Consumer<IsoButton> action) {
        int w = font.width(label) + BUTTON_PADDING;
        addRenderableWidget(new IsoButton(right - w, MARGIN, w, TOOLBAR_HEIGHT, label, action));
        return right - w - 2;
    }

    private void resetView() {
        CameraAngles.reset();
        view.resetPan();
        view.setZoomTarget(MapRuntime.hudView().zoomTarget());
        view.requestRender();
    }

    private boolean overMap(double mouseX, double mouseY) {
        return mouseX >= mapX && mouseX < mapX + mapW && mouseY >= mapY && mouseY < mapY + mapH;
    }

    /** The place the context menu was opened for, or null while it is closed. Public for the QA harness. */
    @Nullable
    public BlockPos menuTarget() {
        return menuTarget;
    }

    private void openMenu(double mouseX, double mouseY) {
        closeMenu();
        BlockPos pos = pickAt(mouseX, mouseY);
        if (pos == null) {
            return;
        }
        menuTarget = pos;
        List<Component> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(Component.translatable("isomap.map.menu.new_waypoint"));
        actions.add(() -> minecraft.setScreen(WaypointEditScreen.forNewAt(pos, this)));
        // Like the button in the waypoint list, teleport is only offered where the game would allow it.
        if (WaypointTeleport.isAllowed(minecraft)) {
            labels.add(Component.translatable("isomap.map.menu.teleport"));
            actions.add(() -> {
                WaypointTeleport.teleport(minecraft, Dimensions.current(minecraft), pos.getX(), pos.getY() + 1, pos.getZ());
                minecraft.setScreen(null);
            });
        }
        menuTitle = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        int w = font.width(menuTitle) + MENU_PADDING * 2;
        for (Component label : labels) {
            w = Math.max(w, font.width(label) + BUTTON_PADDING);
        }
        menuW = w + MENU_PADDING * 2;
        menuH = MENU_TITLE_HEIGHT + labels.size() * (MENU_ROW + 2) + MENU_PADDING;
        // Opens at the cursor, pushed back inside the screen near the edges.
        menuX = (int) Math.min(mouseX, width - menuW - MARGIN);
        menuY = (int) Math.min(mouseY, height - menuH - MARGIN);
        for (int i = 0; i < labels.size(); i++) {
            Runnable action = actions.get(i);
            IsoButton b = new IsoButton(menuX + MENU_PADDING, menuY + MENU_TITLE_HEIGHT + i * (MENU_ROW + 2), w, MENU_ROW, labels.get(i), x -> {
                closeMenu();
                action.run();
            });
            menuButtons.add(addRenderableWidget(b));
        }
    }

    private void closeMenu() {
        menuButtons.forEach(this::removeWidget);
        menuButtons.clear();
        menuTarget = null;
    }

    private boolean overMenu(double mouseX, double mouseY) {
        return menuTarget != null && mouseX >= menuX && mouseX < menuX + menuW && mouseY >= menuY && mouseY < menuY + menuH;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (menuTarget != null && !overMenu(mouseX, mouseY)) {
            // A click beside the menu only closes it; acting on the map at the same time would surprise.
            closeMenu();
            return true;
        }
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (menuTarget != null) {
            return true;
        }
        if (overMap(mouseX, mouseY) && button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            BlockPos pos = pickAt(mouseX, mouseY);
            if (pos != null) {
                PingClient.pingAt(minecraft, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
            }
            return true;
        }
        if (overMap(mouseX, mouseY) && (button == 0 || button == 1)) {
            dragButton = button;
            dragDistance = 0.0;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button != dragButton) {
            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }
        dragDistance += Math.abs(dragX) + Math.abs(dragY);
        if (dragDistance < CLICK_SLOP) {
            return true;
        }
        if (button == 0) {
            double guiScale = minecraft.getWindow().getGuiScale();
            CameraAngles.addMouseInput(dragX * guiScale, dragY * guiScale);
        } else {
            panBy(dragX, dragY);
        }
        view.requestRender();
        return true;
    }

    /** Moves the focus so that the terrain follows the cursor at any camera angle. */
    private void panBy(double guiDx, double guiDy) {
        double yaw = Math.toRadians(CameraAngles.yawDegrees());
        double pitchSine = Math.max(MIN_PAN_SINE, Math.sin(Math.toRadians(view.pitchDegrees())));
        double right = -guiDx * view.blocksPerGuiUnit();
        double depth = -guiDy * view.blocksPerGuiUnit() / pitchSine;
        view.pan(right * Math.cos(yaw) - depth * Math.sin(yaw), right * Math.sin(yaw) + depth * Math.cos(yaw));
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean wasClick = button == dragButton && dragDistance < CLICK_SLOP;
        if (button == dragButton) {
            dragButton = -1;
        }
        if (wasClick && overMap(mouseX, mouseY)) {
            // The right button still pans when dragged; only a click without movement opens the menu.
            if (button == 0) {
                handleMapClick(mouseX, mouseY);
            } else {
                openMenu(mouseX, mouseY);
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void handleMapClick(double mouseX, double mouseY) {
        if (hovered != null) {
            minecraft.setScreen(WaypointEditScreen.forExisting(hovered, this));
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastClickAt < DOUBLE_CLICK_MS) {
            BlockPos pos = pickAt(mouseX, mouseY);
            if (pos != null) {
                minecraft.setScreen(WaypointEditScreen.forNewAt(pos, this));
            }
        }
        lastClickAt = now;
    }

    @Nullable
    private BlockPos pickAt(double mouseX, double mouseY) {
        if (minecraft.level == null) {
            return null;
        }
        return view.pick(MapRuntime.terrain(), mouseX - (mapX + mapW / 2.0), mouseY - (mapY + mapH / 2.0),
                minecraft.level.getMinBuildHeight(), minecraft.level.getMaxBuildHeight());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        view.multiplyZoom(scrollY > 0 ? WHEEL_FACTOR : 1.0 / WHEEL_FACTOR);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && menuTarget != null) {
            closeMenu();
            return true;
        }
        if (KeyBindings.OPEN_MAP.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void tick() {
        // Everything that fits on the screen is wanted, and zoomed far out a coarser mesh does: that is what keeps it cheap.
        double blocksPerUnit = view.blocksPerGuiUnit();
        int radius = Math.min(MAX_VIEW_RADIUS_CHUNKS, (int) (Math.hypot(mapW, mapH) / 2.0 * blocksPerUnit / 16.0) + 2);
        int step = blocksPerUnit <= FINE_MESH_BLOCKS_PER_UNIT ? 1 : blocksPerUnit <= MEDIUM_MESH_BLOCKS_PER_UNIT ? 2 : 4;
        MapRuntime.terrain().setScreenView((int) Math.floor(view.focusX()), (int) Math.floor(view.focusZ()), radius, step);
    }

    @Override
    public void removed() {
        MapRuntime.detachScreenView(view);
        CameraAngles.storeIfDirty();
        IsoMapConfig.save();
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        LocalPlayer player = minecraft.player;
        Theme.frame(g, mapX - 1, mapY - 1, mapW + 2, mapH + 2, MAP_BACKGROUND, Theme.BORDER);
        view.blit(g, mapX, mapY, mapW, mapH, 1f);
        if (player == null) {
            return;
        }
        int cx = mapX + mapW / 2;
        int cy = mapY + mapH / 2;
        g.enableScissor(mapX, mapY, mapX + mapW, mapY + mapH);
        if (IsoMapConfig.SHOW_COMPASS.getAsBoolean()) {
            MarkerRenderer.drawCompass(g, font, view, cx, cy, mapW / 2, mapH / 2, COMPASS_INSIDE, 1f);
        }
        hovered = MarkerRenderer.drawWaypoints(g, view, cx, cy, mapW / 2, mapH / 2, 1f, mouseX, mouseY);
        MarkerRenderer.drawPings(g, view, cx, cy, mapW / 2, mapH / 2, 1f);
        MarkerRenderer.drawPlayer(g, view, player, partialTick, cx, cy, 1f);
        g.disableScissor();

        int statusY = mapY + mapH + 3;
        g.fill(mapX - 1, statusY - 1, mapX + mapW + 1, statusY + STATUS_HEIGHT - 1, Theme.PANEL);
        Component status;
        BlockPos pos = overMap(mouseX, mouseY) ? pickAt(mouseX, mouseY) : null;
        if (hovered != null) {
            status = Component.literal(hovered.name + "  " + hovered.x + " " + hovered.y + " " + hovered.z);
        } else if (pos != null) {
            status = Component.literal(pos.getX() + " " + pos.getY() + " " + pos.getZ());
        } else {
            status = Component.translatable("isomap.map.help");
        }
        g.drawString(font, status, mapX + 2, statusY + 1, Theme.TEXT_DIM, false);
        String angles = Math.round(CameraAngles.pitchDegrees()) + "°  " + Math.round(net.minecraft.util.Mth.wrapDegrees(CameraAngles.yawDegrees())) + "°";
        g.drawString(font, angles, mapX + mapW - font.width(angles) - 2, statusY + 1, Theme.TEXT_DIM, false);

        // The menu's own panel; its buttons are ordinary widgets drawn on top of it.
        if (menuTarget != null) {
            Theme.panel(g, menuX, menuY, menuW, menuH);
            g.drawString(font, menuTitle, menuX + MENU_PADDING + 1, menuY + 5, Theme.TEXT_DIM, false);
        }
    }
}
