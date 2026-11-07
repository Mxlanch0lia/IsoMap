package dev.isomap.fabric.mixin;

import dev.isomap.client.ClientEvents;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The start of a frame, where the map renders its image: the same moment as NeoForge's RenderFrameEvent.Pre. */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void isomap$frameStart(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        ClientEvents.onFrameStart(deltaTracker.getGameTimeDeltaPartialTick(false));
    }
}
