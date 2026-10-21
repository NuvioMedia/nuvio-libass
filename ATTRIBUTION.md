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

## edde746/libass (native library)

The prebuilt `libass.a` static library is sourced from edde746's fork of libass,
which adds Android static library builds, multi-threaded rendering support, and
performance optimizations (ASS_FEATURE_FAST_BLUR, HarfBuzz subfonts reuse,
batch thread accounting) on top of upstream [libass/libass](https://github.com/libass/libass).

- Repository: https://github.com/edde746/libass
- Upstream: https://github.com/libass/libass
- License: ISC
