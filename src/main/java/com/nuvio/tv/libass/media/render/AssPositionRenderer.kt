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
 * (driven by [VideoFrameMetadataListener]) is not firing (tunneling, or any
 * other scenario where frame metadata is unavailable), this renderer provides
 * the current playback position so subtitles still render.
 *
 * The callback is invoked with [C.TIME_UNSET] as releaseTimeNs so the atlas
 * pipeline swaps immediately instead of vsync-pinning (no frame timing info
 * available in tunneled mode).
 *
 * Based on peerless2012's AssRenderer approach.
 */
@OptIn(UnstableApi::class)
class AssPositionRenderer(private val assHandler: AssHandler) : NoSampleRenderer() {

    // Tracks whether the primary frame callback is active. When it fires
    // regularly we skip the position-based render to avoid duplicate work.
    @Volatile
    private var lastFrameCallbackNs = Long.MIN_VALUE

    override fun getName(): String = "AssPositionRenderer"

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        val elapsed = System.nanoTime() - lastFrameCallbackNs
        // Only render from position when frame callback hasn't fired in 500ms.
        // At ~24fps the callback fires every ~42ms, so 500ms means it's not active.
        if (elapsed > 500_000_000L) {
            assHandler.videoFrameCallback?.invoke(positionUs, C.TIME_UNSET)
        }
    }

    /**
     * Called by the frame callback wrapper to mark that the primary path is active.
     */
    fun onFrameCallbackFired() {
        lastFrameCallbackNs = System.nanoTime()
    }
}
