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

### Native libass

The AAR build downloads a prebuilt libass package from this repository's releases
(`LIBASS_VERSION` and `LIBASS_SHA256` in `src/main/cpp/CMakeLists.txt`). The package
is built from the `libass` submodule by `scripts/libass/build-android.sh`, with the
dependency versions pinned in `scripts/libass/wraps`.

To update libass:

1. Move the submodule to the new commit: `git -C libass fetch && git -C libass checkout <commit>`.
2. Commit, then push a `libass-<version>` tag, for example `libass-0.17.5-laskco-b620927`.
   The `libass Android package` workflow builds the package and attaches it to that release.
3. Set `LIBASS_VERSION` and `LIBASS_SHA256` from the workflow summary and rebuild the AAR.

Building the package locally needs the Android NDK, Meson 1.11 or newer, Ninja, gperf
and nasm.

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
by moi15moi. The native libass comes from
[Laskco/mpvNova-libass](https://github.com/Laskco/mpvNova-libass), pinned as the
`libass` submodule.
