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
 * The activity of the primary path is detected by reading
 * [AssHandler.lastVideoFrameCallbackNs], which is updated on every frame
 * metadata callback. When no callback has fired in 500ms, the renderer assumes
 * tunneled playback and takes over.
 *
 * Based on peerless2012's AssRenderer approach.
 */
@OptIn(UnstableApi::class)
class AssPositionRenderer(private val assHandler: AssHandler) : NoSampleRenderer() {

    override fun getName(): String = "AssPositionRenderer"

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        val lastCallback = assHandler.lastVideoFrameCallbackNs
        // lastCallback == 0 means VideoFrameMetadataListener never fired (tunneling
        // from the start). nanoTime() - 0 is always > 500ms after boot.
        val elapsed = System.nanoTime() - lastCallback
        if (elapsed > 500_000_000L) {
            val callback = assHandler.videoFrameCallback ?: return
            // ExoPlayer's render() positionUs has a 10^12 offset from the playback
            // clock. Subtract it to get the actual media position in microseconds.
            callback.invoke(positionUs - RENDERER_POSITION_OFFSET_US - assHandler.subtitleDelayUs, C.TIME_UNSET)
        }
    }

    private companion object {
        const val RENDERER_POSITION_OFFSET_US = 1_000_000_000_000L
    }
}
