package dev.isomap.client.ui.screen;

import com.mojang.blaze3d.platform.InputConstants;
import dev.isomap.client.input.KeyBindings;
import dev.isomap.client.render.CameraAngles;
import dev.isomap.client.render.MapRuntime;
import dev.isomap.client.sound.UiSound;
import dev.isomap.client.ui.Theme;
import dev.isomap.client.ui.widget.ConfirmButton;
import dev.isomap.client.ui.widget.IsoButton;
import dev.isomap.client.ui.widget.IsoCycle;
import dev.isomap.client.ui.widget.IsoSlider;
import dev.isomap.client.ui.widget.IsoToggle;
import dev.isomap.client.ui.widget.IsoWidget;
import dev.isomap.client.ui.widget.ScrollArea;
import dev.isomap.config.IsoMapConfig;
import dev.isomap.config.Labeled;
import dev.isomap.config.Setting;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * The settings, in five tabs. Changes apply immediately, the HUD map stays visible behind the panel as a live preview,
 * and the file is written when the screen closes.
 */
public class SettingsScreen extends IsoScreen {
    public enum Tab {
        GENERAL, MINIMAP, WAYPOINTS, SOUNDS, CONTROLS;

        Component label() {
            return Component.translatable("isomap.settings.tab." + name().toLowerCase(Locale.ROOT));
        }
    }

    private static final int MARGIN = 8;
    private static final int MAX_WIDTH = 400;
    private static final int MAX_HEIGHT = 320;
    private static final int ROW_HEIGHT = 16;
    private static final int ROW_GAP = 3;
    private static final int HEADER_HEIGHT = 15;
    private static final int CONTROL_WIDTH = 124;

    private static Tab activeTab = Tab.GENERAL;

    private final ScrollArea scroll = new ScrollArea();
    private final List<Row> rows = new ArrayList<>();
    private final List<Setting<?>> tabValues = new ArrayList<>();
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int rowCursor;
    @Nullable
    private KeyMapping capturing;

    private record Row(@Nullable Component label, Component hint, @Nullable AbstractWidget control, int baseY) {}

    public SettingsScreen(@Nullable Screen parent) {
        super(Component.translatable("isomap.settings.title"), parent);
    }

    public static void selectTab(Tab tab) {
        activeTab = tab;
    }

    @Override
    protected void init() {
        super.init();
        panelW = Math.min(width - MARGIN * 2, MAX_WIDTH);
        panelH = Math.min(height - MARGIN * 2, MAX_HEIGHT);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;

        int tabY = panelY + 17;
        Tab[] tabs = Tab.values();
        int tabW = (panelW - 12 - (tabs.length - 1) * 2) / tabs.length;
        for (int i = 0; i < tabs.length; i++) {
            Tab tab = tabs[i];
            addRenderableWidget(new IsoButton(panelX + 6 + i * (tabW + 2), tabY, tabW, 15, tab.label(), b -> switchTab(tab))
                    .selected(tab == activeTab));
        }

        int footerY = panelY + panelH - 21;
        Component resetTabLabel = Component.translatable("isomap.settings.reset_tab");
        Component resetAllLabel = Component.translatable("isomap.settings.reset_all");
        Component confirmLabel = Component.translatable("isomap.ui.confirm");
        int resetTabW = font.width(resetTabLabel) + 12;
        int resetAllW = Math.max(font.width(resetAllLabel), font.width(confirmLabel)) + 12;
        addRenderableWidget(new IsoButton(panelX + 6, footerY, resetTabW, 15, resetTabLabel, b -> resetTab()));
        addRenderableWidget(new ConfirmButton(panelX + 8 + resetTabW, footerY, resetAllW, 15, resetAllLabel, confirmLabel, b -> resetAll()));
        addRenderableWidget(new IsoButton(panelX + panelW - 76, footerY, 70, 15, Component.translatable("isomap.ui.done"), b -> onClose()));

        int contentTop = tabY + 19;
        int hintHeight = 11;
        scroll.setBounds(panelX + 6, contentTop, panelW - 12, footerY - contentTop - hintHeight - 3);
        buildTab();
        layoutRows();
    }

