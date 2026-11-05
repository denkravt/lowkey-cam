# LowKey — Reelshot-style vlog camera for Android

An Android take on the two features that make Reelshot (iOS) feel special:

1. **Low-key mode** — hold the phone in portrait, record true landscape video.
2. **Widget** — one tap from your home screen straight into the camera, already recording.

Plus the Android-native extras that iOS can't do: a **Quick Settings tile** (works from any app, and is the closest thing Android has to a lock-screen shortcut).

## What's in it

| Feature | Where |
|---|---|
| Low-key recording (portrait hold → 1920×1080 landscape file) | `camera/CameraActivity.kt` |
| Blackout mode (screen looks off, still records) | same |
| Record / pause / resume, timer, flip camera, torch | same |
| Projects + private clip storage | `data/Store.kt` |
| Home screen widget (project name, clip count, REC buttons) | `widget/RecordWidget.kt` |
| Quick Settings tile | `tile/RecordTileService.kt` |

Clips are written to app-private storage (`filesDir/clips`) so nothing lands in the gallery —
matching Reelshot's "keep it off your camera roll" positioning. Share exports via FileProvider.

## How low-key mode actually works

CameraX exposes a per-use-case `targetRotation`. The activity is locked to portrait, so:

- `Preview` stays at `Surface.ROTATION_0` → sensor image is upright in the portrait window.
- `VideoCapture` is set to `Surface.ROTATION_90` → the encoder writes a 1920×1080 buffer and
  tags the MP4 with a 90° rotation matrix, so players show a normal landscape video.

The preview is composed as a 16:9 landscape frame in the middle of the portrait screen with the
`PreviewView` rotated −90°, so what you see is exactly what gets recorded (just laid sideways).

Because the file carries rotation metadata instead of baked-in pixels, no re-encode is needed and
quality is full-rate.

## Build

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- minSdk 26, targetSdk 35, AGP 8.9.2, Kotlin 2.0.21, CameraX 1.4.2, Glance 1.1.1.

## Not done yet

- Editor (trim, music, A/B roll) — out of scope for this first cut.
- True screen-off recording needs a `camera`-type foreground service; currently the activity keeps
  the screen on and Blackout simulates "screen off".
- Lock screen widgets don't exist in stock Android — the QS tile is the substitute.
