package dev.isomap.neoforge;

import dev.isomap.IsoMap;
import dev.isomap.client.input.KeyBindings;
import dev.isomap.client.render.HudOverlay;
import dev.isomap.client.ui.screen.SettingsScreen;
import dev.isomap.config.IsoMapConfig;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;

/** Client entry point: everything the player sees. It registers nothing in synced registries. */
@Mod(value = IsoMap.MOD_ID, dist = Dist.CLIENT)
public final class IsoMapNeoForgeClient {
    public IsoMapNeoForgeClient(IEventBus modBus, ModContainer container) {
        NeoForgePlatform.install();
        IsoMapConfig.load();
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> new SettingsScreen(parent));

        modBus.addListener(IsoMapNeoForgeClient::registerKeys);
        modBus.addListener(IsoMapNeoForgeClient::registerGuiLayers);
        NeoForge.EVENT_BUS.register(NeoForgeClientEvents.class);
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) {
        for (KeyMapping key : KeyBindings.ALL) {
            // In menus the same keys belong to the screen, not to the map.
            key.setKeyConflictContext(KeyConflictContext.IN_GAME);
            event.register(key);
        }
    }

    private static void registerGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(IsoMap.id("minimap"), HudOverlay::render);
    }
}
