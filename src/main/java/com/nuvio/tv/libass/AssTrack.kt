package com.nuvio.tv.libass

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

  fun readChunk(start: Long, duration: Long, array: ByteArray, offset: Int = 0, length: Int = array.size) = withNative { nativeAssTrackReadChunk(it, start, duration, array, offset, length) }

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
