package dev.isomap.fabric.mixin;

import dev.isomap.fabric.TextShadow;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets a text field draw without the shadow; the shadow itself is dropped in {@link GuiGraphicsMixin}. */
@Mixin(EditBox.class)
abstract class EditBoxMixin implements TextShadow.Switch {
    @Unique
    private boolean isomap$shadow = true;

    @Override
    public void isomap$setTextShadow(boolean shadow) {
        isomap$shadow = shadow;
    }

    @Inject(method = "renderWidget", at = @At("HEAD"))
    private void isomap$beginRender(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        TextShadow.suppressed = !isomap$shadow;
    }

    @Inject(method = "renderWidget", at = @At("RETURN"))
    private void isomap$endRender(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        TextShadow.suppressed = false;
    }
}
