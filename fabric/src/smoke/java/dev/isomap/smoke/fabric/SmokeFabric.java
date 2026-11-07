package dev.isomap.smoke.fabric;

import dev.isomap.client.ClientEvents;
import dev.isomap.smoke.SmokePlatform;
import dev.isomap.smoke.SmokeTest;
import java.lang.reflect.Method;
import javax.annotation.Nullable;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;

/** Starts the shared QA harness on Fabric. */
public final class SmokeFabric implements ClientModInitializer, SmokePlatform {
    @Override
    public void onInitializeClient() {
        if (!SmokeTest.isActive()) {
            return;
        }
        SmokeTest.start(this);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> SmokeTest.onTick());
        // The harness only measures frames while a world is open, so the start of the world's rendering will do.
        WorldRenderEvents.START.register(context -> SmokeTest.onFramePre());
    }

    /** Fabric's turn path is a mixin at the head of the private turnPlayer; the shared decision is what it calls. */
    @Override
    public boolean turnFrozen() {
        return ClientEvents.consumeTurn(0.0, 0.0);
    }

    /** Goes through the real wheel handler, so the scroll mixin itself is what gets tested. */
    @Override
    public boolean scrollConsumed() {
        Minecraft mc = Minecraft.getInstance();
        int slot = mc.player == null ? -1 : mc.player.getInventory().selected;
        double zoom = dev.isomap.client.render.MapRuntime.hudView().zoomTarget();
        try {
            // The development client runs with Mojang's names, so the private method can be found by name.
            Method onScroll = MouseHandler.class.getDeclaredMethod("onScroll", long.class, double.class, double.class);
            onScroll.setAccessible(true);
            onScroll.invoke(mc.mouseHandler, mc.getWindow().getWindow(), 0.0, 1.0);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot reach MouseHandler.onScroll", e);
        }
        boolean zoomed = dev.isomap.client.render.MapRuntime.hudView().zoomTarget() != zoom;
        boolean slotMoved = mc.player != null && mc.player.getInventory().selected != slot;
        if (slotMoved) {
            mc.player.getInventory().selected = slot;
        }
        return zoomed && !slotMoved;
    }

    /** Fabric has no list of its own; Mod Menu is optional and not part of the test client. */
    @Nullable
    @Override
    public Screen modListScreen(Screen parent) {
        return null;
    }
}