    private void switchTab(Tab tab) {
        if (tab != activeTab) {
            activeTab = tab;
            capturing = null;
            scroll.scrollToTop();
            rebuildWidgets();
        }
    }

    private void buildTab() {
        rows.clear();
        tabValues.clear();
        rowCursor = 0;
        switch (activeTab) {
            case GENERAL -> {
                header("general");
                toggle(IsoMapConfig.ENABLED);
                toggle(IsoMapConfig.SHOW_COORDS);
                toggle(IsoMapConfig.SHOW_BIOME);
                toggle(IsoMapConfig.SHOW_COMPASS);
                toggle(IsoMapConfig.HIDE_WITH_DEBUG);
                toggle(IsoMapConfig.UI_ANIMATIONS);
            }
            case MINIMAP -> {
                header("layout");
                slider(IsoMapConfig.SIZE, 4, "");
                cycle(IsoMapConfig.ANCHOR);
                slider(IsoMapConfig.OFFSET_X, 1, "");
                slider(IsoMapConfig.OFFSET_Y, 1, "");
                slider(IsoMapConfig.OPACITY, 1, "%");
                slider(IsoMapConfig.BACKGROUND_OPACITY, 1, "%");
                header("terrain");
                toggle(IsoMapConfig.RENDER_3D);
                toggle(IsoMapConfig.TERRAIN_LAYERS);
                slider(IsoMapConfig.RENDER_DISTANCE, 1, "");
                slider(IsoMapConfig.UPDATE_FPS, 1, "");
                slider(IsoMapConfig.REFRESH_SECONDS, 1, "s");
                decimalSlider(IsoMapConfig.HEIGHT_SCALE, 0.05);
                cycle(IsoMapConfig.CAVE_MODE);
                toggle(IsoMapConfig.SUPERSAMPLE);
                header("camera");
                slider(IsoMapConfig.FOLLOW_SMOOTHING, 1, "%");
                decimalSlider(IsoMapConfig.SENSITIVITY, 0.05);
                toggle(IsoMapConfig.INVERT_YAW);
                toggle(IsoMapConfig.INVERT_PITCH);
                slider(IsoMapConfig.PITCH_MIN, 1, "°");
                slider(IsoMapConfig.PITCH_MAX, 1, "°");
                slider(IsoMapConfig.YAW_LIMIT, 5, "°");
                slider(IsoMapConfig.AUTO_RETURN_SECONDS, 1, "s");
            }
            case WAYPOINTS -> {
                header("display");
                toggle(IsoMapConfig.SHOW_WAYPOINTS);
                cycle(IsoMapConfig.LABEL_MODE);
                toggle(IsoMapConfig.SHOW_DISTANCE);
                toggle(IsoMapConfig.EDGE_MARKERS);
                slider(IsoMapConfig.MARKER_SIZE, 2, "");
                header("death");
                toggle(IsoMapConfig.DEATH_ENABLED);
                toggle(IsoMapConfig.DEATH_VISIBLE);
                toggle(IsoMapConfig.DEATH_CHAT);
                slider(IsoMapConfig.DEATH_MAX, 1, "");
                header("pings");
                toggle(IsoMapConfig.PINGS_ENABLED);
                toggle(IsoMapConfig.PINGS_THROUGH_CHAT);
                slider(IsoMapConfig.PING_SECONDS, 1, "s");
            }
            case SOUNDS -> {
                header("sounds");
                toggle(IsoMapConfig.SOUND_ENABLED);
                slider(IsoMapConfig.SOUND_VOLUME, 1, "%");
                header("sound_groups");
                toggle(IsoMapConfig.SOUND_MENU);
                toggle(IsoMapConfig.SOUND_HOVER);
                toggle(IsoMapConfig.SOUND_CLICK);
                toggle(IsoMapConfig.SOUND_TOGGLE);
                toggle(IsoMapConfig.SOUND_WAYPOINT);
                toggle(IsoMapConfig.SOUND_PING);
                addRow(Component.translatable("isomap.settings.sound_test"), Component.translatable("isomap.settings.sound_test.hint"),
                        new IsoButton(0, 0, CONTROL_WIDTH, ROW_HEIGHT, Component.translatable("isomap.settings.sound_test.play"), b -> UiSound.WAYPOINT_ADD.play()));
            }
            case CONTROLS -> {
                header("keys");
                for (KeyMapping mapping : KeyBindings.ALL) {
                    addRow(Component.translatable(mapping.getName()), Component.translatable("isomap.settings.key.hint"), new KeyButton(mapping));
                }
                header("mouse");
                toggle(IsoMapConfig.WHEEL_ZOOM);
            }
        }
    }

