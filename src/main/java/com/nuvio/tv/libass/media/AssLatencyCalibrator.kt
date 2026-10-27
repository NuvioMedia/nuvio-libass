package com.nuvio.tv.libass.media

import android.os.Build
import android.util.Log
import android.view.SurfaceControl
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import com.nuvio.tv.libass.SurfaceTxProbe
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Auto-calibrates subtitle/video layer offset by measuring how much later the
 * codec VIDEO plane reaches the display than the GL subtitle OVERLAY plane.
 *
 * Uses SurfaceControl.applyTransactionToFrame() (API 34) to attach a callback
 * to both planes and measure their previous-release fence timestamps. The
 * median difference in frame intervals is the inter-plane offset.
 *
 * Converges once, persists the result, and stops all probing (zero steady-state
 * overhead). Re-runs each playback session to re-confirm.
 *
 * Based on Plezy's AssLatencyCalibrator by edde746.
 */
class AssLatencyCalibrator(
    private val videoSurface: SurfaceView,
    private val overlaySurface: SurfaceView,
    private val onCalibrated: (Int) -> Unit,
    private val onDone: () -> Unit,
    private val log: (String) -> Unit = { Log.d(TAG, it) }
) {
    private val lock = Any()
    private val finished = AtomicBoolean(false)
    private val applyFail = AtomicInteger(0)

    @Volatile private var frameIntervalNs: Long = 0L
    @Volatile private var converged = false
    @Volatile private var stopped = false

    private var videoFrames = 0
    private var videoAttempts = 0
    private var overlaySwaps = 0
    private var overlayAttempts = 0
    private val videoRel = ArrayDeque<Double>()
    private val overlayRel = ArrayDeque<Double>()

    fun start() {
        SurfaceTxProbe.sink = { tag, latch, release, count, state, source, cb ->
            onResult(tag, latch, release, count, state, source, cb)
        }
        log("calibration started (API ${Build.VERSION.SDK_INT})")
    }

    fun probeVideo(releaseTimeNs: Long, fps: Float) {
        if (stopped || converged || Build.VERSION.SDK_INT < 34) return
        if (fps > 1f) frameIntervalNs = (1_000_000_000.0 / fps).toLong()
        var doAttach = false
        var giveUp = false
        synchronized(lock) {
            videoFrames++
            if (videoFrames > GIVEUP_FRAMES) {
                giveUp = true
            } else if (videoAttempts < VIDEO_ATTEMPT_CAP && videoFrames % VIDEO_EVERY == 0) {
                videoAttempts++
                doAttach = true
            }
        }
        if (giveUp) {
            finishIncomplete()
        } else if (doAttach) {
            attach(videoSurface, releaseTimeNs, SurfaceTxProbe.SOURCE_VIDEO)
        }
    }

    fun probeOverlay(releaseTimeNs: Long) {
        if (stopped || converged || Build.VERSION.SDK_INT < 34) return
        var doAttach = false
        synchronized(lock) {
            overlaySwaps++
            if (overlayAttempts < OVERLAY_ATTEMPT_CAP && overlaySwaps % OVERLAY_EVERY == 0) {
                overlayAttempts++
                doAttach = true
            }
        }
        if (doAttach) attach(overlaySurface, releaseTimeNs, SurfaceTxProbe.SOURCE_OVERLAY)
    }

    @RequiresApi(34)
    private fun attach(surface: SurfaceView, releaseTimeNs: Long, source: Int) {
        try {
            val tx = SurfaceControl.Transaction()
            SurfaceTxProbe.nativeAttach(tx, releaseTimeNs, source)
            surface.applyTransactionToFrame(tx)
        } catch (t: Throwable) {
            if (applyFail.incrementAndGet() == 1) {
                log("applyTransactionToFrame failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private fun onResult(
        tag: Long,
        latchNs: Long,
        releaseNs: Long,
        surfaceCount: Int,
        fenceState: Int,
        source: Int,
        callbackNs: Long
    ) {
        if (surfaceCount <= 0 || fenceState != FENCE_OK || releaseNs <= 0) return
        val relMs = (releaseNs - tag) / 1_000_000.0
        var result: Int? = null
        synchronized(lock) {
            if (converged || stopped) return
            val list = if (source == SurfaceTxProbe.SOURCE_OVERLAY) overlayRel else videoRel
            val window = if (source == SurfaceTxProbe.SOURCE_OVERLAY) OVERLAY_WINDOW else VIDEO_WINDOW
            list.addLast(relMs)
            while (list.size > window) list.removeFirst()
            result = tryComputeOffset()
        }
        result?.let {
            onCalibrated(it)
            finish()
        }
    }

    private fun tryComputeOffset(): Int? {
        val frameMs = frameIntervalNs / 1_000_000.0
        if (frameMs <= 0.5) return null
        if (videoRel.size < VIDEO_MIN || overlayRel.size < OVERLAY_MIN) return null
        val medV = median(videoRel)
        val medO = median(overlayRel)
        val offsetMs = medV - medO
        val raw = offsetMs / frameMs
        val frames = raw.roundToInt()
        if (abs(raw - frames) > CONFIDENCE_TOL) return null
        val clamped = frames.coerceIn(MAX_NEGATIVE_FRAMES, MAX_POSITIVE_FRAMES)
        converged = true
        log(
            "CALIBRATED offsetFrames=$clamped raw=${"%.2f".format(raw)} " +
                "(video=${"%.1f".format(medV)}ms - overlay=${"%.1f".format(medO)}ms = ${"%.1f".format(offsetMs)}ms " +
                "/ frameMs=${"%.2f".format(frameMs)}) samples video=${videoRel.size} overlay=${overlayRel.size} " +
                "applyFail=${applyFail.get()}"
        )
        return clamped
    }

    private fun finishIncomplete() {
        if (converged || stopped) return
        log(
            "calibration incomplete after $videoFrames frames " +
                "(video=${videoRel.size} overlay=${overlayRel.size}); keeping seed"
        )
        finish()
    }

    fun stop() = finish()

    private fun finish() {
        if (!finished.compareAndSet(false, true)) return
        stopped = true
        SurfaceTxProbe.sink = null
        onDone()
    }

    private fun median(xs: Collection<Double>): Double {
        val s = xs.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    companion object {
        private const val TAG = "AssLatencyCalibrator"
        private const val FENCE_OK = 3
        private const val VIDEO_EVERY = 2
        private const val OVERLAY_EVERY = 1
        private const val VIDEO_MIN = 40
        private const val OVERLAY_MIN = 12
        private const val VIDEO_WINDOW = 60
        private const val OVERLAY_WINDOW = 40
        private const val VIDEO_ATTEMPT_CAP = 200
        private const val OVERLAY_ATTEMPT_CAP = 60
        private const val CONFIDENCE_TOL = 0.33
        private const val GIVEUP_FRAMES = 1800
        private const val MAX_NEGATIVE_FRAMES = -10
        private const val MAX_POSITIVE_FRAMES = 10
    }
}
