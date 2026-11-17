package dev.isomap.client.sound;

import dev.isomap.IsoMap;
import dev.isomap.config.IsoMapConfig;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;

/**
 * Interface sounds. The events are deliberately not added to the synced sound registry, so joining a vanilla server
 * cannot be affected: {@code SoundManager} resolves them through {@code assets/isomap/sounds.json} by name alone.
 */
public enum UiSound {
    OPEN("ui.open", 60, 1.0f, IsoMapConfig.SOUND_MENU),
    CLOSE("ui.close", 60, 1.0f, IsoMapConfig.SOUND_MENU),
    HOVER("ui.hover", 90, 0.45f, IsoMapConfig.SOUND_HOVER),
    CLICK("ui.click", 40, 0.9f, IsoMapConfig.SOUND_CLICK),
    TOGGLE_ON("ui.toggle_on", 40, 0.9f, IsoMapConfig.SOUND_TOGGLE),
    TOGGLE_OFF("ui.toggle_off", 40, 0.9f, IsoMapConfig.SOUND_TOGGLE),
    WAYPOINT_ADD("ui.waypoint_add", 100, 1.0f, IsoMapConfig.SOUND_WAYPOINT),
    WAYPOINT_REMOVE("ui.waypoint_remove", 100, 1.0f, IsoMapConfig.SOUND_WAYPOINT),
    PING("ui.ping", 150, 1.0f, IsoMapConfig.SOUND_PING);

    private final SoundEvent event;
    private final long cooldownNanos;
    private final float gain;
    private final BooleanSupplier enabled;
    private long lastPlayed;
    private int playCount;

    UiSound(String name, int cooldownMs, float gain, BooleanSupplier enabled) {
        this.event = SoundEvent.createVariableRangeEvent(IsoMap.id(name));
        this.cooldownNanos = cooldownMs * 1_000_000L;
        this.gain = gain;
        this.enabled = enabled;
    }

    public void play() {
        if (!IsoMapConfig.SOUND_ENABLED.getAsBoolean() || !enabled.getAsBoolean()) {
            return;
        }
        float volume = IsoMapConfig.SOUND_VOLUME.getAsInt() / 100f * gain;
        long now = System.nanoTime();
        if (volume <= 0f || (lastPlayed != 0L && now - lastPlayed < cooldownNanos)) {
            return;
        }
        lastPlayed = now;
        playCount++;
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(event, 1f, volume));
    }

    public static void toggle(boolean on) {
        (on ? TOGGLE_ON : TOGGLE_OFF).play();
    }

    /** How many times the sound actually started playing; lets the QA harness observe cooldowns and mute switches. */
    public int playCount() {
        return playCount;
    }
}
