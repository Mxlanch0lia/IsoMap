package dev.isomap.smoke.neoforge;

import dev.isomap.IsoMap;
import dev.isomap.neoforge.NeoForgeClientEvents;
import dev.isomap.smoke.SmokePlatform;
import dev.isomap.smoke.SmokeTest;
import javax.annotation.Nullable;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.CalculatePlayerTurnEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Starts the shared QA harness on NeoForge and feeds it NeoForge's events. */
public final class SmokeNeoForge implements SmokePlatform {
    private SmokeNeoForge() {}

    @EventBusSubscriber(modid = IsoMap.MOD_ID, value = Dist.CLIENT)
    public static final class Setup {
        private Setup() {}

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            SmokeTest.start(new SmokeNeoForge());
        }
    }

    @EventBusSubscriber(modid = IsoMap.MOD_ID, value = Dist.CLIENT)
    public static final class Game {
        private Game() {}

        @SubscribeEvent
        public static void onTick(ClientTickEvent.Post event) {
            SmokeTest.onTick();
        }

        @SubscribeEvent
        public static void onFramePre(RenderFrameEvent.Pre event) {
            SmokeTest.onFramePre();
        }
    }

    @Override
    public boolean turnFrozen() {
        var turn = new CalculatePlayerTurnEvent(0.5, false);
        NeoForge.EVENT_BUS.post(turn);
        return Math.abs(turn.getMouseSensitivity() - NeoForgeClientEvents.FROZEN_TURN_SENSITIVITY) < 1.0e-9;
    }

    @Override
    public boolean scrollConsumed() {
        var scroll = new InputEvent.MouseScrollingEvent(0.0, 1.0, new org.joml.Vector2i(), false, false, false, 0.0, 0.0);
        NeoForge.EVENT_BUS.post(scroll);
        return scroll.isCanceled();
    }

    @Nullable
    @Override
    public Screen modListScreen(Screen parent) {
        // NeoForge for 26.3 moved the screen into its own package.
        for (String name : new String[] {"net.neoforged.neoforge.client.gui.modlist.ModListScreen", "net.neoforged.neoforge.client.gui.ModListScreen"}) {
            try {
                return (Screen) Class.forName(name).getConstructor(Screen.class).newInstance(parent);
            } catch (ReflectiveOperationException e) {
                // Try the other place.
            }
        }
        return null;
    }
}
