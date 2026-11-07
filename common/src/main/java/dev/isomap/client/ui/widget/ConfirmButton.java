package dev.isomap.client.ui.widget;

import dev.isomap.client.sound.UiSound;
import dev.isomap.client.ui.Theme;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** Guards a destructive action: the first click arms the button for a moment, the second one performs it. */
public class ConfirmButton extends IsoButton {
    private static final long ARM_NANOS = 2_500_000_000L;

    private final Component idleMessage;
    private final Component armedMessage;
    private long armedAt;

    public ConfirmButton(int x, int y, int width, int height, Component message, Component armedMessage, Consumer<IsoButton> action) {
        super(x, y, width, height, message, action);
        this.idleMessage = message;
        this.armedMessage = armedMessage;
    }

    private boolean isArmed() {
        return armedAt != 0L && System.nanoTime() - armedAt < ARM_NANOS;
    }

    @Override
    protected void press() {
        if (isArmed()) {
            armedAt = 0L;
            super.press();
        } else {
            armedAt = System.nanoTime();
            UiSound.CLICK.play();
        }
    }

    @Override
    protected void paint(GuiGraphics g, int mouseX, int mouseY, float partialTick, float hover, float press) {
        boolean armed = isArmed();
        setMessage(armed ? armedMessage : idleMessage);
        textColor(armed ? Theme.DANGER : Theme.TEXT);
        super.paint(g, mouseX, mouseY, partialTick, hover, press);
    }
}
