package dev.isomap.client.ui.screen;

import dev.isomap.client.sound.UiSound;
import dev.isomap.client.ui.Theme;
import dev.isomap.client.ui.widget.ColorSwatch;
import dev.isomap.client.ui.widget.ConfirmButton;
import dev.isomap.client.ui.widget.IsoButton;
import dev.isomap.client.ui.widget.IsoCycle;
import dev.isomap.client.ui.widget.IsoTextField;
import dev.isomap.client.ui.widget.IsoToggle;
import dev.isomap.client.ui.widget.TagChip;
import dev.isomap.client.waypoint.Dimensions;
import dev.isomap.client.waypoint.Waypoint;
import dev.isomap.client.waypoint.WaypointStore;
import dev.isomap.client.waypoint.WaypointTeleport;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Creates or edits a waypoint. Works on a copy, so cancelling leaves the stored one untouched. */
public class WaypointEditScreen extends IsoScreen {
    public static final int[] PALETTE = {
        0x6FB1FF, 0x4FD1C5, 0x7BD88F, 0xC3E88D, 0xF6D860, 0xFFB454,
        0xFF8A73, 0xE5655C, 0xF28FB3, 0xB48EFF, 0xE9EBEE, 0x8A9099
    };

    private static final int PANEL_WIDTH = 320;
    private static final int LABEL_WIDTH = 58;
    private static final int ROW = 20;
    private static final int FIELD_HEIGHT = 16;
    private static final int MAX_SUGGESTIONS = 6;

    private final Waypoint draft;
    private final boolean isNew;
    private String nameText;
    private String xText;
    private String yText;
    private String zText;
    private String tagText = "";
    private String colorText;

    private int panelX;
    private int panelY;
    private int panelH;
    private final List<Label> labels = new ArrayList<>();
    private IsoTextField nameField;
    private IsoTextField xField;
    private IsoTextField yField;
    private IsoTextField zField;
    private IsoTextField colorField;
    private IsoTextField tagField;
    private IsoButton saveButton;
    private boolean refocusTags;

    private record Label(Component text, int x, int y) {}

    private WaypointEditScreen(Waypoint draft, boolean isNew, @Nullable Screen parent) {
        super(Component.translatable(isNew ? "isomap.edit.title_new" : "isomap.edit.title"), parent);
        this.draft = draft;
        this.isNew = isNew;
        this.nameText = draft.name;
        this.xText = Integer.toString(draft.x);
        this.yText = Integer.toString(draft.y);
        this.zText = Integer.toString(draft.z);
        this.colorText = String.format("%06X", draft.color);
    }

    public static WaypointEditScreen forNewAtPlayer(@Nullable Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        BlockPos pos = player == null ? BlockPos.ZERO : player.blockPosition();
        return forNewAt(pos, parent);
    }

    public static WaypointEditScreen forNewAt(BlockPos pos, @Nullable Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        Waypoint w = new Waypoint();
        w.x = pos.getX();
        w.y = pos.getY();
        w.z = pos.getZ();
        w.dimension = Dimensions.current(mc);
        // Successive waypoints walk through the palette so they differ without any effort.
        w.color = PALETTE[WaypointStore.get().all().size() % PALETTE.length];
        w.name = Component.translatable("isomap.edit.default_name", WaypointStore.get().all().size() + 1).getString();
        return new WaypointEditScreen(w, true, parent);
    }

    public static WaypointEditScreen forExisting(Waypoint waypoint, @Nullable Screen parent) {
        return new WaypointEditScreen(waypoint.copy(), false, parent);
    }

