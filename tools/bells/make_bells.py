#!/usr/bin/env python3
"""
make_bells.py: synthesises the custom bell sounds that ship with YABA.

Every sound is built from scratch in this file: sine partials, filtered noise, envelopes and a little
physics (struck modes, reeds, brass, strings, tonewheels). No samples, no recordings, nothing imitated.
Output is deterministic: all randomness comes from fixed seeds.

    python3 tools/bells/make_bells.py                 render all ten into app/src/main/res/raw, then analyse
    python3 tools/bells/make_bells.py tram honk       render a subset
    python3 tools/bells/make_bells.py --out /tmp/b    render somewhere else (for listening)
    python3 tools/bells/make_bells.py --analyse       only analyse what is already in res/raw
    python3 tools/bells/make_bells.py --wav           force 16-bit WAV even if ffmpeg has libvorbis

Format: Ogg Vorbis (ffmpeg -c:a libvorbis -q:a 5) when ffmpeg has libvorbis, else 16-bit PCM WAV.
Both decode with MediaExtractor, and aapt stores both uncompressed, so the app can read them as
android.resource://<package>/raw/bell_<id>. Only one of bell_<id>.ogg / bell_<id>.wav may exist
(Android resource names ignore the extension), so the stale twin is removed on every write.

Target for every sound: mono, 44.1 kHz, 0.6 to 3 s, sample peak -1 dBFS, no clipping, no DC, 5 ms fades,
energy between 500 Hz and 4 kHz for a small, mid-heavy bike speaker, nothing below ~250 Hz worth mentioning.
The bike's converter (BellConverter.kt) keeps 44.1 kHz for anything under 4.5 s, trims leading/trailing
samples below 400/32767 (-38 dBFS) and peak-normalises to 29200, which these files already sit at.

Licence: original work, dedicated to the public domain under CC0 1.0. La Cucaracha is a traditional folk
melody (public domain). No brand sting, trademarked ringtone or copyrighted recording is reproduced.
"""
from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
import tempfile
import wave
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

import numpy as np
from numpy.lib.stride_tricks import sliding_window_view

SR = 44_100
TWO_PI = 2.0 * np.pi
LN_1000 = np.log(1000.0)          # T60: amplitude falls by 60 dB = factor 1000
PEAK_DBFS = -1.0
FADE_S = 0.005
ROOT = Path(__file__).resolve().parents[2]
RAW_DIR = ROOT / "app" / "src" / "main" / "res" / "raw"
FFMPEG = "/opt/homebrew/bin/ffmpeg" if Path("/opt/homebrew/bin/ffmpeg").exists() else (shutil.which("ffmpeg") or "ffmpeg")


# ------------------------------------------------------------------------------------------------ basics

def n_of(seconds: float) -> int:
    return int(round(seconds * SR))


def clock(seconds: float) -> np.ndarray:
    return np.arange(n_of(seconds)) / SR


_NOTE = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}


def hz(name: str) -> float:
    """'F#5' -> 739.99 Hz (equal temperament, A4 = 440 Hz)."""
    semi, rest = _NOTE[name[0]], name[1:]
    while rest and rest[0] in "#b":
        semi += 1 if rest[0] == "#" else -1
        rest = rest[1:]
    return 440.0 * 2.0 ** ((semi + 12 * (int(rest) + 1) - 69) / 12.0)


def cents(c) -> np.ndarray:
    return 2.0 ** (np.asarray(c, dtype=float) / 1200.0)


def integrate(freq: np.ndarray) -> np.ndarray:
    """Instantaneous frequency (Hz per sample) -> running phase (rad). Phase stays continuous through glides."""
    return TWO_PI * np.cumsum(freq) / SR


def smoothstep(x) -> np.ndarray:
    x = np.clip(x, 0.0, 1.0)
    return x * x * (3.0 - 2.0 * x)


def gate(t: np.ndarray, on: float, off: float, attack: float, release: float) -> np.ndarray:
    """0 before `on`, S-curve up to 1 within `attack`, hold until `off`, S-curve down within `release`."""
    return smoothstep((t - on) / attack) * (1.0 - smoothstep((t - off) / release))


def ramp(t: np.ndarray, points) -> np.ndarray:
    """Piecewise-linear curve through (time, value) points, flat outside."""
    times, values = zip(*points)
    return np.interp(t, times, values)


def decay(t: np.ndarray, t60: float) -> np.ndarray:
    """Exponential decay reaching -60 dB after t60 seconds (t < 0 gives 0)."""
    return np.where(t >= 0.0, np.exp(-LN_1000 * np.maximum(t, 0.0) / t60), 0.0)


def follow(x: np.ndarray, rise: float, fall: float | None = None) -> np.ndarray:
    """One-pole smoother with separate rise/fall time constants (seconds)."""
    a_up = np.exp(-1.0 / (rise * SR))
    a_dn = np.exp(-1.0 / ((rise if fall is None else fall) * SR))
    out, s = [], float(x[0])
    for v in x.tolist():
        s = v + (a_up if v > s else a_dn) * (s - v)
        out.append(s)
    return np.asarray(out)


def place(buf: np.ndarray, start: float, sig: np.ndarray, gain: float = 1.0) -> None:
    """Mix `sig` into `buf` starting at `start` seconds (clipped to the buffer)."""
    i0 = n_of(start)
    if i0 >= len(buf):
        return
    m = min(len(sig), len(buf) - i0)
    buf[i0:i0 + m] += gain * sig[:m]


def peak(x: np.ndarray) -> float:
    return float(np.max(np.abs(x))) if len(x) else 0.0


# ------------------------------------------------------------------------------------------------ filters

def spectral(x: np.ndarray, response: Callable[[np.ndarray], np.ndarray], pad: float = 0.25) -> np.ndarray:
    """Zero-phase filter: multiply the spectrum by response(freqs). Padding keeps the wrap-around out."""
    p = n_of(pad)
    n = len(x) + 2 * p
    spec = np.fft.rfft(np.concatenate([np.zeros(p), x, np.zeros(p)]))
    f = np.fft.rfftfreq(n, 1.0 / SR)
    return np.fft.irfft(spec * response(f), n)[p:p + len(x)]


def hp_mag(f, fc, order=2):
    return 1.0 / np.sqrt(1.0 + (fc / np.maximum(f, 1e-3)) ** (2 * order))


def lp_mag(f, fc, order=2):
    return 1.0 / np.sqrt(1.0 + (np.asarray(f) / fc) ** (2 * order))


def bell_eq(f, fc, gain_db, octaves):
    """Peaking EQ with a Gaussian shape in log-frequency (width in octaves)."""
    g = 10.0 ** (gain_db / 20.0)
    return 1.0 + (g - 1.0) * np.exp(-0.5 * (np.log2(np.maximum(f, 1e-3) / fc) / octaves) ** 2)


