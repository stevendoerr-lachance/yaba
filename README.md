# YABA

Yet Another Bike App. A native Android app that controls a VanMoof e-bike directly over Bluetooth LE.

Built for one second-hand S3, and meant to keep working when VanMoof's servers do not. YABA fetches
the bike key from the VanMoof account once, stores it encrypted on the phone (Android Keystore) and
talks to the bike without any backend after that.

Not affiliated with VanMoof. Not an official VanMoof app. Use at your own risk.

## Supported bikes

| Bike | Status |
|---|---|
| VanMoof S3 / X3 | Full support. Tested on an S3 with firmware 1.9.3. |
| VanMoof Electrified S2 / X2 | Core functions: lock, lights, assist, alarm, diagnosis. Protocol ported from OldMoofKit. |

## What it does

- **Ride:** unlock and lock, assist level 0 to 4, speed region (JP 24, EU 25, US 32 km/h), lights,
  alarm, touch unlock.
- **Guard:** arms the bike's own motion watch, keeps the connection with the screen off and notifies
  the phone when the bike moves.
- **Sound:** bell tone, custom bell from any audio file (converted on the phone), ten bundled bells
  synthesised by `tools/bells/make_bells.py`, sound board.
- **Diagnosis:** live status, decoded fault bits, a background ride logger (CSV), readout of the bike's
  internal log, raw characteristic values.
- **Bike:** firmware versions, odometer, units, backup code, key backup and import, several bikes.
- **Experiments:** e-shifter shift points, and Speed++. Speed++ sets a 28" bike to the 24" wheel
  setting, so the bike measures 84 % of the real speed and assist ends later. YABA shows the real
  speed. Check what is legal where you ride.
- Book-style foldables get a two-pane layout split at the hinge.
- English, German, Dutch, French, Italian, Croatian, Russian.

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

The APK lands in `app/build/outputs/apk/debug/`. Install it with `adb install -r`.

Close the official VanMoof app while using YABA. The bike accepts one Bluetooth connection at a time.

## Layout

- `app/src/main/java/dev/steven/s3control/ble`: protocol, crypto, GATT client, drivers for S3 and S2
- `.../data`: account API, key vault
- `.../ride`: ride logger, guard service
- `.../ui`: Jetpack Compose screens and components
- `app/src/test`: unit tests for protocol and logic, no device needed

## Credits

Protocol knowledge comes from [mooovy](https://github.com/mjarkk/vanmoof-web-controller) (mjarkk),
[VanMoofKit](https://github.com/SvenTiigi/VanMoofKit) (SvenTiigi), pymoof (quantsini),
[OldMoofKit](https://github.com/Jegge/OldMoofKit) (Jegge, MIT) and the S3 firmware notes by Knight1.
No VanMoof code, firmware or sound files are part of this repository.

Fonts: Archivo and Roboto Flex (SIL OFL, see `licenses/`). Bundled bells: CC0, see `tools/bells/README.md`.

## License

MIT, see [LICENSE](LICENSE).
