package dev.isomap.client.ui.widget;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import dev.isomap.client.sound.UiSound;
import dev.isomap.client.ui.Theme;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

public class IsoSlider extends IsoWidget {
    private static final int THUMB_WIDTH = 3;

    private final double min;
    private final double max;
    private final double step;
    private final DoubleSupplier getter;
    private final DoubleConsumer setter;
    private final DoubleFunction<String> format;

    public IsoSlider(int x, int y, int width, int height, double min, double max, double step,
            DoubleSupplier getter, DoubleConsumer setter, DoubleFunction<String> format) {
        super(x, y, width, height, Component.empty());
        this.min = min;
        this.max = max;
        this.step = step;
        this.getter = getter;
        this.setter = setter;
        this.format = format;
    }

    private void setFromMouse(double mouseX) {
        double t = Mth.clamp((mouseX - getX() - THUMB_WIDTH / 2.0) / (width - THUMB_WIDTH), 0.0, 1.0);
        apply(min + (max - min) * t);
    }

    private void apply(double raw) {
        double snapped = Mth.clamp(Math.round(raw / step) * step, min, max);
        if (snapped != getter.getAsDouble()) {
            setter.accept(snapped);
        }
    }

    @Override
    protected void onPressed(double mouseX, double mouseY, int button) {
        setFromMouse(mouseX);
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dragX, double dragY) {
        setFromMouse(event.x());
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        UiSound.CLICK.play();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        if (keyCode == InputConstants.KEY_LEFT || keyCode == InputConstants.KEY_RIGHT) {
            apply(getter.getAsDouble() + (keyCode == InputConstants.KEY_RIGHT ? step : -step));
            return true;
        }
        return false;
    }

    @Override
    protected void paint(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick, float hover, float press) {
        paintBox(g, hover, press);
        double value = getter.getAsDouble();
        float t = (float) Mth.clamp((value - min) / (max - min), 0.0, 1.0);
        int thumbX = getX() + Math.round(t * (width - THUMB_WIDTH));
        g.fill(getX() + 1, getY() + 1, thumbX, getBottom() - 1, dim(Theme.withAlpha(Theme.ACCENT, 0.35f)));
        g.fill(thumbX, getY() + 1, thumbX + THUMB_WIDTH, getBottom() - 1, dim(Theme.ACCENT));
        Font font = Minecraft.getInstance().font;
        String text = format.apply(value);
        // The shadow keeps the value readable when the thumb passes behind it.
        g.text(font, text, getX() + (width - font.width(text)) / 2, getY() + (height - 8) / 2, dim(Theme.TEXT), true);
    }
}
