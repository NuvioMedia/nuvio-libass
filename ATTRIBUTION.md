# Attribution

This module is based on the libass integration from [Plezy](https://github.com/edde746/plezy)
by edde746, which itself is a fork of [libass-android](https://github.com/peerless2012/libass-android)
by peerless2012.

## peerless2012/libass-android

The original Android libass integration library. Provides the core JNI bindings
(Ass, AssRender, AssTrack), ExoPlayer media integration (AssHandler, subtitle
parsers, Matroska extractor with font extraction), and the foundational rendering
architecture. Our AssHandler, parser layer, extractor output, and JNI binding
structure originate from this project.

The video effects rendering path (AssVideoEffectAsync, AssFrameTimeMapper,
AssVideoRendererWrapper) is inspired by the EFFECTS_ATLAS approach from
[peerless2012/libass-android PR #90](https://github.com/peerless2012/libass-android/pull/90)
by moi15moi, which demonstrated drawing subtitles directly onto video frames
via Media3's GlEffect/GlShaderProgram pipeline.

- Repository: https://github.com/peerless2012/libass-android
- Author: peerless2012
- License: Apache 2.0

## edde746/plezy

Rebuilt the rendering pipeline on top of peerless2012's foundation with major
performance improvements: atlas-based texture packing (AssPack.c), SurfaceView
overlay with vsync-pinned swap via `eglPresentationTimeANDROID`, speculative
render-ahead engine, cache-warming prefetch, EGL frame timestamp measurement,
and render scale support. Our rendering pipeline (AssSubtitleAtlasPipeline,
AssSubtitleSurfaceView, SpecRenderEngine, AssPack) comes from this project.

- Repository: https://github.com/edde746/plezy
- Author: edde746
- License: GPL-3.0

## Laskco/mpvNova-libass (native library)

The `libass.a` static library is built from Laskco's fork of libass, pinned as the
`libass` submodule. It adds multi-threaded event rendering, less processing of
large off-screen effects, better reuse of cached bitmaps, ARMv7 and ARM64 NEON
rendering paths, and a performance mode on top of upstream
[libass/libass](https://github.com/libass/libass).

- Repository: https://github.com/Laskco/mpvNova-libass
- Upstream: https://github.com/libass/libass
- License: ISC

## edde746/libass (Android build script)

`scripts/libass/build-android.sh` is adapted from the Android release script in
edde746's fork of libass, which nuvio-libass used for its prebuilt `libass.a`
before switching to Laskco's fork.

- Repository: https://github.com/edde746/libass
- License: ISC