def resonance(f, fc, q):
    """Magnitude of a 2nd-order band-pass, 1 at fc. Used as a formant over harmonic frequencies."""
    x = np.maximum(np.asarray(f, dtype=float), 1.0) / fc
    return 1.0 / np.sqrt(1.0 + (q * (x - 1.0 / x)) ** 2)


def band_noise(n: int, lo: float, hi: float, seed: int, order: int = 2) -> np.ndarray:
    """Unit-RMS noise band-limited to lo..hi Hz."""
    rng = np.random.default_rng(seed)
    y = spectral(rng.standard_normal(n), lambda f: hp_mag(f, lo, order) * lp_mag(f, hi, order), pad=0.0)
    return y / (np.sqrt(np.mean(y * y)) + 1e-12)


def wander(n: int, seed: int, lo: float, hi: float) -> np.ndarray:
    """Slow random modulation signal (unit RMS) with energy between lo and hi Hz."""
    return band_noise(n, lo, hi, seed, order=2)


def convolve(x: np.ndarray, ir: np.ndarray) -> np.ndarray:
    n = len(x) + len(ir) - 1
    return np.fft.irfft(np.fft.rfft(x, n) * np.fft.rfft(ir, n), n)[:len(x)]


def room(x: np.ndarray, t60: float, wet: float, seed: int, predelay: float = 0.012,
         lo: float = 380.0, hi: float = 5000.0) -> np.ndarray:
    """Small synthetic room: a few early reflections plus a noise tail whose highs die twice as fast."""
    t = clock(t60)
    rng = np.random.default_rng(seed)
    low = spectral(rng.standard_normal(len(t)), lambda f: hp_mag(f, lo) * lp_mag(f, 2200.0), pad=0.0)
    high = spectral(rng.standard_normal(len(t)), lambda f: hp_mag(f, 2200.0) * lp_mag(f, hi), pad=0.0)
    ir = low * decay(t, t60) + 0.6 * high * decay(t, 0.5 * t60)
    scale = 2.0 * np.max(np.abs(ir))
    for d, g in ((0.0071, 0.9), (0.0113, -0.7), (0.0167, 0.55), (0.0229, -0.45), (0.0311, 0.35)):
        ir[n_of(d)] += g * scale
    ir = np.concatenate([np.zeros(n_of(predelay)), ir])
    ir /= np.sqrt(np.sum(ir * ir))
    return x + wet * convolve(x, ir)


def echo(x: np.ndarray, delay: float, feedback: float, wet: float, lp: float) -> np.ndarray:
    """Feedback echo, each repeat darker than the last (closed form in the frequency domain)."""
    n = len(x) + n_of(delay * 40)
    f = np.fft.rfftfreq(n, 1.0 / SR)
    z = feedback * lp_mag(f, lp, 1) * np.exp(-1j * TWO_PI * f * delay)
    y = np.fft.irfft(np.fft.rfft(x, n) * (1.0 + wet * z / (1.0 - z)), n)
    return y[:len(x)]


# ------------------------------------------------------------------------------------------------ oscillators

def harmonics(f0: np.ndarray, amp_of: Callable[[int, np.ndarray], np.ndarray], *, ratio=None,
              phases=None, f_top: float = 11_000.0, k_max: int = 120) -> np.ndarray:
    """Band-limited additive tone on a moving fundamental.

    amp_of(k, fk) gives harmonic k's amplitude per sample, fk being its frequency per sample, so spectral
    envelopes (formants, brightness) can follow the pitch. ratio(k) stretches partials (stiff strings).
    Partials fade out between 0.8 * f_top and f_top, so glides never click and nothing aliases."""
    ph = integrate(f0)
    out = np.zeros_like(ph)
    for k in range(1, k_max + 1):
        r = k if ratio is None else ratio(k)
        fk = r * f0
        if fk.min() >= f_top:
            break
        taper = np.clip((f_top - fk) / (0.2 * f_top), 0.0, 1.0)
        p0 = 0.0 if phases is None else phases[(k - 1) % len(phases)]
        out += amp_of(k, fk) * taper * np.sin(r * ph + p0)
    return out


def struck(n: int, hits, modes, f_top: float = 12_000.0) -> np.ndarray:
    """Modal synthesis of a struck metal body.

    hits:  (time s, velocity, hardness Hz, per-mode weights or None). Hardness is the corner frequency of
           the hammer's contact spectrum: harder hammer, brighter strike.
    modes: (freq Hz, amplitude, T60 s, split Hz). Each mode is a doublet split by a few Hz, as in any
           real bell that is not perfectly round, so it warbles instead of sounding like a sine organ.
    Hits add linearly into modes that are still ringing, exactly like the real thing."""
    out = np.zeros(n)
    for t0, vel, hardness, weights in hits:
        i0 = n_of(t0)
        tt = np.arange(n - i0) / SR
        for j, (f, a, t60, split) in enumerate(modes):
            if f >= f_top:
                continue
            w = vel * a / (1.0 + (f / hardness) ** 2) * (1.0 if weights is None else weights[j])
            ring = 0.72 * np.sin(TWO_PI * (f - split / 2) * tt) + 0.28 * np.sin(TWO_PI * (f + split / 2) * tt)
            out[i0:] += w * decay(tt, t60) * ring
    return out


def pulse_amp(k: int, duty) -> np.ndarray:
    """Fourier amplitude of harmonic k of a pulse wave with the given duty cycle (cosine phase)."""
    return (2.0 / (np.pi * k)) * np.sin(np.pi * k * np.asarray(duty))


# ------------------------------------------------------------------------------------------------ dynamics + master

def limit(x: np.ndarray, ceiling: float = 1.0, lookahead: float = 0.002, release: float = 0.06) -> np.ndarray:
    """Look-ahead peak limiter. Gain drops ahead of each peak (held min + box smoothing), recovers
    exponentially. By construction |output| <= ceiling, without hard clipping."""
    w = max(1, n_of(lookahead))
    need = np.minimum(1.0, ceiling / np.maximum(np.abs(x), 1e-12))
    held = sliding_window_view(np.concatenate([need, np.ones(w)]), w + 1).min(axis=1)
    a = np.exp(-1.0 / (release * SR))
    g, s = [], 1.0
    for v in held.tolist():
        s = v if v < s else v + a * (s - v)
        g.append(s)
    g = np.asarray(g)
    c = np.cumsum(np.concatenate([[0.0], np.full(w, g[0]), g]))
    box = (c[w + 1:] - c[:-w - 1]) / (w + 1)
    return x * box


def saturate(x: np.ndarray, drive: float) -> np.ndarray:
    """Soft saturation on a peak-normalised signal (adds the blare of a horn pushed hard)."""
    x = x / (peak(x) + 1e-12)
    return np.tanh(drive * x) / np.tanh(drive)


