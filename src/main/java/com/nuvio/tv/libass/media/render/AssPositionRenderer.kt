package com.nuvio.tv.libass.media.render

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.NoSampleRenderer
import com.nuvio.tv.libass.media.AssHandler

/**
 * Fallback subtitle position source for tunneled playback.
 *
 * ExoPlayer invokes [render] on every rendering loop iteration regardless of
 * whether tunneling is active. When the primary [AssHandler.videoFrameCallback]
 * (driven by VideoFrameMetadataListener) is not firing, this renderer provides
 * the current playback position so subtitles still render.
 *
 * Based on peerless2012's AssRenderer approach.
 */
@OptIn(UnstableApi::class)
class AssPositionRenderer(private val assHandler: AssHandler) : NoSampleRenderer() {

    @Volatile
    private var lastFrameCallbackNs = Long.MIN_VALUE

    private var logCounter = 0

    override fun getName(): String = "AssPositionRenderer"

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        val now = System.nanoTime()
        val elapsed = now - lastFrameCallbackNs
        val callback = assHandler.videoFrameCallback

        // Log every ~120 calls (~2s at 60fps) for debugging
        if (logCounter++ % 120 == 0) {
            Log.d("AssPositionRenderer", "render posUs=$positionUs elapsedMs=${elapsed / 1_000_000} callbackNull=${callback == null} hasTracks=${assHandler.hasTracks()}")
        }

        if (elapsed > 500_000_000L && callback != null) {
            callback.invoke(positionUs, C.TIME_UNSET)
        }
    }

    fun onFrameCallbackFired() {
        lastFrameCallbackNs = System.nanoTime()
    }
}
