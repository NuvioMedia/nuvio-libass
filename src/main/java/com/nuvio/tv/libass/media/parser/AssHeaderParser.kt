package com.nuvio.tv.libass.media.parser

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi

@OptIn(UnstableApi::class)
object AssHeaderParser {

  private const val ASS_EVENTS = "[Events]\n" +
    "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text"
  private val assEventsSuffix = ("\n" + ASS_EVENTS).toByteArray(Charsets.UTF_8)

  private fun normalizeHeader(buffer: ByteArray): ByteArray {
    var contentLength = buffer.size
    while (contentLength > 0 && buffer[contentLength - 1] == 0.toByte()) {
      contentLength--
    }

    val header = String(buffer, 0, contentLength, Charsets.UTF_8)
    val hasEventsSection = header.lineSequence().any {
      it.trim().equals("[Events]", ignoreCase = true)
    }
    if (hasEventsSection) {
      return if (contentLength == buffer.size) buffer else buffer.copyOf(contentLength)
    }

    return buffer.copyOf(contentLength + assEventsSuffix.size).also {
      assEventsSuffix.copyInto(it, destinationOffset = contentLength)
    }
  }

  fun parse(format: Format): ByteArray = normalizeHeader(format.initializationData[1])
}
