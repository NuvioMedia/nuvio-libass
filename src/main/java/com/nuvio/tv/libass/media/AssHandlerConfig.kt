package com.nuvio.tv.libass.media

data class AssHandlerConfig(
  val glyphSize: Int = 10000,
  val cacheSize: Int = 128,
  /** When true, overlay rendering uses CPU NEON blend via ANativeWindow instead of GL atlas pipeline. */
  val useCpuBlend: Boolean = false
)