    private void header(String key) {
        rows.add(new Row(Component.translatable("isomap.settings.section." + key), Component.empty(), null, rowCursor));
        rowCursor += HEADER_HEIGHT;
    }

    private void addRow(Component label, Component hint, AbstractWidget control) {
        rows.add(new Row(label, hint, control, rowCursor));
        rowCursor += ROW_HEIGHT + ROW_GAP;
        addWidget(control);
    }

    private void addSetting(Setting<?> value, AbstractWidget control) {
        tabValues.add(value);
        String key = "isomap.setting." + value.path();
        addRow(Component.translatable(key), Component.translatable(key + ".hint"), control);
    }

    private void toggle(Setting.Bool value) {
        addSetting(value, new IsoToggle(0, 0, CONTROL_WIDTH, ROW_HEIGHT, value::getAsBoolean, value::set));
    }

    private void slider(Setting.Int value, int step, String unit) {
        addSetting(value, new IsoSlider(0, 0, CONTROL_WIDTH, ROW_HEIGHT, value.min(), value.max(), step,
                value::getAsInt, v -> value.set((int) Math.round(v)), v -> Math.round(v) + unit));
    }

    private void decimalSlider(Setting.Decimal value, double step) {
        addSetting(value, new IsoSlider(0, 0, CONTROL_WIDTH, ROW_HEIGHT, value.min(), value.max(), step,
                value::getAsDouble, value::set, v -> String.format(Locale.ROOT, "%.2f", v)));
    }

    private <E extends Enum<E> & Labeled> void cycle(Setting.Choice<E> value) {
        addSetting(value, new IsoCycle<>(0, 0, CONTROL_WIDTH, ROW_HEIGHT, value.constants(), value::get, value::set, Labeled::label));
    }

    private void layoutRows() {
        scroll.setContentHeight(rowCursor);
        int controlX = panelX + 6 + scroll.contentWidth() - CONTROL_WIDTH;
        for (Row row : rows) {
            if (row.control != null) {
                row.control.setX(controlX);
                row.control.setY(scroll.top() + row.baseY - scroll.offset());
                row.control.visible = row.control.getBottom() > scroll.top() && row.control.getY() < scroll.bottom();
                if (row.control instanceof IsoWidget widget) {
                    widget.setClip(scroll.top(), scroll.bottom());
                }
            }
        }
    }

    private void resetTab() {
        capturing = null;
        tabValues.forEach(Setting::reset);
        if (activeTab == Tab.CONTROLS) {
            resetKeys();
        }
        IsoMapConfig.save();
    }

    private void resetAll() {
        capturing = null;
        IsoMapConfig.resetAll();
        resetKeys();
        CameraAngles.reloadFromConfig();
        MapRuntime.hudView().setZoomTarget(IsoMapConfig.STATE_ZOOM.getDefault());
    }

    private void resetKeys() {
        for (KeyMapping mapping : KeyBindings.ALL) {
            mapping.setKey(mapping.getDefaultKey());
        }
        KeyMapping.resetMapping();
        if (minecraft != null) {
            minecraft.options.save();
        }
    }

