package dev.isomap.client.ui.screen;

import dev.isomap.client.sound.UiSound;
import dev.isomap.client.ui.Theme;
import dev.isomap.client.ui.widget.ConfirmButton;
import dev.isomap.client.ui.widget.IsoButton;
import dev.isomap.client.ui.widget.IsoCycle;
import dev.isomap.client.ui.widget.IsoTextField;
import dev.isomap.client.ui.widget.IsoWidget;
import dev.isomap.client.ui.widget.ScrollArea;
import dev.isomap.client.ui.widget.TagChip;
import dev.isomap.client.waypoint.Dimensions;
import dev.isomap.client.waypoint.Waypoint;
import dev.isomap.client.waypoint.WaypointFilter;
import dev.isomap.client.waypoint.WaypointStore;
import dev.isomap.client.waypoint.WaypointTeleport;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Waypoint list with search, a tag filter and a dimension filter. */
public class WaypointListScreen extends IsoScreen {
    private static final int MARGIN = 8;
    private static final int MAX_WIDTH = 440;
    private static final int MAX_HEIGHT = 340;
    private static final int ROW_HEIGHT = 26;
    private static final int MAX_TAG_LINES = 2;
    private static final int ROW_BUTTON = 16;

    private enum DimensionFilter {
        CURRENT, ALL
    }

    private final ScrollArea scroll = new ScrollArea();
    private final List<Row> rows = new ArrayList<>();
    private final List<AbstractWidget> filterChips = new ArrayList<>();
    private final Set<String> requiredTags = new HashSet<>();
    private String query = "";
    private DimensionFilter dimensionFilter = DimensionFilter.ALL;
    private int builtRevision = -1;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int listX;
    private int chipsY;

    public WaypointListScreen(@Nullable Screen parent) {
        super(Component.translatable("isomap.waypoints.title"), parent);
    }

    @Override
    protected void init() {
        super.init();
        panelW = Math.min(width - MARGIN * 2, MAX_WIDTH);
        panelH = Math.min(height - MARGIN * 2, MAX_HEIGHT);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
        listX = panelX + 6;

        int topY = panelY + 18;
        int addW = font.width(Component.translatable("isomap.waypoints.new")) + 14;
        int dimensionW = Math.max(font.width(Component.translatable("isomap.waypoints.dim_all")),
                font.width(Component.translatable("isomap.waypoints.dim_current"))) + 18;
        IsoTextField search = new IsoTextField(panelX + 6, topY, panelW - 16 - addW - dimensionW, 16, Component.translatable("isomap.waypoints.search"));
        search.setMaxLength(64);
        search.setValue(query);
        search.setResponder(value -> {
            query = value;
            rebuildRows();
        });
        addRenderableWidget(search);
        addRenderableWidget(new IsoCycle<>(panelX + panelW - 8 - addW - dimensionW, topY, dimensionW, 16, List.of(DimensionFilter.values()),
                () -> dimensionFilter, value -> {
                    dimensionFilter = value;
                    rebuildRows();
                }, value -> Component.translatable(value == DimensionFilter.ALL ? "isomap.waypoints.dim_all" : "isomap.waypoints.dim_current")));
        addRenderableWidget(new IsoButton(panelX + panelW - 6 - addW, topY, addW, 16, Component.translatable("isomap.waypoints.new"),
                b -> minecraft.setScreen(WaypointEditScreen.forNewAtPlayer(this))));
        addRenderableWidget(new IsoButton(panelX + panelW - 76, panelY + panelH - 21, 70, 15, Component.translatable("isomap.ui.done"), b -> onClose()));

        chipsY = topY + 20;
        rebuildChips();
        rebuildRows();
    }

    private void rebuildChips() {
        filterChips.forEach(this::removeWidget);
        filterChips.clear();
        requiredTags.retainAll(WaypointStore.get().allTags());
        int x = listX;
        int y = chipsY;
        int line = 1;
        int maxX = panelX + panelW - 6;
        for (String tag : WaypointStore.get().allTags()) {
            String text = "#" + tag;
            int chipW = TagChip.widthOf(text);
            if (x + chipW > maxX) {
                if (line == MAX_TAG_LINES) {
                    break;
                }
                line++;
                x = listX;
                y += TagChip.HEIGHT + 2;
            }
            boolean selected = requiredTags.contains(tag);
            filterChips.add(addRenderableWidget(TagChip.create(x, y, text, selected, () -> toggleRequiredTag(tag))));
            x += chipW + 2;
        }
        int chipsBottom = filterChips.isEmpty() ? chipsY : y + TagChip.HEIGHT + 3;
        int footerY = panelY + panelH - 21;
        scroll.setBounds(listX, chipsBottom, panelW - 12, footerY - chipsBottom - 4);
    }