def master(x: np.ndarray, *, hp: float = 300.0, hp_order: int = 4, lp: float = 7500.0, lp_order: int = 2,
           eq=(), drive_db: float = 3.0, release: float = 0.06, tail: float = 0.0) -> np.ndarray:
    """Speaker voicing + loudness: band-limit to what a small bike speaker can play, drive into the
    limiter, optional end fade, remove DC, 5 ms fades, sample peak at exactly PEAK_DBFS."""
    x = np.asarray(x, dtype=float)
    x = x - np.mean(x)

    def response(f):
        r = hp_mag(f, hp, hp_order) * lp_mag(f, lp, lp_order)
        for fc, gain_db, octaves in eq:
            r = r * bell_eq(f, fc, gain_db, octaves)
        return r

    x = spectral(x, response)
    x = x / (peak(x) + 1e-12)
    x = limit(x * 10.0 ** (drive_db / 20.0), ceiling=1.0, release=release)
    if tail > 0:
        nt = n_of(tail)
        x[-nt:] *= 0.5 + 0.5 * np.cos(np.linspace(0.0, np.pi, nt))
    x = x - np.mean(x)
    nf = n_of(FADE_S)
    fade = np.sin(np.linspace(0.0, np.pi / 2, nf)) ** 2
    x[:nf] *= fade
    x[-nf:] *= fade[::-1]
    return x * (10.0 ** (PEAK_DBFS / 20.0) / peak(x))


# ------------------------------------------------------------------------------------------------ instruments

def reed_horn(t: np.ndarray, base_hz: float, pressure: np.ndarray, *, seed: int, bend_cents: float,
              tilt: float, formants, floor: float, bright_lo: float, bright_span: float,
              jitter_cents: float = 4.0, f_top: float = 11_000.0) -> np.ndarray:
    """A pressure-driven reed into a horn. Pitch and brightness rise with pressure, so attacks scoop up
    and releases droop, the way air horns and bulb horns do."""
    n = len(t)
    p = np.maximum(pressure, 0.0)
    f0 = base_hz * cents(bend_cents * (p - 1.0) + jitter_cents * wander(n, seed, 3.0, 14.0))
    fc = bright_lo + bright_span * p
    rng = np.random.default_rng(seed + 1)
    ripple = 10.0 ** (rng.uniform(-2.0, 2.0, 128) / 20.0)          # fixed per-harmonic character
    phases = rng.uniform(-0.6, 0.6, 128)

    def amp_of(k, fk):
        h = floor + sum(g * resonance(fk, f, q) for f, q, g in formants)
        return ripple[k - 1] * k ** (-tilt) * h / np.sqrt(1.0 + (fk / fc) ** 4)

    return p * harmonics(f0, amp_of, phases=phases, f_top=f_top)


def brass(f0: np.ndarray, amp: np.ndarray, bright: np.ndarray, *, seed: int, low: float, span: float,
          formant_hz, formant_q: float = 1.6, formant_gain: float = 0.9, slope: float = 2.8) -> np.ndarray:
    """Additive brass: the harmonic cut-off climbs with how hard the player blows, which is what makes
    brass sound brassy rather than just loud. `formant_hz` may be an array (plunger mute)."""
    rng = np.random.default_rng(seed)
    phases = rng.uniform(-0.5, 0.5, 128)
    fc = low + span * np.clip(bright, 0.0, 1.5) ** 1.3

    def amp_of(k, fk):
        a = k ** 0.45 / (1.0 + (fk / fc) ** slope)
        return a * (1.0 + formant_gain * resonance(fk, formant_hz, formant_q))

    return amp * harmonics(f0, amp_of, phases=phases, f_top=11_000.0)


@dataclass
class Note:
    pitch: float     # Hz
    on: float        # s
    length: float    # s
    level: float = 1.0


def voice_curves(t: np.ndarray, notes: list[Note], *, attack: float, release: float, scoop_cents: float,
                 scoop_time: float, accent: float = 0.0):
    """Pitch and amplitude curves for one monophonic, tongued voice (continuous phase across notes)."""
    f0 = np.full(len(t), notes[0].pitch)
    amp = np.zeros(len(t))
    for i, nt in enumerate(notes):
        nxt = notes[i + 1].on if i + 1 < len(notes) else t[-1] + 1.0
        seg = (t >= nt.on - 0.004) & (t < nxt - 0.004)
        rel = t[seg] - nt.on
        f0[seg] = nt.pitch * cents(-scoop_cents * (1.0 - smoothstep(rel / scoop_time)))
        env = gate(t, nt.on, nt.on + nt.length, attack, release)
        if accent:
            env = env * (1.0 + accent * decay(t - nt.on, 0.25))
        amp += nt.level * env
    return f0, amp


# ------------------------------------------------------------------------------------------------ the ten sounds

def snd_tram() -> np.ndarray:
    """Zagreb tram gong, the driver stamps the pedal twice: ding ... ding."""
    dur = 2.5
    n = n_of(dur)
    f1 = 612.0
    partials = [  # ratio to f1, amplitude, T60 s, doublet split Hz (inharmonic, gong-like)
        (1.000, 1.00, 2.40, 0.8),
        (1.587, 0.45, 1.80, 1.4),
        (2.119, 0.85, 1.45, 0.6),
        (2.756, 0.40, 1.05, 1.9),
        (3.374, 0.38, 0.80, 1.2),
        (4.081, 0.28, 0.55, 2.4),
        (4.917, 0.20, 0.38, 1.6),
        (5.866, 0.13, 0.26, 3.1),
    ]
    modes = [(f1 * r, a, t60, s) for r, a, t60, s in partials]
    hits = [
        (0.002, 1.00, 4200.0, None),
        (0.502, 0.82, 3600.0, [1.0, 1.25, 0.85, 1.3, 1.0, 0.8, 1.1, 1.0]),   # half a second later, a bit off-centre
    ]
    x = struck(n, hits, modes)
    tc = clock(0.06)
    for i, (t0, vel, _, _) in enumerate(hits):
        click = band_noise(len(tc), 1800.0, 8000.0, seed=100 + i) * decay(tc, 0.010)
        clunk = np.sin(TWO_PI * 340.0 * tc) * decay(tc, 0.09)
        place(x, t0, vel * (0.30 * click + 0.18 * clunk))
    return master(x, hp=330.0, lp=7000.0, drive_db=6.0, release=0.15, tail=0.35)


