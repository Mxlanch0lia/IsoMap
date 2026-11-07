package dev.isomap.client.ui.widget;

import dev.isomap.client.sound.UiSound;
import dev.isomap.client.ui.Theme;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** A rectangular switch with a square knob. */
public class IsoToggle extends IsoWidget {
    private static final int TRACK_WIDTH = 20;
    private static final int TRACK_HEIGHT = 10;

    private final BooleanSupplier getter;
    private final Consumer<Boolean> setter;
    private float knob;
    private boolean knobReady;

    public IsoToggle(int x, int y, int width, int height, BooleanSupplier getter, Consumer<Boolean> setter) {
        super(x, y, width, height, Component.empty());
        this.getter = getter;
        this.setter = setter;
    }

    @Override
    protected void onPressed(double mouseX, double mouseY, int button) {
        boolean value = !getter.getAsBoolean();
        setter.accept(value);
        UiSound.toggle(value);
    }

    @Override
    protected void paint(GuiGraphics g, int mouseX, int mouseY, float partialTick, float hover, float press) {
        boolean on = getter.getAsBoolean();
        paintBox(g, hover, press);
        float wanted = on ? 1f : 0f;
        knob = knobReady ? knob + (wanted - knob) * 0.35f : wanted;
        knobReady = true;

        int trackX = getRight() - TRACK_WIDTH - 4;
        int trackY = getY() + (height - TRACK_HEIGHT) / 2;
        Theme.frame(g, trackX, trackY, TRACK_WIDTH, TRACK_HEIGHT, dim(Theme.mix(0x30FFFFFF, Theme.withAlpha(Theme.ACCENT, 0.55f), knob)), dim(Theme.BORDER));
        int knobX = trackX + 1 + Math.round(knob * (TRACK_WIDTH - TRACK_HEIGHT));
        g.fill(knobX, trackY + 1, knobX + TRACK_HEIGHT - 2, trackY + TRACK_HEIGHT - 1, dim(on ? Theme.TEXT : Theme.TEXT_DIM));

        Font font = Minecraft.getInstance().font;
        Component text = Component.translatable(on ? "isomap.ui.on" : "isomap.ui.off");
        g.drawString(font, text, getX() + 5, getY() + (height - 8) / 2, dim(on ? Theme.TEXT : Theme.TEXT_DIM), false);
    }
}