    private void toggleRequiredTag(String tag) {
        if (!requiredTags.remove(tag)) {
            requiredTags.add(tag);
        }
        // Rebuilding the chips from inside a chip's own click must wait: the screen is still iterating its children.
        minecraft.tell(() -> {
            rebuildChips();
            rebuildRows();
        });
    }

    private void rebuildRows() {
        for (Row row : rows) {
            removeWidget(row);
            removeWidget(row.visibility);
            removeWidget(row.edit);
            removeWidget(row.delete);
            if (row.teleport != null) {
                removeWidget(row.teleport);
            }
        }
        rows.clear();
        builtRevision = WaypointStore.get().revision();
        String dimension = dimensionFilter == DimensionFilter.CURRENT ? Dimensions.current(minecraft) : null;
        List<Waypoint> matching = new ArrayList<>();
        for (Waypoint w : WaypointStore.get().all()) {
            if (WaypointFilter.matches(w, query, requiredTags, dimension)) {
                matching.add(w);
            }
        }
        matching.sort(Comparator.comparingLong((Waypoint w) -> w.createdAt).reversed());
        for (Waypoint w : matching) {
            Row row = new Row(w);
            rows.add(row);
            // A click goes to the first child under the pointer, so the buttons must come before the row they sit on.
            addWidget(row.visibility);
            addWidget(row.edit);
            addWidget(row.delete);
            if (row.teleport != null) {
                addWidget(row.teleport);
            }
            addWidget(row);
        }
        scroll.setContentHeight(rows.size() * ROW_HEIGHT);
        layoutRows();
    }

    private void layoutRows() {
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).place(listX, scroll.top() + i * ROW_HEIGHT - scroll.offset());
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
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
    public void tick() {
        // A death or an edit made elsewhere may have changed the data under this list.
        if (builtRevision != WaypointStore.get().revision()) {
            rebuildChips();
            rebuildRows();
        }
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Theme.panel(g, panelX, panelY, panelW, panelH);
        g.drawString(font, title, panelX + 7, panelY + 6, Theme.TEXT, false);

        layoutRows();
        g.enableScissor(panelX + 2, scroll.top(), panelX + panelW - 2, scroll.bottom());
        for (Row row : rows) {
            if (row.visible) {
                row.render(g, mouseX, mouseY, partialTick);
                row.visibility.render(g, mouseX, mouseY, partialTick);
                row.edit.render(g, mouseX, mouseY, partialTick);
                row.delete.render(g, mouseX, mouseY, partialTick);
                if (row.teleport != null) {
                    row.teleport.render(g, mouseX, mouseY, partialTick);
                }
            }
        }
        g.disableScissor();
        scroll.renderBar(g);

        if (rows.isEmpty()) {
            Component empty = Component.translatable(WaypointStore.get().all().isEmpty() ? "isomap.waypoints.empty" : "isomap.waypoints.no_match");
            g.drawCenteredString(font, empty, panelX + panelW / 2, scroll.top() + 20, Theme.TEXT_DIM);
        }
        Component count = Component.translatable("isomap.waypoints.count", rows.size(), WaypointStore.get().all().size());
        g.drawString(font, count, panelX + 7, panelY + panelH - 17, Theme.TEXT_DIM, false);
    }

    /** One waypoint: colour, name, tags and position, with its own hide, edit and delete controls. */
    private final class Row extends IsoWidget {
        final Waypoint waypoint;
        final IsoWidget visibility;
        final IsoButton edit;
        final IsoButton delete;
        /** Only exists where the player may teleport. */
        @Nullable
        final IsoButton teleport;

        Row(Waypoint waypoint) {
            super(0, 0, scroll.contentWidth(), ROW_HEIGHT - 2, Component.literal(waypoint.name));
            this.waypoint = waypoint;
            this.growFactor = 0.006f;
            this.visibility = new VisibilityButton(waypoint);
            this.edit = new IsoButton(0, 0, ROW_BUTTON + 10, ROW_BUTTON, Component.translatable("isomap.waypoints.edit"), b -> openEditor());
            this.teleport = WaypointTeleport.isAllowed(minecraft)
                    ? new IsoButton(0, 0, ROW_BUTTON + 10, ROW_BUTTON, Component.translatable("isomap.waypoints.teleport"), b -> {
                        WaypointTeleport.teleport(minecraft, waypoint);
                        minecraft.tell(() -> minecraft.setScreen(null));
                    })
                    : null;
            this.delete = new ConfirmButton(0, 0, ROW_BUTTON, ROW_BUTTON, Component.literal("×"), Component.literal("?"), b -> {
                WaypointStore.get().remove(waypoint);
                UiSound.WAYPOINT_REMOVE.play();
                minecraft.tell(() -> {
                    rebuildChips();
                    rebuildRows();
                });
            });
        }