def snd_stadium() -> np.ndarray:
    """Canister air horn in a stadium: blast, blast, BLAAAST."""
    dur = 1.95
    t = clock(dur)
    n = len(t)
    blasts = [(0.002, 0.27, 1.0), (0.40, 0.27, 1.0), (0.80, 0.98, 1.04)]
    p = np.zeros(n)
    for on, length, level in blasts:
        sag = 1.0 - 0.07 * np.clip((t - on) / length, 0.0, 1.0)              # the canister cools down
        kick = 1.0 + 0.12 * decay(t - on, 0.12)                               # valve opens with a jolt
        p += level * gate(t, on, on + length, 0.018, 0.05) * sag * kick
    formants = [(1150.0, 2.2, 1.0), (2500.0, 3.0, 1.6), (3900.0, 4.0, 0.6)]
    horn = reed_horn(t, 405.0, p, seed=200, bend_cents=140.0, tilt=0.55, formants=formants, floor=0.35,
                     bright_lo=1200.0, bright_span=2600.0, jitter_cents=6.0)
    horn += 0.6 * reed_horn(t, 405.0 * cents(9.0), p, seed=210, bend_cents=140.0, tilt=0.55,
                            formants=formants, floor=0.35, bright_lo=1200.0, bright_span=2600.0,
                            jitter_cents=6.0)
    horn *= 1.0 + 0.10 * wander(n, 220, 20.0, 70.0)                           # reed flutter, the rasp
    rms = np.sqrt(np.mean(horn ** 2))
    air = band_noise(n, 1800.0, 6000.0, seed=230) * p * 0.06 * rms
    spit = np.zeros(n)
    for i, (on, _, _) in enumerate(blasts):
        place(spit, on, band_noise(n_of(0.08), 1000.0, 5000.0, seed=240 + i) * decay(clock(0.08), 0.06))
    x = saturate(horn + air + 0.15 * rms * spit, 1.6)
    return master(x, hp=320.0, lp=6500.0, drive_db=2.5, release=0.05)


def snd_trombone() -> np.ndarray:
    """Sad trombone with a plunger mute: wah, wah, wah, waaaah (with the wobble)."""
    dur = 2.75
    t = clock(dur)
    n = len(t)
    notes = [Note(hz("D4"), 0.002, 0.37, 1.0), Note(hz("C#4"), 0.43, 0.37, 0.95),
             Note(hz("C4"), 0.86, 0.37, 0.9), Note(hz("B3"), 1.29, 1.25, 1.0)]
    f0, amp = voice_curves(t, notes, attack=0.03, release=0.07, scoop_cents=35.0, scoop_time=0.06)
    last = notes[-1]
    tl = t - last.on
    in_last = tl >= -0.004
    # the wobble: vibrato that grows, a plunger that flaps with it, and a final droop
    depth = 45.0 * smoothstep((tl - 0.25) / 0.45)
    vib_phase = integrate(np.full(n, 5.6))
    vib = np.sin(vib_phase)
    droop = -100.0 * smoothstep((tl - (last.length - 0.30)) / 0.30) ** 1.5
    f0 = np.where(in_last, f0 * cents(depth * vib + droop), f0)
    amp = np.where(in_last, amp * ramp(tl, [(0.0, 1.0), (0.35, 1.05), (last.length, 0.55)]), amp)
    # plunger: closed (dark "oo") -> open ("aa") -> closing on each note
    w = np.zeros(n)
    for nt in notes[:-1]:
        r = t - nt.on
        seg = (r >= -0.004) & (r < nt.length + 0.06)
        w[seg] = (0.06 + 0.94 * smoothstep(r[seg] / 0.15)) * (1.0 - 0.8 * smoothstep((r[seg] - (nt.length - 0.13)) / 0.13))
    flap = (depth / 45.0) * 0.35 * vib
    w = np.where(in_last, (0.06 + 0.94 * smoothstep(tl / 0.18)) * (0.65 + flap)
                 * (1.0 - 0.6 * smoothstep((tl - (last.length - 0.25)) / 0.25)), w)
    w = follow(np.clip(w, 0.04, 1.2), 0.01)
    bright = w * np.clip(amp, 0.0, None) ** 0.8
    x = brass(f0, amp, bright, seed=300, low=250.0, span=1900.0, formant_hz=500.0 + 800.0 * w,
              formant_q=2.5, formant_gain=1.2, slope=3.5)
    rms = np.sqrt(np.mean(x ** 2))
    x += band_noise(n, 1000.0, 5000.0, seed=310) * amp * 0.02 * rms
    x = room(x, t60=0.6, wet=0.10, seed=320)
    return master(x, hp=300.0, lp=6500.0, drive_db=3.0, release=0.08, tail=0.12)


def snd_cucaracha() -> np.ndarray:
    """La Cucaracha (traditional, public domain) on a musical car horn: one little trumpet per pitch."""
    dur = 2.42
    t = clock(dur)
    n = len(t)
    e = 0.12                                   # one eighth note
    phrase = [("C5", 1), ("C5", 1), ("C5", 1), ("F5", 2), ("A5", 4)]
    tuning = {"C5": 6.0, "F5": -4.0, "A5": 9.0}   # every trumpet of a real horn is a little off
    formants = [(1700.0, 2.5, 1.4), (3100.0, 3.0, 1.0)]
    x = np.zeros(n)
    air = np.zeros(n)
    for start in (0.002, 1.20):
        pos = start
        for j, (name, slots) in enumerate(phrase):
            length = slots * e * (0.9 if j == len(phrase) - 1 else 0.8)
            p = gate(t, pos, pos + length, 0.012, 0.035) * (1.0 + 0.10 * decay(t - pos, 0.08))
            seed = 400 + 10 * list(tuning).index(name)              # same trumpet, same timbre
            x += reed_horn(t, hz(name) * cents(tuning[name]), p, seed=seed, bend_cents=90.0, tilt=0.35,
                           formants=formants, floor=0.3, bright_lo=2200.0, bright_span=2200.0,
                           jitter_cents=3.0)
            air += p
            pos += slots * e
    rms = np.sqrt(np.mean(x ** 2))
    x += band_noise(n, 2000.0, 7000.0, seed=490) * 0.03 * rms * air
    x = saturate(x, 1.3)
    return master(x, hp=380.0, lp=6500.0, drive_db=3.0, release=0.05)


