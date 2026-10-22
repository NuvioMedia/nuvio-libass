package com.nuvio.tv.libass.media.render

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

    // Initialized to 0 (not Long.MIN_VALUE) to avoid integer overflow when
    // computing elapsed = System.nanoTime() - lastFrameCallbackNs.
    @Volatile
    private var lastFrameCallbackNs = 0L

    override fun getName(): String = "AssPositionRenderer"

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        val elapsed = System.nanoTime() - lastFrameCallbackNs
        // Only render from position when frame callback hasn't fired in 500ms.
        // At ~24fps the callback fires every ~42ms, so 500ms means it's not active.
        if (elapsed > 500_000_000L) {
            val callback = assHandler.videoFrameCallback ?: return
            // ExoPlayer's render() positionUs has a 10^12 offset from the playback
            // clock. Subtract it to get the actual media position in microseconds.
            callback.invoke(positionUs - RENDERER_POSITION_OFFSET_US, C.TIME_UNSET)
        }
    }

    /**
     * Called by the frame callback wrapper to mark that the primary path is active.
     */
    fun onFrameCallbackFired() {
        lastFrameCallbackNs = System.nanoTime()
    }

    private companion object {
        const val RENDERER_POSITION_OFFSET_US = 1_000_000_000_000L
    }
}