    @Override
    protected void init() {
        super.init();
        labels.clear();
        int chipLines = 2;
        panelH = 18 + ROW * 5 + (TagChip.HEIGHT + 2) * (chipLines + 1) + 34;
        panelX = (width - PANEL_WIDTH) / 2;
        panelY = Math.max(2, (height - panelH) / 2);
        int fieldX = panelX + 7 + LABEL_WIDTH;
        int fieldW = PANEL_WIDTH - 14 - LABEL_WIDTH;
        int y = panelY + 18;

        // Name
        label("isomap.edit.name", y);
        nameField = addRenderableWidget(new IsoTextField(fieldX, y, fieldW, FIELD_HEIGHT, Component.translatable("isomap.edit.name")));
        nameField.setMaxLength(Waypoint.MAX_NAME_LENGTH);
        nameField.setValue(nameText);
        nameField.setResponder(value -> {
            nameText = value;
            validate();
        });
        y += ROW;

        // Coordinates
        label("isomap.edit.coords", y);
        int hereW = 52;
        int coordW = (fieldW - hereW - 6) / 3;
        xField = coordField(fieldX, y, coordW, "X", xText, value -> xText = value);
        yField = coordField(fieldX + coordW + 2, y, coordW, "Y", yText, value -> yText = value);
        zField = coordField(fieldX + (coordW + 2) * 2, y, coordW, "Z", zText, value -> zText = value);
        addRenderableWidget(new IsoButton(fieldX + fieldW - hereW, y, hereW, FIELD_HEIGHT, Component.translatable("isomap.edit.here"), b -> usePlayerPosition()));
        y += ROW;

        // Dimension and visibility
        label("isomap.edit.dimension", y);
        int toggleW = 86;
        addRenderableWidget(new IsoCycle<>(fieldX, y, fieldW - toggleW - 2, FIELD_HEIGHT, Dimensions.known(minecraft, draft.dimension),
                () -> draft.dimension, value -> draft.dimension = value, Dimensions::label));
        addRenderableWidget(new IsoToggle(fieldX + fieldW - toggleW, y, toggleW, FIELD_HEIGHT, () -> draft.visible, value -> draft.visible = value));
        y += ROW;

        // Colour
        label("isomap.edit.color", y);
        int swatch = 12;
        for (int i = 0; i < PALETTE.length; i++) {
            addRenderableWidget(new ColorSwatch(fieldX + i * (swatch + 1), y + 2, swatch, PALETTE[i], () -> draft.color, this::pickColor));
        }
        int hexX = fieldX + PALETTE.length * (swatch + 1) + 2;
        colorField = addRenderableWidget(new IsoTextField(hexX, y, fieldX + fieldW - hexX, FIELD_HEIGHT, Component.literal("RRGGBB")));
        colorField.setMaxLength(6);
        colorField.setFilter(value -> value.matches("[0-9a-fA-F]*"));
        colorField.setValue(colorText);
        colorField.setResponder(this::typedColor);
        y += ROW;

        // Tags
        label("isomap.edit.tags", y);
        int addW = 54;
        tagField = addRenderableWidget(new IsoTextField(fieldX, y, fieldW - addW - 2, FIELD_HEIGHT, Component.translatable("isomap.edit.tag_hint")));
        tagField.setMaxLength(Waypoint.MAX_TAG_LENGTH);
        tagField.setValue(tagText);
        tagField.setResponder(value -> tagText = value);
        addRenderableWidget(new IsoButton(fieldX + fieldW - addW, y, addW, FIELD_HEIGHT, Component.translatable("isomap.edit.tag_add"), b -> addTypedTag()));
        y += ROW;
        addTagChips(fieldX, y, fieldW, chipLines);
        y += (TagChip.HEIGHT + 2) * chipLines;
        addSuggestionChips(fieldX, y, fieldW);

        // Footer
        int footerY = panelY + panelH - 21;
        saveButton = addRenderableWidget(new IsoButton(panelX + PANEL_WIDTH - 76, footerY, 70, 15, Component.translatable("isomap.ui.save"), b -> save()));
        addRenderableWidget(new IsoButton(panelX + PANEL_WIDTH - 148, footerY, 70, 15, Component.translatable("isomap.ui.cancel"), b -> onClose()));
        if (!isNew) {
            addRenderableWidget(new ConfirmButton(panelX + 6, footerY, 70, 15, Component.translatable("isomap.ui.delete"), Component.translatable("isomap.ui.confirm"), b -> delete()));
            if (WaypointTeleport.isAllowed(minecraft)) {
                addRenderableWidget(new IsoButton(panelX + 78, footerY, 70, 15, Component.translatable("isomap.edit.teleport"), b -> {
                    WaypointTeleport.teleport(minecraft, draft);
                    minecraft.setScreen(null);
                }));
            }
        }
        validate();

        if (refocusTags) {
            refocusTags = false;
            setFocused(tagField);
        } else if (isNew) {
            setInitialFocus(nameField);
        }
    }

    private void label(String key, int y) {
        labels.add(new Label(Component.translatable(key), panelX + 7, y + 4));
    }

    private IsoTextField coordField(int x, int y, int width, String hint, String value, java.util.function.Consumer<String> sink) {
        IsoTextField field = addRenderableWidget(new IsoTextField(x, y, width, FIELD_HEIGHT, Component.literal(hint)));
        field.setMaxLength(9);
        field.setFilter(text -> text.matches("-?[0-9]*"));
        field.setValue(value);
        field.setResponder(text -> {
            sink.accept(text);
            validate();
        });
        return field;
    }

