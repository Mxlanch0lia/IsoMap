package dev.isomap.client.ui.widget;

import dev.isomap.client.sound.UiSound;
import dev.isomap.client.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** A small rectangle holding a tag. Clicking does whatever the owner decides: filter, remove or add. */
public class TagChip extends IsoWidget {
    public static final int HEIGHT = 12;
    private static final int PADDING = 4;

    private final Runnable action;
    private final String text;
    private final boolean selected;

    private TagChip(int x, int y, int width, String text, boolean selected, Runnable action) {
        super(x, y, width, HEIGHT, Component.literal(text));
        this.action = action;
        this.text = text;
        this.selected = selected;
    }

    public static TagChip create(int x, int y, String text, boolean selected, Runnable action) {
        return new TagChip(x, y, widthOf(text), text, selected, action);
    }

    public static int widthOf(String text) {
        return Minecraft.getInstance().font.width(text) + PADDING * 2;
    }

    @Override
    protected void onPressed(double mouseX, double mouseY, int button) {
        UiSound.toggle(!selected);
        action.run();
    }

    @Override
    protected void paint(GuiGraphics g, int mouseX, int mouseY, float partialTick, float hover, float press) {
        paintBox(g, hover, press);
        if (selected) {
            g.fill(getX() + 1, getY() + 1, getRight() - 1, getBottom() - 1, Theme.withAlpha(Theme.ACCENT, 0.3f));
        }
        Font font = Minecraft.getInstance().font;
        g.drawString(font, text, getX() + PADDING, getY() + 2, dim(selected ? Theme.TEXT : Theme.TEXT_DIM), false);
    }
}
