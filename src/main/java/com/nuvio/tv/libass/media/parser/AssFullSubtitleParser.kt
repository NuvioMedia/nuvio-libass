package com.nuvio.tv.libass.media.parser

import androidx.media3.common.Format
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import com.nuvio.tv.libass.AssTrack

@UnstableApi
class AssFullSubtitleParser(private val track: AssTrack) : SubtitleParser {

  override fun parse(
    data: ByteArray,
    offset: Int,
    length: Int,
    outputOptions: SubtitleParser.OutputOptions,
    output: Consumer<CuesWithTiming>
  ) {
    track.readBuffer(data, offset, length)
  }

  override fun getCueReplacementBehavior(): Int = Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE
}
