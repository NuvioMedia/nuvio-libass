# nuvio-libass

Atlas-based libass subtitle rendering module for Android with ExoPlayer integration.

Two rendering paths:

- **Video Effects** (AssVideoEffectAsync) - draws subtitles directly onto each video
  frame via `setVideoEffects`. A dedicated render thread with a shared EGL context
  runs libass asynchronously so video delivery is never blocked. Ensures frame-accurate
  subtitle timing on all display hardware.

- **Overlay SurfaceView** (AssSubtitleSurfaceView) - renders subtitles on a separate
  SurfaceView layer with vsync-pinned swap via `eglPresentationTimeANDROID`, speculative
  render-ahead, and cache-warming prefetch.

## Usage

Build the AAR:

```bash
./gradlew assembleRelease
```

The output AAR is at `build/outputs/aar/nuvio-libass-release.aar`.

Copy it to your app's `libs/` folder and add:

```kotlin
implementation(files("libs/nuvio-libass-release.aar"))
```

### Video Effects path

```kotlin
val player = ExoPlayer.Builder(context)
    .buildWithAssSupportCompat(context, renderersFactory = renderersFactory)

player.setVideoEffects(listOf(AssVideoEffectAsync(player.getAssHandlerCompat()!!)))
```

### Overlay SurfaceView path

```kotlin
val assHandler = player.getAssHandlerCompat()!!
val surfaceView = AssSubtitleSurfaceView(context, assHandler)
assHandler.videoFrameCallback = { pts, releaseNs ->
    surfaceView.requestRender(pts, releaseNs)
}
```

## Attribution

See [ATTRIBUTION.md](ATTRIBUTION.md) for full credits.

This module is based on [Plezy's](https://github.com/edde746/plezy)
[libass](https://github.com/edde746/libass) implementation by edde746,
which is a fork of [libass-android](https://github.com/peerless2012/libass-android)
by peerless2012. The video effects path is inspired by
[peerless2012/libass-android PR #90](https://github.com/peerless2012/libass-android/pull/90)
by moi15moi.