def snd_ringring() -> np.ndarray:
    """Classic rotary thumb bell on a handlebar: rrring ... rrring."""
    dur = 1.9
    n = n_of(dur)
    modes = [  # freq Hz, amplitude, T60 s, doublet split Hz (small steel dome)
        (905.0, 0.22, 0.90, 1.6),
        (1640.0, 1.00, 1.50, 3.2),
        (2395.0, 0.72, 1.15, 4.6),
        (3320.0, 0.55, 0.85, 2.3),
        (4470.0, 0.30, 0.55, 5.8),
        (5910.0, 0.16, 0.35, 7.1),
    ]
    rng = np.random.default_rng(500)
    hits = []
    for press_on, press_len in ((0.002, 0.27), (0.60, 0.30)):
        tau, k = 0.0, 0
        while tau < press_len:
            u = tau / press_len
            rate = 24.0 + 10.0 * np.sin(np.pi * u)              # the gear spins up, then coasts
            vel = (0.6 + 0.4 * np.sin(np.pi * u)) * (1.0 if k % 2 == 0 else 0.8) * rng.uniform(0.8, 1.2)
            hits.append((press_on + tau + rng.uniform(-0.002, 0.002) + 0.002, vel, 7000.0, None))
            tau += 1.0 / rate
            k += 1
    hits[0] = (0.002,) + hits[0][1:]
    x = struck(n, hits, modes)
    tc = clock(0.01)
    for i, (t0, vel, _, _) in enumerate(hits):
        place(x, t0, 0.25 * vel * band_noise(len(tc), 3000.0, 9000.0, seed=510 + i) * decay(tc, 0.004))
    return master(x, hp=520.0, lp=8500.0, drive_db=5.0, release=0.06, tail=0.3)


def slap_bass(f0_hz: float, length: float, *, kind: str, seed: int, glide: float = 0.0,
              glide_start: float = 0.08, glide_time: float = 0.35) -> np.ndarray:
    """Stiff-string bass. 'slap' = thumb on the string, 'pop' = finger snapping it back onto the frets,
    'ghost' = left hand muting it (all click, no note)."""
    t = clock(length + 0.08)
    n = len(t)
    pos, tilt, bright = {"slap": (0.19, 0.8, 1.0), "pop": (0.12, 0.6, 1.4), "ghost": (0.19, 0.8, 0.4)}[kind]
    tau1 = 0.9 if kind != "ghost" else 0.02
    f0 = f0_hz * 2.0 ** (glide * smoothstep((t - glide_start) / glide_time))
    stiffness = 0.00035
    mute = 1.0 - smoothstep((t - length) / 0.04)
    onset = smoothstep(t / 0.0006)

    def amp_of(k, fk):
        a = abs(np.sin(np.pi * k * pos)) / k ** tilt
        tau = tau1 / (1.0 + 0.08 * (k - 1) ** 1.5 / bright)
        return a * np.exp(-t / tau)

    tone = harmonics(f0, amp_of, ratio=lambda k: k * np.sqrt(1.0 + stiffness * k * k), f_top=9000.0)
    tone *= mute * onset
    clank = band_noise(n, 1200.0, 5000.0, seed=seed) * decay(t, 0.03)
    clank_level = {"slap": 0.10, "pop": 0.16, "ghost": 0.22}[kind]
    return tone + clank_level * clank * np.max(np.abs(tone[: n_of(0.02)]) + 1e-9) * 3.0


def rimshot(seed: int, accent: float = 1.0) -> np.ndarray:
    """Cross-stick on a snare rim: a woody tock with a crack on top."""
    t = clock(0.12)
    body = (0.55 * np.sin(TWO_PI * 480.0 * t) * decay(t, 0.09)
            + 0.25 * np.sin(TWO_PI * 820.0 * t + 0.4) * decay(t, 0.12)
            + 0.80 * np.sin(TWO_PI * 1720.0 * t) * decay(t, 0.055)
            + 0.35 * np.sin(TWO_PI * 3350.0 * t) * decay(t, 0.035))
    crack = 0.7 * band_noise(len(t), 1800.0, 7000.0, seed=seed) * decay(t, 0.028)
    return accent * (body + crack) * smoothstep(t / 0.0004)


def organ(chord: list[float], length: float, *, seed: int) -> np.ndarray:
    """Tonewheel organ stab: drawbars 00 8764 002, fast percussion on the third, key click, fast Leslie."""
    t = clock(length + 0.09)
    n = len(t)
    drawbars = {1.0: 8, 2.0: 7, 3.0: 6, 4.0: 4, 8.0: 2}               # footage multiple -> bar setting
    env = gate(t, 0.0, length, 0.004, 0.04)
    rng = np.random.default_rng(seed)
    leslie_ph = TWO_PI * 6.7 * t
    x = np.zeros(n)
    for f in chord:
        voice = np.zeros(n)
        for mult, bar in drawbars.items():
            level = 10.0 ** ((bar - 8) * 3.0 / 20.0)
            if mult == 3.0:
                level *= 1.0 + 1.6 * decay(t, 0.45)                       # percussion, fast decay
            fr = f * mult * (1.0 + 0.0025 * np.sin(leslie_ph + mult))     # Doppler from the rotor
            voice += level * np.sin(integrate(fr) + rng.uniform(0, TWO_PI))
        voice *= 1.0 + 0.18 * np.sin(leslie_ph + rng.uniform(0, TWO_PI))
        click = band_noise(n, 800.0, 5000.0, seed=seed + int(f)) * decay(t, 0.006) * 0.35
        x += voice * env + click
    return np.tanh(0.9 * x / peak(x)) / np.tanh(0.9)


def snd_nightshift() -> np.ndarray:
    """Original after-hours lounge groove: swung slap bass, cross-stick, organ push, cheeky slide."""
    dur = 2.72
    n = n_of(dur)
    bass = np.zeros(n)
    hits = [  # (time s, note, length s, kind, glide octaves)
        (0.002, "D3", 0.28, "slap", 0.0),
        (0.333, "D3", 0.03, "ghost", 0.0),
        (0.500, "D4", 0.14, "pop", 0.0),
        (0.833, "F3", 0.14, "slap", 0.0),
        (1.000, "G3", 0.15, "slap", 0.0),
        (1.167, "G#3", 0.08, "slap", 0.0),
        (1.333, "A3", 0.12, "pop", 0.0),
        (1.500, "A3", 0.03, "ghost", 0.0),
        (1.667, "C4", 0.10, "pop", 0.0),
        (1.833, "C#3", 0.12, "slap", 0.0),
        (2.000, "D3", 0.55, "slap", 1.0),          # the cheeky bwoop up an octave
    ]
    for i, (t0, name, length, kind, glide) in enumerate(hits):
        level = 0.55 if kind == "ghost" else 1.0
        place(bass, t0, slap_bass(hz(name), length, kind=kind, seed=600 + i, glide=glide), level)
    rim = np.zeros(n)
    for i, (t0, acc) in enumerate(((0.5, 1.0), (1.333, 0.45), (1.5, 1.0), (1.833, 0.8), (2.0, 1.1))):
        place(rim, t0, rimshot(seed=650 + i, accent=acc))
    keys = np.zeros(n)
    place(keys, 1.833, organ([hz(x) for x in ("C#4", "F4", "B4", "D#5", "G#5")], 0.11, seed=670), 0.8)
    place(keys, 2.000, organ([hz(x) for x in ("D4", "F#4", "C5", "E5", "A5")], 0.42, seed=680), 1.0)
    mix = (bass / peak(bass)) * 1.0 + (rim / peak(rim)) * 0.5 + keys * 0.75
    mix = room(mix, t60=0.5, wet=0.08, seed=690)
    return master(mix, hp=350.0, hp_order=2, lp=7500.0, eq=((1000.0, 3.0, 1.0),), drive_db=4.0,
                  release=0.07, tail=0.15)


