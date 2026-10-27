package com.nuvio.tv.libass

import android.view.SurfaceControl
import androidx.annotation.Keep

/**
 * JNI bridge for the API-34 transaction-completion callback path. Measures when
 * video and overlay layers actually reach the display via the previous-release fence.
 * Used by AssLatencyCalibrator to auto-detect inter-plane timing offset.
 */
@Keep
object SurfaceTxProbe {
  init {
    System.loadLibrary("asskt")
  }

  const val SOURCE_VIDEO = 0
  const val SOURCE_OVERLAY = 1

  @Volatile
  @JvmStatic
  var sink: ((Long, Long, Long, Int, Int, Int, Long) -> Unit)? = null

  @JvmStatic
  external fun nativeAttach(transaction: SurfaceControl.Transaction, tag: Long, source: Int)

  @Keep
  @JvmStatic
  fun onResult(
    tag: Long,
    latchNs: Long,
    releaseNs: Long,
    surfaceCount: Int,
    fenceState: Int,
    source: Int,
    callbackNs: Long
  ) {
    val s = sink ?: return
    s.invoke(tag, latchNs, releaseNs, surfaceCount, fenceState, source, callbackNs)
  }
}
