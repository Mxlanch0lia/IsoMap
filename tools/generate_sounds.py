#!/usr/bin/env python3
"""Synthesises the interface sounds from nothing but sine waves, so the assets carry no third-party rights (CC0).

Usage:
    python -m pip install numpy soundfile
    python tools/generate_sounds.py
"""
from pathlib import Path

import numpy as np
import soundfile as sf

RATE = 44100
OUT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/isomap/sounds/ui"


def tone(freq, seconds, decay, gain=1.0, attack=0.004, glide_to=None, harmonic=0.12):
    """A soft plucked tone: a sine with a quiet octave, a short fade-in and an exponential tail."""
    n = int(RATE * seconds)
    t = np.arange(n) / RATE
    end = freq if glide_to is None else glide_to
    freq_curve = np.linspace(freq, end, n)
    phase = 2 * np.pi * np.cumsum(freq_curve) / RATE
    wave = np.sin(phase) + harmonic * np.sin(2 * phase)
    envelope = np.exp(-t * decay) * np.clip(t / attack, 0.0, 1.0)
    # The last few milliseconds fade to exact zero, avoiding a click at the end of the file.
    envelope *= np.clip((seconds - t) / 0.008, 0.0, 1.0)
    return gain * wave * envelope


def sequence(*notes, gap=0.05):
    """Overlays notes that start one after another."""
    total = int(RATE * (gap * (len(notes) - 1))) + max(len(n) for n in notes)
    out = np.zeros(total)
    for i, note in enumerate(notes):
        start = int(RATE * gap * i)
        out[start:start + len(note)] += note
    return out


def write(name, samples, peak):
    samples = samples / np.max(np.abs(samples)) * peak
    OUT.mkdir(parents=True, exist_ok=True)
    sf.write(OUT / f"{name}.ogg", samples.astype(np.float32), RATE, format="OGG", subtype="VORBIS")
    print(f"{name}.ogg  {len(samples) / RATE * 1000:.0f} ms  peak {peak}")


write("hover", tone(1320, 0.05, 90, attack=0.002), 0.16)
write("click", tone(780, 0.07, 70, attack=0.001), 0.36)
write("toggle_on", tone(620, 0.09, 45, glide_to=860), 0.36)
write("toggle_off", tone(860, 0.09, 45, glide_to=620), 0.36)
write("open", sequence(tone(520, 0.14, 26), tone(780, 0.16, 24), gap=0.05), 0.34)
write("close", sequence(tone(780, 0.12, 28), tone(520, 0.14, 26), gap=0.05), 0.30)
write("waypoint_add", sequence(tone(660, 0.14, 24), tone(880, 0.14, 24), tone(1100, 0.20, 18), gap=0.06), 0.40)
write("waypoint_remove", sequence(tone(620, 0.12, 28, glide_to=520), tone(410, 0.18, 22, glide_to=340), gap=0.07), 0.36)
write("ping", sequence(tone(1180, 0.10, 34, harmonic=0.3), tone(1570, 0.22, 16, harmonic=0.3), gap=0.07), 0.42)
