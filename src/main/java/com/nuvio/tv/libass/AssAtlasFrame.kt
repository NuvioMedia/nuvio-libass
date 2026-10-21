package com.nuvio.tv.libass

class AssAtlasFrame(
  val atlasWidth: Int,
  val pageHeights: IntArray,
  val pageQuadCounts: IntArray,
  val quadCount: Int,
  val changed: Int,
  val truncated: Int,
  val requiredPages: Int,
  val hasOutput: Boolean,
  val mode: Int = MODE_ATLAS
) {
  companion object {
    const val MODE_ATLAS = 0
    const val MODE_COMPOSITE = 1
  }

  val pageCount: Int get() = pageHeights.size

  val atlasHeight: Int get() = if (pageHeights.isNotEmpty()) pageHeights[0] else 0

  constructor(
    atlasWidth: Int,
    atlasHeight: Int,
    quadCount: Int,
    changed: Int,
    truncated: Int,
    hasOutput: Boolean
  ) : this(atlasWidth, intArrayOf(atlasHeight), intArrayOf(quadCount), quadCount, changed, truncated, 1, hasOutput)

  constructor(
    atlasWidth: Int,
    atlasHeight: Int,
    quadCount: Int,
    changed: Int,
    truncated: Int
  ) : this(atlasWidth, atlasHeight, quadCount, changed, truncated, quadCount > 0)
}
