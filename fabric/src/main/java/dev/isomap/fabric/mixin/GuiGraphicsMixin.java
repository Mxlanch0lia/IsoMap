package dev.isomap.fabric.mixin;

import dev.isomap.fabric.TextShadow;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Drops the text shadow while a text field that asked for that is drawing; everything else draws as usual. */
@Mixin(GuiGraphics.class)
abstract class GuiGraphicsMixin {
    @ModifyVariable(
            method = {
                    "drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)I",
                    "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)I"
            },
            at = @At("HEAD"),
            argsOnly = true)
    private boolean isomap$shadow(boolean dropShadow) {
        return dropShadow && !TextShadow.suppressed;
    }
}
