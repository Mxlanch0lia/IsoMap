package dev.isomap.client;

import dev.isomap.IsoMap;
import dev.isomap.client.input.KeyBindings;
import dev.isomap.client.render.HudOverlay;
import dev.isomap.client.ui.screen.SettingsScreen;
import dev.isomap.config.IsoMapConfig;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/** Client entry point: everything the player sees. It registers nothing in synced registries. */
@Mod(value = IsoMap.MOD_ID, dist = Dist.CLIENT)
public final class IsoMapClient {
    public IsoMapClient(IEventBus modBus, ModContainer container) {
        IsoMapConfig.load();
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> new SettingsScreen(parent));

        modBus.addListener(KeyBindings::register);
        modBus.addListener(IsoMapClient::registerGuiLayers);
        NeoForge.EVENT_BUS.register(ClientEvents.class);
    }

    private static void registerGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(IsoMap.id("minimap"), HudOverlay::render);
    }
}