    private void bindKey(InputConstants.Key key) {
        KeyMapping mapping = capturing;
        if (mapping != null && minecraft != null) {
            mapping.setKey(key);
            KeyMapping.resetMapping();
            minecraft.options.save();
            capturing = null;
            UiSound.CLICK.play();
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (capturing != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                capturing = null;
            } else if (keyCode == GLFW.GLFW_KEY_BACKSPACE || keyCode == GLFW.GLFW_KEY_DELETE) {
                bindKey(InputConstants.UNKNOWN);
            } else {
                bindKey(InputConstants.getKey(keyCode, scanCode));
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (capturing != null) {
            bindKey(InputConstants.Type.MOUSE.getOrCreate(button));
            return true;
        }
        return scroll.mouseClicked(mouseX, mouseY) || super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (scroll.mouseDragged(mouseY)) {
            layoutRows();
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        scroll.mouseReleased();
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scroll.mouseScrolled(mouseX, mouseY, scrollY)) {
            layoutRows();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void removed() {
        IsoMapConfig.save();
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Theme.panel(g, panelX, panelY, panelW, panelH);
        g.drawString(font, title, panelX + 7, panelY + 6, Theme.TEXT, false);

        layoutRows();
        g.enableScissor(panelX + 2, scroll.top(), panelX + panelW - 2, scroll.bottom());
        Component hint = Component.empty();
        for (Row row : rows) {
            int rowY = scroll.top() + row.baseY - scroll.offset();
            if (rowY > scroll.bottom() || rowY + ROW_HEIGHT < scroll.top()) {
                continue;
            }
            if (row.control == null) {
                g.drawString(font, row.label, panelX + 7, rowY + 4, Theme.ACCENT, false);
                g.fill(panelX + 7, rowY + 13, panelX + 6 + scroll.contentWidth(), rowY + 14, Theme.SURFACE_HOVER);
            } else {
                boolean rowHovered = scroll.contains(mouseX, mouseY) && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT + ROW_GAP;
                if (rowHovered) {
                    hint = row.hint;
                    g.fill(panelX + 6, rowY - 1, panelX + 6 + scroll.contentWidth(), rowY + ROW_HEIGHT + 1, Theme.SURFACE);
                }
                int labelWidth = scroll.contentWidth() - CONTROL_WIDTH - 8;
                g.drawString(font, font.plainSubstrByWidth(row.label.getString(), labelWidth), panelX + 9, rowY + 4, Theme.TEXT, false);
                row.control.render(g, mouseX, mouseY, partialTick);
            }
        }
        g.disableScissor();
        scroll.renderBar(g);

        String hintText = font.plainSubstrByWidth(hint.getString(), panelW - 14);
        g.drawString(font, hintText, panelX + 7, scroll.bottom() + 3, Theme.TEXT_DIM, false);
    }

    /** Shows and rebinds one key mapping, marking a clash with any other binding in the game. */
    private final class KeyButton extends IsoWidget {
        private final KeyMapping mapping;

        KeyButton(KeyMapping mapping) {
            super(0, 0, CONTROL_WIDTH, ROW_HEIGHT, Component.empty());
            this.mapping = mapping;
        }

        @Override
        protected void onPressed(double mouseX, double mouseY, int button) {
            capturing = mapping;
            UiSound.CLICK.play();
        }

        private boolean hasConflict() {
            if (mapping.isUnbound() || minecraft == null) {
                return false;
            }
            for (KeyMapping other : minecraft.options.keyMappings) {
                if (other != mapping && other.same(mapping)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        protected void paint(GuiGraphics g, int mouseX, int mouseY, float partialTick, float hover, float press) {
            paintBox(g, hover, press);
            Font textFont = font;
            boolean waiting = capturing == mapping;
            Component text = waiting ? Component.translatable("isomap.settings.key.press") : mapping.getTranslatedKeyMessage();
            int color = waiting ? Theme.ACCENT : hasConflict() ? Theme.DANGER : Theme.TEXT;
            String clipped = textFont.plainSubstrByWidth(text.getString(), width - 6);
            g.drawString(textFont, clipped, getX() + (width - textFont.width(clipped)) / 2, getY() + (height - 8) / 2, color, false);
        }
    }
}
