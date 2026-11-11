# Bundled bell sounds

Ten short custom bells that ship with YABA, so you can put a fun sound on the bike without hunting for a
file first. Each one is synthesised from scratch by [`make_bells.py`](make_bells.py) out of sine
partials, filtered noise, envelopes and a bit of physics (struck modes, reeds, brass, strings,
tonewheels). No samples, no recordings.

They live in `app/src/main/res/raw/bell_<id>.wav` (or `.ogg`, see below), so the app gets them as
`R.raw.bell_<id>` (listed in `BundledBells.kt`). The app opens them as
`android.resource://<package>/<resource id>` and sends them through the same pipeline as a picked file
(`BellConverter` → VM_SOUND → bike). They are referenced from code, so release resource shrinking keeps them.

| id | | sound | what it is |
|---|---|---|---|
| `tram` | 🚋 | Tram | Zagreb tram gong: the driver stamps the pedal twice, ding ... ding, half a second apart. Eight inharmonic partials, each a slightly split doublet so it warbles like real cast metal, plus hammer click and pedal clunk. |
| `stadium` | 📣 | Stadium horn | Canister air horn: blast, blast, BLAAAST. Two reeds a few cents apart, pitch scoops up as the valve opens and sags as the can cools, reed flutter for the rasp. |
| `trombone` | 😢 | Sad trombone | Plunger-muted trombone, wah wah wah waaah (D4 C#4 C4 B3). The plunger opens and closes on every note; on the last one the vibrato grows, the plunger flaps along and the pitch droops. |
| `cucaracha` | 🪳 | La Cucaracha | The folk tune (C C C F A, twice) on a musical car horn, one little trumpet per pitch, each slightly out of tune like the real thing. |
| `ringring` | 🚲 | Ring ring | Rotary thumb bell, two lever pushes: rrring-rrring. A small steel dome struck by two hammers on a spinning gear (24 to 34 strikes per second), then ringing out. |
| `nightshift` | 🌙 | Night shift | Original after-hours lounge sting: swung slap bass line in D, cross-stick on 2 and 4, a chromatic organ push (C#9 → D9) and a cheeky bass slide up an octave. |
| `jingle` | 👾 | YABA jingle | Five-note chiptune, ya-ba, ya-ba, YAAA (C5 F5, Ab5 A5, F6): pulse waves with 4-bit, 60 Hz volume steps, an echo channel, duty sweep and vibrato on the top note. |
| `ufo` | 🛸 | UFO | Theremin flying saucer: hovers, wobbles faster and faster, then zooms off upwards, with a little ring-mod shimmer and space echo. |
| `honk` | 🤡 | Clown honk | Rubber bulb horn, HONK-HONK: a reed that goes sharp under pressure, period doubling for the rubbery rasp, and the bulb sucking air back in. |
| `fanfare` | 🎺 | Fanfare | Original brass fanfare in D: two trumpets and a trombone, dotted call, scalar turn, held D-major chord with a swell. |

## Sound rules

Every file is mono, 44.1 kHz, 0.6 to 3 s, sample peak exactly -1 dBFS, no clipped samples, no DC,
5 ms fades at both ends. The bike's speaker is small and mid-heavy, so every sound is voiced for it:
energy between 500 Hz and 4 kHz (82 to 100 % of it), high-passed at 300 to 520 Hz so nothing below
~250 Hz eats headroom, a gentle low-pass at 6.5 to 8.5 kHz, and a look-ahead limiter for loudness
without hard clipping.

They also fit the bike converter as they are: under 4.5 s they stay at 44.1 kHz, every file stays
well under the 400 kB limit, and the peak already matches the converter's normalisation target, so
it leaves the level alone. The converter trims everything quieter than -38 dBFS at both ends, so the
"kept" column below is what the bike actually plays.

## Regenerate

Needs Python 3.9+, numpy 1.20+ and ffmpeg (for Ogg export and the analysis).

```bash
python3 tools/bells/make_bells.py                 # render all ten into res/raw, then analyse
python3 tools/bells/make_bells.py tram honk       # only some
python3 tools/bells/make_bells.py --out /tmp/b    # render somewhere else, to listen first
python3 tools/bells/make_bells.py --analyse       # only analyse what is in res/raw
python3 tools/bells/make_bells.py --wav           # force WAV even if libvorbis is there
```

Output is deterministic: every random choice comes from a fixed seed, so a rerun on the same numpy
version gives byte-identical files. The script exits non-zero if a file breaks one of the rules above
or if two sounds get too similar (spectro-temporal fingerprint correlation above 0.85).

**Format.** If ffmpeg has `libvorbis`, files are Ogg Vorbis (`-c:a libvorbis -q:a 5`). The Homebrew
ffmpeg on the build Mac has no libvorbis, so the committed files are 16-bit PCM WAV (about 1.9 MB
for all ten). Both decode with `MediaExtractor` and aapt stores both uncompressed. Android resource
names ignore the extension, so the script deletes the other twin (`bell_<id>.ogg` vs `.wav`) on every
write; two files with the same id would break the build.

To add a sound: write a `snd_<id>()` function that returns a float array and goes through `master()`,
add it to `SOUNDS`, rerun.

## Measured

From `make_bells.py --analyse` (decoded through ffmpeg). Centroid is amplitude-weighted over the
whole file.

| id | duration | kept on bike | peak | RMS | loudest 400 ms | centroid |
|---|---|---|---|---|---|---|
| tram | 2.50 s | 1.87 s | -1.0 dBFS | -17.5 dBFS | -12.4 dBFS | 1119 Hz |
| stadium | 1.95 s | 1.82 s | -1.0 dBFS | -11.8 dBFS | -10.4 dBFS | 2692 Hz |
| trombone | 2.75 s | 2.62 s | -1.0 dBFS | -14.2 dBFS | -11.4 dBFS | 1653 Hz |
| cucaracha | 2.42 s | 2.26 s | -1.0 dBFS | -10.3 dBFS | -8.9 dBFS | 3052 Hz |
| ringring | 1.90 s | 1.67 s | -1.0 dBFS | -13.3 dBFS | -9.7 dBFS | 2552 Hz |
| nightshift | 2.72 s | 2.63 s | -1.0 dBFS | -14.7 dBFS | -9.7 dBFS | 1945 Hz |
| jingle | 1.38 s | 1.17 s | -1.0 dBFS | -11.7 dBFS | -9.6 dBFS | 2978 Hz |
| ufo | 2.60 s | 2.55 s | -1.0 dBFS | -6.9 dBFS | -6.0 dBFS | 1665 Hz |
| honk | 0.78 s | 0.61 s | -1.0 dBFS | -12.9 dBFS | -10.9 dBFS | 2866 Hz |
| fanfare | 2.40 s | 2.20 s | -1.0 dBFS | -13.1 dBFS | -11.2 dBFS | 2847 Hz |

## Licence

Original work. The script and all ten sounds are dedicated to the public domain under
[CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/): use, change and share them freely,
no attribution needed.

La Cucaracha is a traditional Mexican folk song and in the public domain; only its melody is used,
played on a synthesised horn. None of the sounds reproduces or imitates a brand sting, sound mark,
trademarked ringtone or copyrighted recording, and requests for such sounds are deliberately not
bundled. For those, use your own file with the picker in the app.
