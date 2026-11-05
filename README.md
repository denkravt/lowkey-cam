# LowKey — Reelshot-style vlog camera for Android

An Android take on the two features that make Reelshot (iOS) feel special:

1. **Low-key mode** — hold the phone in portrait, record true landscape video.
2. **Widgets** — a 3×2 project card and a 1×1 REC button: one tap from your home screen
   straight into the camera, already recording.

Plus the Android-native extras that iOS can't do: a **Quick Settings tile** (works from any app, and is the closest thing Android has to a lock-screen shortcut).

## What's in it

| Feature | Where |
|---|---|
| Low-key recording (portrait hold → 1920×1080 landscape file) | `camera/CameraActivity.kt` |
| Blackout mode (screen looks off, still records) | same |
| Record / pause / resume, timer, flip camera, torch | same |
| Projects + private clip storage | `data/Store.kt` |
| Project screen: clip thumbnails, play, share | `ui/ProjectScreen.kt` |
| Export whole vlog as one MP4 (lossless stitch) | `data/VlogExporter.kt` |
| Home screen widget, 3×2 (project name, clip count, REC buttons) | `widget/RecordWidget.kt` |
| Home screen widget, 1×1 (single REC button) | `widget/QuickRecordWidget.kt` |
| Quick Settings tile | `tile/RecordTileService.kt` |

Clips are written to app-private storage (`filesDir/clips`) so nothing lands in the gallery —
matching Reelshot's "keep it off your camera roll" positioning. Share exports via FileProvider.

## How low-key mode actually works

CameraX exposes a per-use-case `targetRotation`. The activity is locked to portrait, so:

- `VideoCapture.targetRotation = Surface.ROTATION_90` → the recorder captures the **native
  landscape stream** (16:9 of the sensor) and tags the MP4 with a 0° rotation, so it plays wide.
- `Preview.targetRotation` is set to the **same** value. Both use cases therefore share one
  stream configuration, so the preview is the recorded frame — not a portrait crop blown up.

The preview is laid out as a 16:9 frame in the middle of the portrait screen. Because the phone is
held upright, the scene inside it appears sideways — that is inherent to filming landscape while
holding portrait, and it is what the file contains.

Because the file carries rotation metadata instead of baked-in pixels, no re-encode is needed and
quality is full-rate.

## Exporting a whole vlog

Project screen → **Export vlog as one video**. Every clip of the project is stitched into a single
MP4 by copying encoded samples through `MediaExtractor` → `MediaMuxer` (no re-encode, seconds not
minutes), then **Share exported video** sends it wherever you want. Output lands in
`filesDir/exports`.

Because one MP4 carries a single rotation matrix, the project's dominant orientation wins; clips of
the other orientation are left out and reported in the status line.

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