def nes_volume(t: np.ndarray, env: np.ndarray) -> np.ndarray:
    """Quantise a volume curve the way an 8-bit sound chip does: 16 steps, updated 60 times a second."""
    frame = np.floor(t * 60.0) / 60.0
    stepped = np.round(np.interp(frame, t, env) * 15.0) / 15.0
    return follow(stepped, 0.0008)


def snd_jingle() -> np.ndarray:
    """YABA chiptune jingle, five notes: ya-ba, ya-ba, YAAA."""
    dur = 1.38
    t = clock(dur)
    n = len(t)
    melody = [("C5", 0.002, 0.09), ("F5", 0.10, 0.12), ("Ab5", 0.30, 0.07), ("A5", 0.37, 0.11), ("F6", 0.56, 0.55)]
    final_on = melody[-1][1]

    def channel(delay: float, duty_main: float, vol: float) -> np.ndarray:
        f0 = np.full(n, hz(melody[0][0]))
        env = np.zeros(n)
        duty = np.full(n, duty_main)
        for name, on, length in melody:
            on += delay
            seg = t >= on - 0.002
            f0[seg] = hz(name)
            r = t - on
            if name == "F6":
                vib = 28.0 * smoothstep((r - 0.15) / 0.1) * np.sin(TWO_PI * 6.2 * np.maximum(r, 0.0))
                f0[seg] = hz(name) * cents(vib[seg])
                duty[seg] = np.where(r[seg] < 0.05, 0.125, np.where(r[seg] < 0.10, 0.25, duty_main))
                shape = ramp(r, [(0.0, 1.0), (0.12, 0.85), (length, 0.0)])
            else:
                shape = ramp(r, [(0.0, 1.0), (0.10, 0.66), (1.0, 0.66)])
            env += gate(t, on, on + length, 0.004, 0.012) * shape
        duty = follow(duty, 0.002)
        vol_curve = nes_volume(t, np.clip(env, 0.0, 1.0)) * vol
        return vol_curve * harmonics(f0, lambda k, fk: pulse_amp(k, duty), phases=[np.pi / 2], f_top=12_000.0)

    x = channel(0.0, 0.25, 1.0) + channel(0.09, 0.5, 0.35)
    # triangle channel doubles the last note an octave down
    tri_t = t - final_on
    tri = sum((8.0 / np.pi ** 2) * ((-1) ** ((k - 1) // 2)) / k ** 2 * np.sin(k * TWO_PI * hz("F5") * tri_t)
              for k in (1, 3, 5, 7, 9))
    x += 0.2 * tri * gate(t, final_on, final_on + 0.30, 0.002, 0.004)
    # noise channel: one short 15-bit LFSR hiss on the last note
    reg, bits = 1, []
    for _ in range(n_of(0.06) // 2 + 1):
        fb = (reg ^ (reg >> 1)) & 1
        reg = (reg >> 1) | (fb << 14)
        bits.append(1.0 if reg & 1 else -1.0)
    hiss = np.repeat(np.asarray(bits), 2)[: n_of(0.06)] * decay(clock(0.06), 0.06)
    place(x, final_on, 0.18 * hiss)
    return master(x, hp=400.0, hp_order=2, lp=8000.0, drive_db=2.5, release=0.05, tail=0.1)


def snd_ufo() -> np.ndarray:
    """Theremin flying saucer: hovers, wobbles faster and faster, then zooms off."""
    dur = 2.6
    t = clock(dur)
    n = len(t)
    centre = np.exp(ramp(t, [(0.0, np.log(560.0)), (0.25, np.log(880.0)), (0.9, np.log(1180.0)),
                             (1.5, np.log(760.0)), (1.75, np.log(820.0)), (2.45, np.log(2400.0))]))
    rate = ramp(t, [(0.0, 4.5), (0.3, 6.0), (1.5, 10.5), (2.5, 13.0)])
    depth = ramp(t, [(0.0, 0.0), (0.25, 90.0), (0.6, 210.0), (1.6, 240.0), (2.5, 120.0)])
    lfo = np.sin(integrate(rate))
    f0 = centre * cents(depth * lfo)
    amp = gate(t, 0.002, 2.2, 0.12, 0.3) * (1.0 + 0.15 * np.sin(integrate(rate) - 0.8))
    partial = {1: 1.0, 2: 0.22, 3: 0.10, 4: 0.035, 5: 0.02}
    tone = harmonics(f0, lambda k, fk: partial.get(k, 0.0), k_max=5)
    tone = amp * tone
    tone += 0.18 * tone * np.sin(TWO_PI * 37.0 * t)                    # saucer shimmer (ring mod)
    tone = echo(tone, delay=0.13, feedback=0.38, wet=0.5, lp=3000.0)
    return master(tone, hp=420.0, lp=6500.0, drive_db=4.0, release=0.08, tail=0.15)


def snd_honk() -> np.ndarray:
    """Clown bulb horn: HONK-HONK, with the bulb sucking air back in."""
    dur = 0.78
    t = clock(dur)
    n = len(t)
    honks = [(0.002, 0.19, 1.0), (0.27, 0.24, 1.08)]
    p = np.zeros(n)
    for on, length, level in honks:
        u = np.clip((t - on) / length, 0.0, 1.0)
        p += level * gate(t, on, on + length - 0.05, 0.02, 0.05) * (1.0 + 0.08 * np.sin(np.pi * u))
    formants = [(1000.0, 2.5, 0.8), (1500.0, 3.5, 1.6), (2700.0, 4.0, 0.7)]
    horn = reed_horn(t, 395.0, p, seed=900, bend_cents=160.0, tilt=0.3, formants=formants, floor=0.15,
                     bright_lo=1800.0, bright_span=2500.0, jitter_cents=5.0)
    # period doubling at full squeeze: odd half-harmonics give the rubbery rasp
    f_half = 395.0 / 2.0 * cents(160.0 * (p - 1.0))
    rasp = smoothstep((p - 0.7) / 0.3)
    horn += rasp * harmonics(f_half, lambda k, fk: (k % 2) * 0.16 * (0.15 + sum(
        g * resonance(fk, f, q) for f, q, g in formants)), f_top=9000.0)
    inhale = np.zeros(n)
    for i, (on, length, _) in enumerate(honks):
        tt = clock(0.13)
        place(inhale, on + length + 0.005, band_noise(len(tt), 2000.0, 6000.0, seed=910 + i) * gate(tt, 0, 0.05, 0.02, 0.07))
    x = saturate(horn, 1.5) + 0.035 * inhale
    return master(x, hp=380.0, lp=6500.0, drive_db=5.0, release=0.05, tail=0.08)


def snd_fanfare() -> np.ndarray:
    """Original brass fanfare in D: two trumpets and a trombone, dotted call, scalar turn, big chord."""
    dur = 2.4
    t = clock(dur)
    n = len(t)
    rng = np.random.default_rng(1000)

    def notes(spec, level=1.0):
        out = []
        for name, on, length in spec:
            out.append(Note(hz(name), max(0.002, on + rng.uniform(-0.005, 0.005)), length, level))
        return out

    tp1 = notes([("D5", 0.0, 0.27), ("A4", 0.30, 0.085), ("D5", 0.40, 0.18), ("F#5", 0.60, 0.18),
                 ("E5", 0.80, 0.06), ("F#5", 0.867, 0.06), ("G5", 0.933, 0.06), ("A5", 1.0, 0.75)])
    tp2 = notes([("A4", 0.0, 0.27), ("F#4", 0.30, 0.085), ("A4", 0.40, 0.18), ("D5", 0.60, 0.18),
                 ("C#5", 0.80, 0.06), ("D5", 0.867, 0.06), ("E5", 0.933, 0.06), ("F#5", 1.0, 0.75)], 0.8)
    tbn = notes([("D4", 0.0, 0.27), ("A3", 0.40, 0.38), ("D4", 1.0, 0.75)], 0.75)
    x = np.zeros(n)
    for voice, (spec, seed, detune, low, span, formant) in enumerate(((tp1, 1010, 0.0, 650.0, 3300.0, 1250.0),
                                                                      (tp2, 1020, -5.0, 650.0, 3300.0, 1250.0),
                                                                      (tbn, 1030, 3.0, 420.0, 1900.0, 900.0))):
        f0, amp = voice_curves(t, spec, attack=0.014, release=0.03, scoop_cents=30.0, scoop_time=0.025,
                               accent=0.25)
        final = spec[-1]
        tf = t - final.on
        swell = np.where(tf >= 0.0, ramp(tf, [(0.0, 0.85), (0.6, 1.1)]), 1.0)
        vib = 10.0 * smoothstep((tf - 0.3) / 0.15) * np.sin(TWO_PI * 5.5 * np.maximum(tf, 0.0) + voice)
        f0 = f0 * cents(detune + np.where(tf >= 0.0, vib, 0.0))
        amp = amp * swell
        bright = follow(amp, 0.025, 0.06)
        x += brass(f0, amp, bright, seed=seed, low=low, span=span, formant_hz=formant)
    x += band_noise(n, 1500.0, 6000.0, seed=1090) * follow(np.abs(x), 0.01) * 0.04
    x = room(saturate(x, 1.3), t60=1.0, wet=0.18, seed=1095)
    return master(x, hp=300.0, lp=6500.0, drive_db=4.5, release=0.08, tail=0.25)


# ------------------------------------------------------------------------------------------------ registry

@dataclass
class Sound:
    id: str
    emoji: str
    title: str
    line: str
    render: Callable[[], np.ndarray]


SOUNDS = [
    Sound("tram", "🚋", "Tram", "Zagreb tram gong, two pedal strikes: ding ... ding, inharmonic partials", snd_tram),
    Sound("stadium", "📣", "Stadium horn", "Canister air horn, three blasts, the last one long", snd_stadium),
    Sound("trombone", "😢", "Sad trombone", "Plunger-muted trombone, wah wah wah waaah, wobble on the last", snd_trombone),
    Sound("cucaracha", "🪳", "La Cucaracha", "Folk tune (public domain) on a bright multi-trumpet car horn", snd_cucaracha),
    Sound("ringring", "🚲", "Ring ring", "Rotary thumb bell, two lever pushes: rrring-rrring", snd_ringring),
    Sound("nightshift", "🌙", "Night shift", "Original swung lounge sting: slap bass, cross-stick, organ push, bass slide", snd_nightshift),
    Sound("jingle", "👾", "YABA jingle", "Five-note chiptune: pulse waves, echo channel, vibrato on the top note", snd_jingle),
    Sound("ufo", "🛸", "UFO", "Theremin saucer: hovers, wobbles faster, zooms off upwards", snd_ufo),
    Sound("honk", "🤡", "Clown honk", "Rubber bulb horn, honk-honk with a rasp, bulb refilling", snd_honk),
    Sound("fanfare", "🎺", "Fanfare", "Original brass fanfare in D, two trumpets and a trombone", snd_fanfare),
]


# ------------------------------------------------------------------------------------------------ files

def has_libvorbis() -> bool:
    try:
        r = subprocess.run([FFMPEG, "-hide_banner", "-encoders"], capture_output=True, text=True, check=False)
    except OSError:
        return False
    return any(line.split()[1:2] == ["libvorbis"] for line in r.stdout.splitlines() if line.strip())


def to_pcm16(x: np.ndarray) -> np.ndarray:
    return np.clip(np.round(x * 32767.0), -32768, 32767).astype("<i2")


def write_wav(path: Path, x: np.ndarray) -> None:
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(to_pcm16(x).tobytes())


def export(sound: Sound, x: np.ndarray, out_dir: Path, ogg: bool) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    ext = ".ogg" if ogg else ".wav"
    dest = out_dir / f"bell_{sound.id}{ext}"
    if ogg:
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / "in.wav"
            write_wav(src, x)
            subprocess.run([FFMPEG, "-v", "error", "-y", "-i", str(src), "-map_metadata", "-1",
                            "-fflags", "+bitexact", "-flags:a", "+bitexact", "-c:a", "libvorbis", "-q:a", "5",
                            "-metadata", f"title=YABA bell: {sound.title}", "-metadata", "copyright=CC0 1.0",
                            str(dest)], check=True)
    else:
        write_wav(dest, x)
    twin = out_dir / f"bell_{sound.id}{'.wav' if ogg else '.ogg'}"
    if twin.exists():
        twin.unlink()                    # Android resource names ignore the extension: keep exactly one
    return dest


def decode(path: Path) -> np.ndarray:
    """Decode any audio file to mono float PCM via ffmpeg (what the analysis looks at)."""
    raw = subprocess.run([FFMPEG, "-v", "error", "-i", str(path), "-f", "s16le", "-acodec", "pcm_s16le",
                          "-ac", "1", "-ar", str(SR), "-"], capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype="<i2").astype(float)


# ------------------------------------------------------------------------------------------------ analysis

def db(v: float) -> float:
    return 20.0 * np.log10(max(v, 1e-12))


def frames_power(x: np.ndarray, size: int = 2048, hop: int = 512) -> tuple[np.ndarray, np.ndarray]:
    x = np.concatenate([x, np.zeros(size)])
    fr = sliding_window_view(x, size)[::hop] * np.hanning(size)
    return np.abs(np.fft.rfft(fr, axis=1)) ** 2, np.fft.rfftfreq(size, 1.0 / SR)


def analyse_one(s16: np.ndarray) -> dict:
    x = s16 / 32768.0
    pw, f = frames_power(x)
    mag = np.sqrt(pw).sum(axis=0)
    total = pw.sum()
    band = pw[:, (f >= 500) & (f <= 4000)].sum() / total
    sub = pw[:, f < 150].sum() / total
    hi = pw[:, f > 6000].sum() / total
    win = n_of(0.4)
    c = np.cumsum(np.concatenate([[0.0], x * x]))
    short_rms = np.sqrt(np.max((c[win:] - c[:-win]) / win)) if len(x) > win else np.sqrt(np.mean(x * x))
    loud = np.nonzero(np.abs(s16) >= 400)[0]          # what BellConverter's trimSilence keeps
    up = np.fft.irfft(np.fft.rfft(x), 4 * len(x)) * 4  # 4x oversampled for the true peak
    return {
        "dur": len(x) / SR,
        "kept": (loud[-1] - loud[0] + 1) / SR if len(loud) else 0.0,
        "peak": db(np.max(np.abs(x))),
        "true_peak": db(np.max(np.abs(up))),
        "rms": db(np.sqrt(np.mean(x * x))),
        "rms400": db(short_rms),
        "centroid": float((f * mag).sum() / mag.sum()),
        "band": band, "sub": sub, "hi": hi,
        "clipped": int(np.sum((s16 >= 32767) | (s16 <= -32768))),
        "dc": db(abs(np.mean(x))),
        "edge": int(max(abs(s16[0]), abs(s16[-1]))),
    }


def fingerprint(s16: np.ndarray, slices: int = 24) -> np.ndarray:
    """Spectro-temporal fingerprint: third-octave levels (250 Hz to 8 kHz) over 24 slices of the audible part."""
    x = s16 / 32768.0
    pw, f = frames_power(x, 1024, 256)
    centres = 250.0 * 2.0 ** (np.arange(16) / 3.0)
    bands = np.stack([pw[:, (f >= c / 2 ** (1 / 6)) & (f < c * 2 ** (1 / 6))].sum(axis=1) for c in centres], axis=1)
    level = 10.0 * np.log10(bands + 1e-12)
    energy = 10.0 * np.log10(pw.sum(axis=1) + 1e-12)
    active = np.nonzero(energy > energy.max() - 40.0)[0]
    level = level[active[0]:active[-1] + 1]
    idx = np.linspace(0, len(level), slices + 1).astype(int)
    fp = np.stack([level[a:max(b, a + 1)].mean(axis=0) for a, b in zip(idx[:-1], idx[1:])])
    fp = np.maximum(fp, fp.max() - 50.0)
    return ((fp - fp.mean()) / (fp.std() + 1e-12)).ravel()


def find_file(out_dir: Path, sid: str) -> Path | None:
    for ext in (".ogg", ".wav"):
        p = out_dir / f"bell_{sid}{ext}"
        if p.exists():
            return p
    return None


def analyse(out_dir: Path, ids: list[str]) -> bool:
    rows, prints, ok = [], {}, True
    for s in SOUNDS:
        if s.id not in ids:
            continue
        path = find_file(out_dir, s.id)
        if path is None:
            print(f"missing: bell_{s.id}")
            ok = False
            continue
        s16 = decode(path)
        m = analyse_one(s16)
        m["size"] = path.stat().st_size
        m["file"] = path.name
        rows.append((s, m))
        prints[s.id] = fingerprint(s16)
        problems = []
        if not 0.6 <= m["dur"] <= 3.0: problems.append("duration")
        if m["clipped"]: problems.append("clipping")
        if abs(m["peak"] - PEAK_DBFS) > 0.3: problems.append("peak")
        if m["dc"] > -60.0: problems.append("dc")
        if m["edge"] > 2: problems.append("fades")
        if m["band"] < 0.6: problems.append("band")
        if m["sub"] > 0.01: problems.append("sub-bass")
        m["problems"] = problems
        ok &= not problems

    print("| id | emoji | file | dur s | kept s | peak dBFS | true pk | RMS dBFS | RMS400 | centroid Hz | 0.5-4k | <150 | >6k | clip | DC dBFS | kB | check |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for s, m in rows:
        print(f"| {s.id} | {s.emoji} | {m['file']} | {m['dur']:.2f} | {m['kept']:.2f} | {m['peak']:.2f} | {m['true_peak']:.2f} "
              f"| {m['rms']:.1f} | {m['rms400']:.1f} | {m['centroid']:.0f} | {m['band']:.0%} | {m['sub']:.2%} "
              f"| {m['hi']:.1%} | {m['clipped']} | {m['dc']:.0f} | {m['size'] / 1000:.0f} "
              f"| {'ok' if not m['problems'] else ','.join(m['problems'])} |")
    if len(prints) > 1:
        keys = list(prints)
        mat = np.corrcoef(np.stack([prints[k] for k in keys]))
        np.fill_diagonal(mat, -1.0)
        print("\nNearest neighbour (fingerprint correlation, 1 = identical):")
        for i, k in enumerate(keys):
            j = int(np.argmax(mat[i]))
            print(f"  {k:<11} ~ {keys[j]:<11} {mat[i, j]:.2f}")
        worst = float(mat.max())
        if worst > 0.85:
            print(f"too similar: {worst:.2f}")
            ok = False
    print("\nALL CLEAN" if ok else "\nPROBLEMS FOUND")
    return ok


# ------------------------------------------------------------------------------------------------ main

def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("ids", nargs="*", help="subset of sound ids (default: all)")
    ap.add_argument("--out", type=Path, default=RAW_DIR, help=f"output directory (default: {RAW_DIR})")
    ap.add_argument("--wav", action="store_true", help="write 16-bit WAV even if libvorbis is available")
    ap.add_argument("--analyse", action="store_true", help="only analyse existing files")
    args = ap.parse_args()
    known = [s.id for s in SOUNDS]
    ids = args.ids or known
    unknown = sorted(set(ids) - set(known))
    if unknown:
        ap.error(f"unknown id(s): {', '.join(unknown)}; known: {', '.join(known)}")
    if not args.analyse:
        ogg = not args.wav and has_libvorbis()
        if not ogg and not args.wav:
            print("ffmpeg has no libvorbis: writing 16-bit WAV instead")
        for s in SOUNDS:
            if s.id in ids:
                x = s.render()
                assert np.all(np.isfinite(x)), s.id
                print(f"wrote {export(s, x, args.out, ogg)}")
    return 0 if analyse(args.out, ids) else 1


if __name__ == "__main__":
    sys.exit(main())
