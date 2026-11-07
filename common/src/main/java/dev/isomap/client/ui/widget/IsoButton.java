package dev.isomap.client.ui.widget;

import dev.isomap.client.sound.UiSound;
import dev.isomap.client.ui.Theme;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

public class IsoButton extends IsoWidget {
    private final Consumer<IsoButton> action;
    private int textColor = Theme.TEXT;
    private boolean selected;

    public IsoButton(int x, int y, int width, int height, Component message, Consumer<IsoButton> action) {
        super(x, y, width, height, message);
        this.action = action;
    }

    public IsoButton textColor(int color) {
        textColor = color;
        return this;
    }

    public IsoButton selected(boolean value) {
        selected = value;
        return this;
    }

    @Override
    protected void onPressed(double mouseX, double mouseY, int button) {
        press();
    }

    protected void press() {
        UiSound.CLICK.play();
        action.accept(this);
    }

    @Override
    protected void paint(GuiGraphics g, int mouseX, int mouseY, float partialTick, float hover, float press) {
        paintBox(g, hover, press);
        if (selected) {
            g.fill(getX(), getBottom() - 2, getRight(), getBottom(), dim(Theme.ACCENT));
        }
        Font font = Minecraft.getInstance().font;
        String text = font.plainSubstrByWidth(getMessage().getString(), width - 4);
        g.drawString(font, text, getX() + (width - font.width(text)) / 2, getY() + (height - 8) / 2, dim(selected ? Theme.ACCENT : textColor), false);
    }
}