        void openEditor() {
            minecraft.setScreen(WaypointEditScreen.forExisting(waypoint, WaypointListScreen.this));
        }

        void place(int x, int y) {
            setX(x);
            setY(y);
            visible = getBottom() > scroll.top() && y < scroll.bottom();
            int buttonY = y + (height - ROW_BUTTON) / 2;
            int right = x + width - 3;
            delete.setX(right - delete.getWidth());
            edit.setX(delete.getX() - edit.getWidth() - 2);
            visibility.setX(edit.getX() - visibility.getWidth() - 2);
            if (teleport != null) {
                teleport.setX(visibility.getX() - teleport.getWidth() - 2);
                teleport.setY(buttonY);
                teleport.visible = visible;
                teleport.setClip(scroll.top(), scroll.bottom());
            }
            for (IsoWidget button : List.of(visibility, edit, delete)) {
                button.setY(buttonY);
                button.visible = visible;
                button.setClip(scroll.top(), scroll.bottom());
            }
            setClip(scroll.top(), scroll.bottom());
        }

        @Override
        protected void onPressed(double mouseX, double mouseY, int button) {
            UiSound.CLICK.play();
            openEditor();
        }

        @Override
        protected void paint(GuiGraphics g, int mouseX, int mouseY, float partialTick, float hover, float press) {
            paintBox(g, hover, press);
            int swatchX = getX() + 4;
            int swatchY = getY() + 4;
            int textColor = waypoint.visible ? Theme.TEXT : Theme.TEXT_DIM;
            if (waypoint.visible) {
                g.fill(swatchX, swatchY, swatchX + 8, swatchY + 8, 0xFF000000 | waypoint.color);
            } else {
                g.renderOutline(swatchX, swatchY, 8, 8, 0xFF000000 | waypoint.color);
            }
            int textX = swatchX + 12;
            int textRight = (teleport != null ? teleport.getX() : visibility.getX()) - 4;
            int textW = textRight - textX;

            String name = font.plainSubstrByWidth(waypoint.name, Math.max(20, textW * 2 / 3));
            g.drawString(font, name, textX, getY() + 3, textColor, false);
            if (!waypoint.tags.isEmpty()) {
                String tags = "#" + String.join(" #", waypoint.tags);
                int tagsX = textX + font.width(name) + 6;
                g.drawString(font, font.plainSubstrByWidth(tags, Math.max(0, textRight - tagsX)), tagsX, getY() + 3, Theme.ACCENT, false);
            }
            String info = waypoint.x + " " + waypoint.y + " " + waypoint.z + "  " + Dimensions.label(waypoint.dimension).getString() + distanceText();
            g.drawString(font, font.plainSubstrByWidth(info, textW), textX, getY() + 13, Theme.TEXT_DIM, false);
        }

        private String distanceText() {
            if (minecraft.player == null || !waypoint.dimension.equals(Dimensions.current(minecraft))) {
                return "";
            }
            double distance = Math.sqrt(minecraft.player.distanceToSqr(waypoint.x + 0.5, waypoint.y + 0.5, waypoint.z + 0.5));
            return "  " + Math.round(distance) + "m";
        }
    }

    /** Filled square for a shown waypoint, hollow for a hidden one. */
    private static final class VisibilityButton extends IsoWidget {
        private final Waypoint waypoint;

        VisibilityButton(Waypoint waypoint) {
            super(0, 0, ROW_BUTTON, ROW_BUTTON, Component.translatable("isomap.waypoints.visibility"));
            this.waypoint = waypoint;
        }

        @Override
        protected void onPressed(double mouseX, double mouseY, int button) {
            boolean shown = !waypoint.visible;
            WaypointStore.get().setVisible(waypoint, shown);
            UiSound.toggle(shown);
        }

        @Override
        protected void paint(GuiGraphics g, int mouseX, int mouseY, float partialTick, float hover, float press) {
            paintBox(g, hover, press);
            int x = getX() + 5;
            int y = getY() + 5;
            if (waypoint.visible) {
                g.fill(x, y, x + 6, y + 6, Theme.TEXT);
            } else {
                g.renderOutline(x, y, 6, 6, Theme.TEXT_DIM);
            }
        }
    }
}
