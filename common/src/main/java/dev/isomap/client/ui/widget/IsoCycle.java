package dev.isomap.client.ui.widget;

import dev.isomap.client.sound.UiSound;
import dev.isomap.client.ui.Theme;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** Steps through a list of values: left click forwards, right click backwards. */
public class IsoCycle<T> extends IsoWidget {
    private final List<T> values;
    private final Supplier<T> getter;
    private final Consumer<T> setter;
    private final Function<T, Component> label;

    public IsoCycle(int x, int y, int width, int height, List<T> values, Supplier<T> getter, Consumer<T> setter, Function<T, Component> label) {
        super(x, y, width, height, Component.empty());
        this.values = values;
        this.getter = getter;
        this.setter = setter;
        this.label = label;
    }

    @Override
    protected boolean isValidClickButton(int button) {
        return button == 0 || button == 1;
    }

    @Override
    protected void onPressed(double mouseX, double mouseY, int button) {
        int index = values.indexOf(getter.get());
        int next = Math.floorMod(index + (button == 1 ? -1 : 1), values.size());
        setter.accept(values.get(next));
        UiSound.CLICK.play();
    }

    @Override
    protected void paint(GuiGraphics g, int mouseX, int mouseY, float partialTick, float hover, float press) {
        paintBox(g, hover, press);
        Font font = Minecraft.getInstance().font;
        String text = font.plainSubstrByWidth(label.apply(getter.get()).getString(), width - 12);
        int textY = getY() + (height - 8) / 2;
        g.drawString(font, text, getX() + (width - font.width(text)) / 2, textY, dim(Theme.TEXT), false);
        // Two small bars hint that this control has more values.
        int barY = getY() + height / 2 - 2;
        g.fill(getX() + 3, barY, getX() + 4, barY + 4, dim(Theme.TEXT_DIM));
        g.fill(getRight() - 4, barY, getRight() - 3, barY + 4, dim(Theme.TEXT_DIM));
    }
}
