package com.nuvio.tv.libass

internal object AssFrameTimestamps {
  const val PENDING = -2L
  const val INVALID = -1L

  const val SRC_PRESENT = 0
  const val SRC_COMPOSITION_START = 1
  const val SRC_COMPOSITION_LATCH = 2
  const val ERR_NO_SURFACE = -1
  const val ERR_NO_EXTENSION = -2
  const val ERR_NO_PROC = -3
  const val ERR_UNSUPPORTED = -4
  const val ERR_ENABLE_FAILED = -5

  fun sourceLabel(status: Int): String = when (status) {
    SRC_PRESENT -> "present"
    SRC_COMPOSITION_START -> "comp-start"
    SRC_COMPOSITION_LATCH -> "comp-latch"
    ERR_NO_SURFACE -> "off:no-surface"
    ERR_NO_EXTENSION -> "off:no-ext"
    ERR_NO_PROC -> "off:no-proc"
    ERR_UNSUPPORTED -> "off:unsupported"
    ERR_ENABLE_FAILED -> "off:enable-failed"
    else -> "off:$status"
  }

  @JvmStatic external fun nativeInit(): Int
  @JvmStatic external fun nativeGetNextFrameId(): Long
  @JvmStatic external fun nativeGetDisplayPresentTime(frameId: Long): Long
}
