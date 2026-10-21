package com.nuvio.tv.libass

import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class AssRender(nativeAss: Long, private val lock: ReentrantLock) {

  companion object {

    private const val MAX_ATLAS_PAGES = 4
    private const val HEADER_INTS = 7 + 2 * MAX_ATLAS_PAGES + 1

    @JvmStatic
    external fun nativeAssRenderInit(ass: Long): Long

    @JvmStatic
    external fun nativeAssRenderSetFontScale(render: Long, scale: Float)

    @JvmStatic
    external fun nativeAssRenderSetCacheLimit(render: Long, glyphMax: Int, bitmapMaxSize: Int)

    @JvmStatic
    external fun nativeAssRenderSetStorageSize(render: Long, width: Int, height: Int)

    @JvmStatic
    external fun nativeAssRenderSetFrameSize(render: Long, width: Int, height: Int)

    @JvmStatic
    external fun nativeAssRenderSetMargins(render: Long, top: Int, bottom: Int, left: Int, right: Int)

    @JvmStatic
    external fun nativeAssRenderSetUseMargins(render: Long, use: Boolean)

    @JvmStatic
    external fun nativeAssRenderFrameAtlas(
      render: Long,
      track: Long,
      time: Long,
      atlasBuf: ByteBuffer,
      atlasMaxWidth: Int,
      atlasMaxHeight: Int,
      vertexBuf: ByteBuffer,
      header: IntArray
    ): Int

    @JvmStatic
    external fun nativeAssRenderDeinit(render: Long)
  }

  private var nativeRender: Long = nativeAssRenderInit(nativeAss)

  private val frameHeader = IntArray(HEADER_INTS)

  @Volatile
  var released = false
    private set

  private var track: AssTrack? = null

  private val generation = java.util.concurrent.atomic.AtomicInteger(0)

  val stateGeneration: Int get() = generation.get()

  private inline fun withNative(block: (Long) -> Unit) {
    lock.withLock {
      if (!released && nativeRender != 0L) block(nativeRender)
    }
  }

  fun setTrack(track: AssTrack?) {
    generation.incrementAndGet()
    lock.withLock { this.track = track }
  }

  val hasTrack: Boolean get() = lock.withLock { track != null }

  fun setFontScale(scale: Float) {
    generation.incrementAndGet()
    withNative { nativeAssRenderSetFontScale(it, scale) }
  }

  fun setCacheLimit(glyphMax: Int, bitmapMaxSize: Int) = withNative { nativeAssRenderSetCacheLimit(it, glyphMax, bitmapMaxSize) }

  fun setStorageSize(width: Int, height: Int) {
    generation.incrementAndGet()
    withNative { nativeAssRenderSetStorageSize(it, width, height) }
  }

  fun setFrameSize(width: Int, height: Int) {
    generation.incrementAndGet()
    withNative { nativeAssRenderSetFrameSize(it, width, height) }
  }

  fun setMargins(top: Int, bottom: Int, left: Int, right: Int) {
    generation.incrementAndGet()
    withNative { nativeAssRenderSetMargins(it, top, bottom, left, right) }
  }

  fun setUseMargins(use: Boolean) {
    generation.incrementAndGet()
    withNative { nativeAssRenderSetUseMargins(it, use) }
  }

  @Volatile
  var lastLockWaitMs: Long = 0
    private set

  fun renderFrameAtlas(
    time: Long,
    atlasBuf: ByteBuffer,
    atlasMaxW: Int,
    atlasMaxH: Int,
    vertexBuf: ByteBuffer
  ): AssAtlasFrame? {
    val tQueue = System.nanoTime()
    lock.withLock {
      lastLockWaitMs = (System.nanoTime() - tQueue) / 1_000_000
      if (released || nativeRender == 0L) return null
      val t = track ?: return null
      if (t.released || t.nativeAssTrack == 0L) return null
      val header = frameHeader
      val status =
        nativeAssRenderFrameAtlas(nativeRender, t.nativeAssTrack, time, atlasBuf, atlasMaxW, atlasMaxH, vertexBuf, header)
      if (status == 0) return null
      val pageCount = header[6]
      return AssAtlasFrame(
        atlasWidth = header[0],
        pageHeights = IntArray(pageCount) { header[7 + it] },
        pageQuadCounts = IntArray(pageCount) { header[7 + MAX_ATLAS_PAGES + it] },
        quadCount = header[1],
        changed = header[2],
        truncated = header[3],
        requiredPages = header[4],
        hasOutput = header[5] != 0,
        mode = header[7 + 2 * MAX_ATLAS_PAGES]
      )
    }
  }

  fun release() {
    lock.withLock {
      if (released) return
      released = true
      track = null
      if (nativeRender != 0L) {
        nativeAssRenderDeinit(nativeRender)
        nativeRender = 0
      }
    }
  }

  protected fun finalize() {
    release()
  }
}
