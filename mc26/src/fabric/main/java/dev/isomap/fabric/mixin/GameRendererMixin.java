package dev.isomap.fabric.mixin;

import dev.isomap.client.ClientEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The start of a frame, where the map renders its image: the same moment as NeoForge's RenderFrameEvent.Pre. */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void isomap$frameStart(CallbackInfo ci) {
        // Only the callback: the method takes different arguments in 26.1 and 26.3.
        ClientEvents.onFrameStart(Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false));
    }
}
