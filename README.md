<p align="center">
  <img src="assets/hero.svg" width="100%" alt="YABA. Keeps riding. No matter what.">
</p>

<p align="center">
  <a href="https://github.com/stevendoerr-lachance/yaba/releases/latest"><img src="https://img.shields.io/github/v/release/stevendoerr-lachance/yaba?style=flat-square&label=APK&color=D6ECFF&labelColor=191C21" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-D6ECFF?style=flat-square&labelColor=191C21" alt="Android 8.0 and up">
  <img src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-D6ECFF?style=flat-square&labelColor=191C21" alt="Kotlin, Jetpack Compose">
  <img src="https://img.shields.io/badge/tests-651%20green-D6ECFF?style=flat-square&labelColor=191C21" alt="651 unit tests">
  <img src="https://img.shields.io/badge/license-MIT-D6ECFF?style=flat-square&labelColor=191C21" alt="MIT">
</p>

# The servers went dark. The bike did not.

YABA is a native Android app for VanMoof e-bikes. It talks to the bike directly over Bluetooth LE.
It fetches the bike key from the VanMoof account once, stores it encrypted on the phone (Android
Keystore) and needs no backend after that. No subscription, no login on every start, no cloud in
the loop.

It was built in Frankfurt am Main for one second-hand S3 and for every bike that should keep riding.

<p align="center">
  <img src="assets/screens/ride.png" width="19%" alt="Ride: speed on the top tube, unlock slider, speed region">
  <img src="assets/screens/cockpit.png" width="19%" alt="Cockpit: large speed for a phone on the handlebar">
  <img src="assets/screens/guard.png" width="19%" alt="Guard: the bike watches for movement">
  <img src="assets/screens/sound.png" width="19%" alt="Sound: bell tones and custom sounds">
  <img src="assets/screens/diagnostics.png" width="19%" alt="Diagnostics: ride logger and recordings">
</p>

## What it does

| | What you get |
|---|---|
| **Ride** | Slide to unlock, assist 0 to 4, lights, alarm, touch unlock. The top tube on screen shows what the one on the bike shows. |
| **Cockpit** | Phone on the handlebar: speed in dots you can read at arm's length, gear, assist, and the three signals that decide whether the bike stays awake. |
| **Guard** | Locks the bike, arms its own motion watch, keeps the link with the screen off and tells your phone the moment a wheel turns. Siren right away if you want it. |
| **Sound** | Bell tone, your own bell from any audio file (converted on the phone), ten bundled bells, every sound the bike knows, volume per sound. |
| **Diagnostics** | Live status, the 64 fault bits decoded into sentences, a ride logger that writes one reading every 2 seconds to CSV, and the bike's internal log read out and explained. |
| **Bike** | Firmware versions, odometer, units, backup code, key backup and import, several bikes. |
| **Lab** | E-shifter shift points and Speed++ (see below). |

Seven languages: English, German, Dutch, French, Italian, Croatian, Russian.

## Supported bikes

| Bike | Status |
|---|---|
| VanMoof S3 / X3 | Everything above. Tested on an S3 with firmware 1.9.3. |
| VanMoof Electrified S2 / X2 | Lock, lights, assist, alarm, bell, diagnostics. Protocol ported from OldMoofKit. |

<img src="assets/signs.svg" width="330" align="right" alt="Speed regions: Japan 24, Europe 25, USA 32 km/h">

### Speed regions and Speed++

The bike knows three regions, and YABA shows them as what they are: road signs.

Speed++ sets a 28″ bike to the 24″ wheel setting. The bike then measures 84 % of the real speed,
so assist ends at about 29.7 km/h instead of 25. The bike's own speedo and odometer read low by
the same factor. YABA multiplies by 1.1875 and shows the real speed everywhere. Check what is
legal where you ride.

<br clear="right">

## It folds

On a book-style foldable the app splits at the hinge: the bike on the left, the controls on the right.

<p align="center">
  <img src="assets/screens/foldable.png" width="70%" alt="Two-pane layout on a foldable, split at the hinge">
</p>

## Ten bells, synthesised from scratch

