# LowKey — Reelshot-style vlog camera for Android

An Android take on the two features that make Reelshot (iOS) feel special:

1. **Low-key mode** — hold the phone in portrait, record true landscape video.
2. **Widgets** — a 3×2 project card and a 1×1 REC button: one tap from your home screen
   straight into the camera, already recording.

Plus the Android-native extras that iOS can't do: a **Quick Settings tile** (works from any app, and is the closest thing Android has to a lock-screen shortcut).

## What's in it

| Feature | Where |
|---|---|
| Low-key recording (upright preview, 16:9 band → landscape file) | `camera/CameraActivity.kt` |
| Standard mode follows device rotation (portrait ⇄ landscape) | `camera/CameraActivity.kt` |
| Lens switching (ultra-wide / wide / tele) | `camera/CameraActivity.kt` |
| 16:9 crop / concat re-encode | `data/ClipProcessor.kt` |
| Blackout mode (screen looks off, still records) | same |
| Record / pause / resume, timer, flip camera, torch | same |
| Projects + private clip storage | `data/Store.kt` |
| Project screen: clip thumbnails, play, share, delete | `ui/ProjectScreen.kt` |
| Clip editor: trim with live preview | `ui/ClipEditorScreen.kt` |
| Play the vlog back in order | `ui/VlogPlayerScreen.kt` |
| Recap: trim every clip to 2/3/5s | `ui/ProjectScreen.kt` |
| Reorder clips (⋮ menu) | `data/Store.kt` |
| Background music, mixed on export | `data/ClipProcessor.kt` |
| Export whole vlog as one MP4 (lossless stitch) | `data/VlogExporter.kt` |
| Home screen widget, 3×2 (project name, clip count, REC buttons) | `widget/RecordWidget.kt` |
| Home screen widget, 1×1 (single REC button) | `widget/QuickRecordWidget.kt` |
| Quick Settings tile | `tile/RecordTileService.kt` |

Clips are written to app-private storage (`filesDir/clips`) so nothing lands in the gallery —
matching Reelshot's "keep it off your camera roll" positioning. Share exports via FileProvider.

## Orientation and lenses

**Standard mode** follows the device: the camera activity runs `fullSensor`, so turning the phone
landscape rotates the UI and both `Preview` and `VideoCapture` are re-pointed at the new display
rotation (`DisplayManager.DisplayListener`). Portrait stays portrait, landscape stays landscape.

**Low-key mode** deliberately locks itself to portrait (it crops a 16:9 band out of a portrait take),
and switching the toggle flips the activity between `PORTRAIT` and `FULL_SENSOR`.

**Lenses** — every camera the device exposes for the current facing becomes a chip (`0.5×`, `1×`,
`3×`…), labelled from `CameraInfo.intrinsicZoomRatio` and rebound via that camera's own
`CameraSelector`. Phones with a single lens simply show no chip row.

## How low-key mode actually works

There is no way to capture a wide, upright frame while the phone is upright — a landscape video
shot from a portrait-held sensor always contains the world tipped 90°. Every app that offers this
mode does the same thing: **shoot portrait, keep only the 16:9 band**.

So low-key mode records portrait (both `Preview` and `VideoCapture` at `ROTATION_0`, so the preview
is upright and honest) and shows a bright 16:9 band with the rest dimmed. On stop, the take is
trimmed to that band with `media3-transformer` (`Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP`) into
a 1280×720 landscape MP4, and the portrait original is deleted. Cost: a second or two of processing
per take.

The band is the recording — what the bright area shows is exactly what lands in the file.

## Editing a clip

Tap any clip in a project to open the trim editor: `ExoPlayer` preview, a two-handle range slider,
**Preview** (plays only the kept part, looping inside the range) and **Save trim**. Saving re-cuts the
clip through `media3-transformer` with a clipping configuration, swaps the file, updates the duration
and deletes the original.

## Deleting clips

Every clip row has **play / share / ✕**. ✕ asks for confirmation, then removes the entry from the
vlog *and* deletes the file from storage, and refreshes the widget.

## What the project screen gives you

- **Record into this project** — straight back to the camera for that vlog
- **Play vlog** — plays the clips back to back in their arranged order
- **Export vlog as one video** — stitched, cropped to 16:9, saved to the gallery
- **Music** — pick a track, set its level (0–100%), remove it
- **Recap** — one tap trims every clip down to 2 / 3 / 5 seconds
- **CLIPS** — tap a clip to trim it; ⋮ for Trim / Move up / Move down / Share / Delete
- **Tap the title** to rename the project

## Music and clip order

**Music** — pick a track from the project screen (`Add track`, any audio file via SAF). It is copied
into the project, looped for the whole vlog, and mixed under the clip audio by `DefaultAudioMixer`
(`Transformer.Builder.setAudioMixerFactory`) at export time. **Remove** drops it.

**Order** — each clip's ⋮ menu has Move up / Move down. Clips carry an explicit `order` field, so the
project list — and therefore the export — follows your arrangement instead of recording time.

## Exporting a whole vlog

Project screen → **Export vlog as one video**. Every clip of the project becomes a single MP4 and is
written straight to the device gallery (`Movies/LowKey`) through MediaStore — no extra tap. **Share
the vlog** then sends it wherever you want.

Two paths: when all clips share encoder settings they are stitched losslessly (sample copy through
`MediaExtractor` → `MediaMuxer`, seconds not minutes); otherwise a single `media3-transformer` pass
re-encodes them cropped to 1280×720. A private copy stays in `filesDir/exports`.

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
