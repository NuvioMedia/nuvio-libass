# nuvio-libass

Atlas-based libass subtitle rendering module for Android with ExoPlayer integration.

Renders ASS/SSA subtitles through a dedicated SurfaceView overlay with vsync-pinned
swap via `eglPresentationTimeANDROID`, texture atlas packing, speculative render-ahead,
and cache-warming prefetch.

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

## Attribution

See [ATTRIBUTION.md](ATTRIBUTION.md) for full credits.

This module is based on [Plezy's](https://github.com/edde746/plezy) [libass](https://github.com/edde746/libass) implementation by edde746,
which is a fork of [libass-android](https://github.com/peerless2012/libass-android)
by peerless2012. The prebuilt native libass is from [edde746/libass](https://github.com/edde746/libass).
