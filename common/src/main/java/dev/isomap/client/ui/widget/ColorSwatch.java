package dev.isomap.client.ui.widget;

import dev.isomap.client.sound.UiSound;
import dev.isomap.client.ui.Theme;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

public class ColorSwatch extends IsoWidget {
    private final int color;
    private final IntSupplier current;
    private final IntConsumer setter;

    public ColorSwatch(int x, int y, int size, int color, IntSupplier current, IntConsumer setter) {
        super(x, y, size, size, Component.literal(String.format("#%06X", color)));
        this.color = color;
        this.current = current;
        this.setter = setter;
        this.growFactor = 0.12f;
    }

    @Override
    protected void onPressed(double mouseX, double mouseY, int button) {
        setter.accept(color);
        UiSound.CLICK.play();
    }

    @Override
    protected void paint(GuiGraphics g, int mouseX, int mouseY, float partialTick, float hover, float press) {
        boolean selected = current.getAsInt() == color;
        g.fill(getX(), getY(), getRight(), getBottom(), 0xFF000000 | color);
        g.renderOutline(getX(), getY(), width, height, selected ? Theme.TEXT : Theme.mix(0x60000000, Theme.BORDER_HOVER, hover));
        if (press > 0.01f) {
            g.fill(getX() + 1, getY() + 1, getRight() - 1, getBottom() - 1, Theme.withAlpha(Theme.FLASH, press));
        }
    }
}
