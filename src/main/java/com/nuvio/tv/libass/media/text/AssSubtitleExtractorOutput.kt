package com.nuvio.tv.libass.media.text

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import com.nuvio.tv.libass.media.AssHandler
import com.nuvio.tv.libass.media.extractor.AssMatroskaExtractor

@UnstableApi
class AssSubtitleExtractorOutput(
  private val delegate: ExtractorOutput,
  private val assHandler: AssHandler,
  private val extractor: AssMatroskaExtractor
) : ExtractorOutput by delegate {
  override fun track(id: Int, type: Int): TrackOutput = if (type == C.TRACK_TYPE_TEXT) {
    AssTrackOutput(delegate.track(id, type), assHandler, extractor)
  } else {
    delegate.track(id, type)
  }
}