    private void addTagChips(int x0, int y0, int width, int lines) {
        int x = x0;
        int y = y0;
        int line = 1;
        for (String tag : draft.tags) {
            String text = "#" + tag + " ×";
            int chipW = TagChip.widthOf(text);
            if (x + chipW > x0 + width) {
                if (line == lines) {
                    break;
                }
                line++;
                x = x0;
                y += TagChip.HEIGHT + 2;
            }
            addRenderableWidget(TagChip.create(x, y, text, true, () -> {
                draft.removeTag(tag);
                rebuildLater();
            }));
            x += chipW + 2;
        }
    }

    private void addSuggestionChips(int x0, int y, int width) {
        int x = x0;
        int shown = 0;
        for (String tag : WaypointStore.get().allTags()) {
            if (draft.tags.contains(tag)) {
                continue;
            }
            String text = "+" + tag;
            int chipW = TagChip.widthOf(text);
            if (x + chipW > x0 + width || shown == MAX_SUGGESTIONS) {
                break;
            }
            addRenderableWidget(TagChip.create(x, y, text, false, () -> {
                draft.addTag(tag);
                rebuildLater();
            }));
            x += chipW + 2;
            shown++;
        }
    }

    /** The chip that was clicked is still being handled, so the widget list is rebuilt on the next task. */
    private void rebuildLater() {
        refocusTags = false;
        minecraft.tell(this::rebuildWidgets);
    }

    private void addTypedTag() {
        if (draft.addTag(tagText)) {
            tagText = "";
            UiSound.TOGGLE_ON.play();
        }
        refocusTags = true;
        minecraft.tell(this::rebuildWidgets);
    }

    private void usePlayerPosition() {
        LocalPlayer player = minecraft.player;
        if (player != null) {
            xField.setValue(Integer.toString(player.getBlockX()));
            yField.setValue(Integer.toString(player.getBlockY()));
            zField.setValue(Integer.toString(player.getBlockZ()));
            draft.dimension = Dimensions.current(minecraft);
        }
    }

    private void pickColor(int color) {
        draft.color = color;
        colorText = String.format("%06X", color);
        colorField.setValue(colorText);
    }

    private void typedColor(String value) {
        colorText = value;
        if (value.length() == 6) {
            draft.color = Integer.parseInt(value, 16);
        }
        validate();
    }

    private static boolean isInt(String text) {
        try {
            Integer.parseInt(text);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void validate() {
        if (saveButton == null) {
            return;
        }
        boolean nameOk = !nameText.isBlank();
        boolean xOk = isInt(xText);
        boolean yOk = isInt(yText);
        boolean zOk = isInt(zText);
        boolean colorOk = colorText.length() == 6;
        nameField.setInvalid(!nameOk);
        xField.setInvalid(!xOk);
        yField.setInvalid(!yOk);
        zField.setInvalid(!zOk);
        colorField.setInvalid(!colorOk);
        saveButton.active = nameOk && xOk && yOk && zOk && colorOk;
    }

    private void save() {
        // Text still sitting in the tag field would otherwise be silently lost.
        draft.addTag(tagText);
        draft.name = nameText.trim();
        draft.x = Integer.parseInt(xText);
        draft.y = Integer.parseInt(yText);
        draft.z = Integer.parseInt(zText);
        WaypointStore.get().put(draft);
        if (isNew) {
            UiSound.WAYPOINT_ADD.play();
        }
        minecraft.setScreen(parent);
    }

    private void delete() {
        WaypointStore.get().remove(draft);
        UiSound.WAYPOINT_REMOVE.play();
        minecraft.setScreen(parent);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean enter = keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER;
        if (enter && tagField.isFocused() && !tagText.isBlank()) {
            addTypedTag();
            return true;
        }
        if (enter && saveButton.active) {
            save();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Theme.panel(g, panelX, panelY, PANEL_WIDTH, panelH);
        g.drawString(font, title, panelX + 7, panelY + 6, Theme.TEXT, false);
        for (Label label : labels) {
            g.drawString(font, label.text, label.x, label.y, Theme.TEXT_DIM, false);
        }
        if (draft.death) {
            Component death = Component.translatable("isomap.edit.death_marker");
            g.drawString(font, death, panelX + PANEL_WIDTH - 7 - font.width(death), panelY + 6, Theme.DANGER, false);
        }
    }
}
