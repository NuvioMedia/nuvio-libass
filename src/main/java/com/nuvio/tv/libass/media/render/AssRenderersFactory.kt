package com.nuvio.tv.libass.media.render

import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.nuvio.tv.libass.media.AssHandler

/**
 * Wraps an existing [RenderersFactory] and appends an [AssPositionRenderer]
 * so subtitle position updates work during tunneled playback.
 *
 * Based on peerless2012's AssRenderersFactory approach.
 */
@UnstableApi
class AssRenderersFactory(
    private val assHandler: AssHandler,
    private val delegate: RenderersFactory
) : RenderersFactory {

    override fun createRenderers(
        eventHandler: Handler,
        videoRendererEventListener: VideoRendererEventListener,
        audioRendererEventListener: AudioRendererEventListener,
        textRendererOutput: TextOutput,
        metadataRendererOutput: MetadataOutput
    ): Array<Renderer> {
        val renderers = delegate.createRenderers(
            eventHandler,
            videoRendererEventListener,
            audioRendererEventListener,
            textRendererOutput,
            metadataRendererOutput
        )
        return renderers + AssPositionRenderer(assHandler)
    }

    override fun createSecondaryRenderer(
        renderer: Renderer,
        eventHandler: Handler,
        videoRendererEventListener: VideoRendererEventListener,
        audioRendererEventListener: AudioRendererEventListener,
        textRendererOutput: TextOutput,
        metadataRendererOutput: MetadataOutput
    ): Renderer? {
        return delegate.createSecondaryRenderer(
            renderer,
            eventHandler,
            videoRendererEventListener,
            audioRendererEventListener,
            textRendererOutput,
            metadataRendererOutput
        )
    }
}
