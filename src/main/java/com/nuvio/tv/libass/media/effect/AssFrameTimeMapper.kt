package com.nuvio.tv.libass.media.effect

import androidx.media3.common.C

/**
 * Maps the presentation time a video effect receives back to the media time of the frame.
 *
 * With video effects, MediaCodecVideoRenderer shifts each buffer timestamp by -startPositionUs
 * before the frame reaches the effects. This class mirrors that from the calls
 * [AssVideoRendererWrapper] sees, so the effect can recover the original media time.
 */
class AssFrameTimeMapper {

    private class Stream(val startPositionUs: Long, val offsetUs: Long)

    private var baseUs = C.TIME_UNSET
    private val streams = ArrayDeque<Stream>()

    @Synchronized
    fun onEnabled(startPositionUs: Long, offsetUs: Long) {
        if (baseUs == C.TIME_UNSET) {
            baseUs = startPositionUs
        }
        streams.clear()
        streams.addLast(Stream(startPositionUs, offsetUs))
    }

    @Synchronized
    fun onStreamReplaced(startPositionUs: Long, offsetUs: Long) {
        streams.addLast(Stream(startPositionUs, offsetUs))
    }

    @Synchronized
    fun onPositionReset() {
        while (streams.size > 1) {
            streams.removeFirst()
        }
    }

    @Synchronized
    fun onReset() {
        baseUs = C.TIME_UNSET
        streams.clear()
    }

    @Synchronized
    fun toMediaTimeUs(framePresentationTimeUs: Long): Long {
        if (baseUs == C.TIME_UNSET || streams.isEmpty()) {
            return C.TIME_UNSET
        }
        val rendererTimeUs = framePresentationTimeUs + baseUs
        while (streams.size > 1 && streams[1].startPositionUs <= rendererTimeUs) {
            streams.removeFirst()
        }
        return rendererTimeUs - streams.first().offsetUs
    }
}
