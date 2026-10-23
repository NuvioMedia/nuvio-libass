package com.nuvio.tv.libass

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class AssTrack(private val ass: Long, private val lock: ReentrantLock) {

  companion object {

    @JvmStatic
    external fun nativeAssTrackInit(track: Long): Long

    @JvmStatic
    external fun nativeAssTrackReadBuffer(track: Long, byteArray: ByteArray, offset: Int, length: Int)

    @JvmStatic
    external fun nativeAssTrackReadChunk(track: Long, start: Long, duration: Long, byteArray: ByteArray, offset: Int, length: Int)

    @JvmStatic
    external fun nativeAssTrackDeinit(track: Long)

    @JvmStatic
    external fun nativeAssTrackNextEventStart(track: Long, afterMs: Long): Long

    @JvmStatic
    external fun nativeAssTrackNextEventChange(track: Long, afterMs: Long): Long
  }

  /** Pending dialogue chunk for lock-free feeding from the extractor thread. */
  private class PendingChunk(
    val start: Long,
    val duration: Long,
    val data: ByteArray,
    val offset: Int,
    val length: Int
  )

  /**
   * Lock-free queue for dialogue chunks fed by the extractor thread.
   * Drained under lock by the render thread before each render, so the
   * extractor never blocks on the libass lock and video/audio buffering
   * is not stalled by heavy subtitle rendering.
   */
  private val pendingChunks = ConcurrentLinkedQueue<PendingChunk>()

  var nativeAssTrack = nativeAssTrackInit(ass)
    private set

  @Volatile
  var released = false
    private set

  private inline fun withNative(block: (Long) -> Unit) {
    lock.withLock {
      if (!released && nativeAssTrack != 0L) block(nativeAssTrack)
    }
  }

  fun readBuffer(array: ByteArray, offset: Int = 0, length: Int = array.size) = withNative { nativeAssTrackReadBuffer(it, array, offset, length) }

  /**
   * Feeds a dialogue chunk to libass under the shared lock.
   * Prefer [enqueueChunk] from the extractor thread to avoid blocking.
   */
  fun readChunk(start: Long, duration: Long, array: ByteArray, offset: Int = 0, length: Int = array.size) = withNative { nativeAssTrackReadChunk(it, start, duration, array, offset, length) }

  /**
   * Enqueues a dialogue chunk without acquiring the lock. The chunk will be
   * fed to libass by [drainPendingChunks] on the render thread before the
   * next render. This keeps the extractor thread unblocked so video/audio
   * buffering is never stalled by long libass renders.
   */
  fun enqueueChunk(start: Long, duration: Long, data: ByteArray, offset: Int = 0, length: Int = data.size) {
    if (released) return
    // Copy the data since the caller's buffer may be reused
    val copy = data.copyOfRange(offset, offset + length)
    pendingChunks.add(PendingChunk(start, duration, copy, 0, copy.size))
  }

  /**
   * Drains all pending dialogue chunks into libass. Must be called under the
   * shared lock (typically by AssRender.renderFrameAtlas before rendering).
   */
  internal fun drainPendingChunks() {
    if (released || nativeAssTrack == 0L) {
      pendingChunks.clear()
      return
    }
    var chunk = pendingChunks.poll()
    while (chunk != null) {
      nativeAssTrackReadChunk(nativeAssTrack, chunk.start, chunk.duration, chunk.data, chunk.offset, chunk.length)
      chunk = pendingChunks.poll()
    }
  }

  fun nextEventStartMs(afterMs: Long): Long {
    lock.withLock {
      if (released || nativeAssTrack == 0L) return -1
      return nativeAssTrackNextEventStart(nativeAssTrack, afterMs)
    }
  }

  fun nextEventChangeMs(afterMs: Long): Long {
    lock.withLock {
      if (released || nativeAssTrack == 0L) return -1
      return nativeAssTrackNextEventChange(nativeAssTrack, afterMs)
    }
  }

  fun release() {
    lock.withLock {
      if (released) return
      released = true
      pendingChunks.clear()
      if (nativeAssTrack != 0L) {
        nativeAssTrackDeinit(nativeAssTrack)
        nativeAssTrack = 0
      }
    }
  }

  protected fun finalize() {
    release()
  }
}
