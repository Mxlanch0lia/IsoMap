package dev.isomap.fabric.mixin;

import dev.isomap.client.ClientEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mouse turning and the wheel: Fabric API has no events for them, NeoForge does. */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
    @Shadow
    private double accumulatedDX;
    @Shadow
    private double accumulatedDY;

    /** While the camera key is held, the movement turns the map; with nothing left over the player stays put. */
    @Inject(method = "turnPlayer", at = @At("HEAD"))
    private void isomap$turnPlayer(double movementTime, CallbackInfo ci) {
        if (ClientEvents.consumeTurn(accumulatedDX, accumulatedDY)) {
            accumulatedDX = 0.0;
            accumulatedDY = 0.0;
        }
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void isomap$onScroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
        if (window == Minecraft.getInstance().getWindow().getWindow() && yOffset != 0.0 && ClientEvents.onScroll(yOffset)) {
            ci.cancel();
        }
    }
}
