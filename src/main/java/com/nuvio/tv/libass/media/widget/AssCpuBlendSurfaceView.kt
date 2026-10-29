package com.nuvio.tv.libass.media.widget

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.libass.AssRender
import com.nuvio.tv.libass.media.AssHandler
import java.util.concurrent.atomic.AtomicReference

/**
 * CPU-blended ASS subtitle overlay via ANativeWindow + NEON.
 *
 * Zero-copy path: ass_render_frame → NEON blend → ANativeWindow_unlockAndPost.
 * No GL, no Bitmap copy, no Canvas. Dirty-rect tracking clears only changed pixels.
 */
@UnstableApi
class AssCpuBlendSurfaceView(
    context: Context,
    private val assHandler: AssHandler
) : SurfaceView(context), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "AssCpuBlend"
        init { System.loadLibrary("asskt") }
    }

    private var nativeHandle: Long = 0
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    private val pendingRequest = AtomicReference<Pair<Long, Long>?>(null)

    @Volatile var renderCount = 0L; private set
    @Volatile var changedRenderCount = 0L; private set

    init {
        setZOrderMediaOverlay(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(this)
    }

    fun requestRender(presentationTimeUs: Long, releaseTimeNs: Long) {
        pendingRequest.set(Pair(presentationTimeUs, releaseTimeNs))
        workerHandler?.post(renderRunnable)
    }

    fun invalidateSubtitles() {
        workerHandler?.post { doRender(force = true) }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val rect = holder.surfaceFrame
        surfaceWidth = rect.width()
        surfaceHeight = rect.height()

        nativeHandle = AssRender.nativeCpuBlendCreate()
        AssRender.nativeCpuBlendSetWindow(nativeHandle, holder.surface, surfaceWidth, surfaceHeight)

        assHandler.setOverlaySurfaceSize(surfaceWidth, surfaceHeight)

        workerThread = HandlerThread("AssCpuBlend", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        workerHandler = Handler(workerThread!!.looper)

        Log.i(TAG, "surface created ${surfaceWidth}x${surfaceHeight}")
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        assHandler.setOverlaySurfaceSize(width, height)
        workerHandler?.post {
            if (nativeHandle != 0L) {
                AssRender.nativeCpuBlendSetWindow(nativeHandle, holder.surface, width, height)
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        val thread = workerThread
        workerHandler?.post {
            if (nativeHandle != 0L) {
                AssRender.nativeCpuBlendSetWindow(nativeHandle, null, 0, 0)
            }
        }
        thread?.quitSafely()
        thread?.join(2000)
        workerThread = null
        workerHandler = null

        if (nativeHandle != 0L) {
            AssRender.nativeCpuBlendRelease(nativeHandle)
            nativeHandle = 0
        }
        Log.i(TAG, "surface destroyed renders=$renderCount changed=$changedRenderCount")
    }

    private val renderRunnable = Runnable { doRender(force = false) }

    private fun doRender(force: Boolean) {
        if (nativeHandle == 0L) return

        val request = pendingRequest.getAndSet(null) ?: if (force) Pair(0L, 0L) else return
        val render = assHandler.render ?: return
        if (render.released) return

        val pts = request.first
        val delayedUs = pts - assHandler.subtitleDelayUs + assHandler.audioDelayUs
        val timeMs = delayedUs / 1000

        renderCount++

        // Single native call: drain chunks + ass_render_frame + NEON blend + ANativeWindow post
        val posted = render.cpuBlendRenderAndPost(nativeHandle, timeMs, force)
        if (posted) changedRenderCount++
    }
}