No samples, no recordings. [`tools/bells/make_bells.py`](tools/bells/make_bells.py) builds every
bell out of sine partials, filtered noise and a bit of physics, voiced for the small speaker in
the frame. All ten are CC0.

| | Bell | What you hear |
|---|---|---|
| 🚲 | Ring ring | A rotary thumb bell, two pushes |
| 🚋 | Tram | Zagreb tram gong: ding ... ding |
| 🤡 | Clown honk | Rubber bulb horn, twice |
| 📣 | Stadium horn | Blast, blast, BLAAAST |
| 🎺 | Fanfare | Two trumpets and a trombone in D |
| 🪳 | La Cucaracha | The folk tune on a musical car horn |
| 😢 | Sad trombone | Wah wah wah waaah |
| 🌙 | Night shift | A lounge sting with a bass slide |
| 👾 | YABA jingle | Five notes of chiptune |
| 🛸 | UFO | A theremin that hovers, wobbles and leaves |

## The bike talks back

<p align="center">
  <img src="assets/grumble.svg" width="62%" alt="The top tube grumbles in Dutch: gedoe, balen, nou ja, he he, auw">
</p>

A Dutch bike may complain in Dutch. Now and then the dot matrix in the app says one word, rarely
and never while you ride. The sentence below it stays polite and tells you the number, the cause
and the next step. You can switch it off.

| On the tube | Literally | Meant |
|---|---|---|
| `GEDOE` | fuss | trouble, bother |
| `BALEN` | to be annoyed | what a pity |
| `NOU JA` | well, yes | oh well |
| `HE HE` | at last | phew |
| `AUW` | ouch | that hurt |
| `VERDORIE` | a softened "verdomme" | darn it |
| `DE PIJP UIT` | out of the pipe | finished, over |

## Install

Get the APK from [Releases](https://github.com/stevendoerr-lachance/yaba/releases/latest).
`universal` runs everywhere. If you want the exact one: `arm64-v8a` for almost every phone made
since 2017, `armeabi-v7a` for older ones, `x86_64` for emulators.

Allow installs from your browser or file manager, open the APK, sign in to your VanMoof account
once. Close the official VanMoof app while using YABA: the bike accepts one Bluetooth connection
at a time.

## Build

Needs JDK 17 and the Android SDK (platform 35, build-tools 35.0.0). Point `local.properties` at the SDK:

```
sdk.dir=/path/to/android-sdk
```

```bash
./gradlew :app:assembleDebug
```

```bash
./gradlew :app:testDebugUnitTest
```

651 unit tests cover the protocol, the crypto, the guard state machine and the log parsers. None
needs a bike.

## Under the hood

| Path | What lives there |
|---|---|
| `app/src/main/java/dev/steven/s3control/ble` | Protocol, crypto, GATT client, drivers for S3 and S2, guard logic |
| `.../data` | Account API, key vault |
| `.../ride` | Ride logger, guard service, log parsers |
| `.../ui` | Jetpack Compose: screens, the dot matrix, the cockpit, the opening film |
| `tools/bells` | The bell synthesiser |
| `tools/readme` | Draws the art on this page from the app's own font, palette and icon |

No UI kit, no image assets. The dot matrix, the tube, the glyphs in the tab bar and the opening
film are drawn in code.

## Credits

Protocol knowledge comes from [mooovy](https://github.com/mjarkk/vanmoof-web-controller) (mjarkk),
[VanMoofKit](https://github.com/SvenTiigi/VanMoofKit) (SvenTiigi), pymoof (quantsini),
[OldMoofKit](https://github.com/Jegge/OldMoofKit) (Jegge, MIT) and the S3 firmware notes by Knight1.
No VanMoof code, firmware or sound files are part of this repository.

Fonts: Archivo and Roboto Flex (SIL OFL, see `licenses/`).

## License

MIT, see [LICENSE](LICENSE).

<p align="center">
  <img src="assets/icon.svg" width="72" alt="YABA app icon"><br>
  <sub>Not affiliated with VanMoof. Not an official VanMoof app. Use at your own risk.</sub>
</p>
