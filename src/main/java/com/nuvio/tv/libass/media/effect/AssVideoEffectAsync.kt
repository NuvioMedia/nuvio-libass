package com.nuvio.tv.libass.media.effect

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.nuvio.tv.libass.AssAtlasFrame
import com.nuvio.tv.libass.media.AssHandler
import com.nuvio.tv.libass.media.widget.AssAtlasPipelineConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Async variant: renders libass on a dedicated GL thread with a shared EGL context,
 * blends the result onto the video frame with a single fullscreen quad.
 */
@UnstableApi
class AssVideoEffectAsync(private val assHandler: AssHandler) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return AssVideoEffectAsyncProgram(assHandler)
    }
    companion object { private const val TAG = "AssVideoEffectAsync" }
}

@UnstableApi
private class AssVideoEffectAsyncProgram(
    private val assHandler: AssHandler,
) : GlShaderProgram {

    private var inputListener: GlShaderProgram.InputListener = object : GlShaderProgram.InputListener {}
    private var outputListener: GlShaderProgram.OutputListener = object : GlShaderProgram.OutputListener {}
    private var errorListener = GlShaderProgram.ErrorListener {}
    private var errorExecutor = Executor { it.run() }
    private var frameInFlight = false

    // Shared RGBA texture: render thread writes, video thread reads.
    private var sharedTexId = 0
    private var sharedFboId = 0
    private var sharedW = 0
    private var sharedH = 0
    @Volatile private var textureReady = false
    @Volatile private var renderInProgress = false

    // Blend shader.
    private var blendProgram = 0
    private var blendAPos = 0
    private var blendAUv = 0
    private var blendUTex = 0

    private val fallbackFbos = HashMap<Int, Int>()

    // Render thread.
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null
    private var renderEglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var renderEglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var renderEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var renderContextReady = false

    private var renderFboId = 0 // FBO on render context wrapping shared texture

    // Libass render buffers (on render thread).
    private val atlasBuf = ByteBuffer.allocateDirect(2048 * 4096).order(ByteOrder.nativeOrder())
    private val vertexBuf = ByteBuffer.allocateDirect(
        AssAtlasPipelineConfig.MAX_QUADS * AssAtlasPipelineConfig.BYTES_PER_QUAD
    ).order(ByteOrder.nativeOrder())

    // Atlas GL state (on render thread context).
    private val atlasTexIds = IntArray(AssAtlasPipelineConfig.MAX_ATLAS_PAGES)
    private var allocPages = 0
    private var atlasAllocW = 0
    private var renderGlProgram = 0
    private var rAPos = 0; private var rAUv = 0; private var rAColor = 0
    private var rUSurf = 0; private var rUTex = 0; private var rURgba = 0
    private var rVbo = 0
    private var renderGlInited = false

    private var initialized = false

    override fun setInputListener(l: GlShaderProgram.InputListener) { inputListener = l; if (!frameInFlight) l.onReadyToAcceptInputFrame() }
    override fun setOutputListener(l: GlShaderProgram.OutputListener) { outputListener = l }
    override fun setErrorListener(e: Executor, l: GlShaderProgram.ErrorListener) { errorExecutor = e; errorListener = l }

    override fun queueInputFrame(glObjProvider: GlObjectsProvider, inputTex: GlTextureInfo, pts: Long) {
        try {
            val w = inputTex.width; val h = inputTex.height
            if (!initialized) { initAll(w, h); initialized = true }
            if (w != sharedW || h != sharedH) resizeShared(w, h)

            val render = assHandler.render
            if (render != null && render.hasTrack && !renderInProgress) {
                val mediaUs = assHandler.frameTimeMapper.toMediaTimeUs(pts)
                val delayedUs = if (mediaUs != C.TIME_UNSET) mediaUs - assHandler.subtitleDelayUs else pts
                val timeMs = delayedUs / 1000
                val scale = AssAtlasPipelineConfig.renderScale
                val rw = if (scale < 1f) Math.round(w * scale) else w
                val rh = if (scale < 1f) Math.round(h * scale) else h
                renderInProgress = true
                renderHandler?.post {
                    try {
                        if (!renderContextReady) {
                            val ok = EGL14.eglMakeCurrent(renderEglDisplay, renderEglSurface, renderEglSurface, renderEglContext)
                            Log.i(TAG, "render eglMakeCurrent=$ok err=0x${Integer.toHexString(EGL14.eglGetError())}")
                            // Create FBO on THIS context wrapping the shared texture.
                            val fb = intArrayOf(0); GLES20.glGenFramebuffers(1, fb, 0); renderFboId = fb[0]
                            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, renderFboId)
                            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, sharedTexId, 0)
                            val st = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                            Log.i(TAG, "render FBO status=0x${Integer.toHexString(st)} fbo=$renderFboId tex=$sharedTexId")
                            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                            renderContextReady = true
                        }
                        render.setFrameSize(rw, rh)
                        val frame = render.renderFrameAtlas(timeMs, atlasBuf, 2048, 4096, vertexBuf)
                        if (frame != null && frame.hasOutput && frame.quadCount > 0) {
                            if (!renderGlInited) { initRenderGl(); renderGlInited = true }
                            // Draw subtitles to shared FBO.
                            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, renderFboId)
                            GLES20.glViewport(0, 0, w, h)
                            GLES20.glClearColor(0f, 0f, 0f, 0f)
                            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                            ensureAtlasTex(frame)
                            uploadAtlas(frame)
                            drawAtlas(frame, rw, rh)
                            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                            val fence = android.opengl.GLES30.glFenceSync(android.opengl.GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
                            android.opengl.GLES30.glFlush()
                            android.opengl.GLES30.glClientWaitSync(fence, android.opengl.GLES30.GL_SYNC_FLUSH_COMMANDS_BIT, 500_000_000L)
                            android.opengl.GLES30.glDeleteSync(fence)
                            textureReady = true
                        } else if (frame != null && frame.changed != 0 && frame.quadCount == 0) {
                            // Clear.
                            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, renderFboId)
                            GLES20.glClearColor(0f, 0f, 0f, 0f)
                            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                            val fence2 = android.opengl.GLES30.glFenceSync(android.opengl.GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
                            android.opengl.GLES30.glFlush()
                            android.opengl.GLES30.glClientWaitSync(fence2, android.opengl.GLES30.GL_SYNC_FLUSH_COMMANDS_BIT, 500_000_000L)
                            android.opengl.GLES30.glDeleteSync(fence2)
                            textureReady = true
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "render thread error", e)
                    }
                    renderInProgress = false
                }
            }

            // Blend shared texture onto video frame.
            if (textureReady && sharedTexId != 0) blendOnto(inputTex)
        } catch (e: Exception) {
            Log.e(TAG, "queueInputFrame failed", e)
            errorExecutor.execute { errorListener.onError(VideoFrameProcessingException.from(e, pts)) }
        }
        frameInFlight = true
        outputListener.onOutputFrameAvailable(inputTex, pts)
    }

    private fun blendOnto(tex: GlTextureInfo) {
        val fbo = if (tex.fboId != C.INDEX_UNSET) tex.fboId
            else fallbackFbos.getOrPut(tex.texId) { GlUtil.createFboForTexture(tex.texId) }
        val prev = intArrayOf(0); GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, prev, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, tex.width, tex.height)
        GLES20.glUseProgram(blendProgram)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sharedTexId)
        GLES20.glUniform1i(blendUTex, 0)
        GLES20.glEnableVertexAttribArray(blendAPos)
        GLES20.glVertexAttribPointer(blendAPos, 2, GLES20.GL_FLOAT, false, 0, QUAD_V)
        GLES20.glEnableVertexAttribArray(blendAUv)
        GLES20.glVertexAttribPointer(blendAUv, 2, GLES20.GL_FLOAT, false, 0, QUAD_U)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisableVertexAttribArray(blendAPos)
        GLES20.glDisableVertexAttribArray(blendAUv)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glUseProgram(0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prev[0])
        while (GLES20.glGetError() != GLES20.GL_NO_ERROR) {}
    }

    // -- Init --

    private fun initAll(w: Int, h: Int) {
        val mainDisp = EGL14.eglGetCurrentDisplay()
        val mainCtx = EGL14.eglGetCurrentContext()
        Log.i(TAG, "initAll mainCtx=$mainCtx mainDisp=$mainDisp")

        createSharedTex(w, h)
        initBlendShader()

        val cfgAttr = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE)
        val cfgs = arrayOfNulls<EGLConfig>(1); val nc = intArrayOf(0)
        EGL14.eglChooseConfig(mainDisp, cfgAttr, 0, cfgs, 0, 1, nc, 0)
        Log.i(TAG, "eglChooseConfig count=${nc[0]} config=${cfgs[0]}")

        renderEglDisplay = mainDisp
        // Try GLES3 shared context first, fallback GLES2.
        for (ver in intArrayOf(3, 2)) {
            val ctxAttr = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, ver, EGL14.EGL_NONE)
            renderEglContext = EGL14.eglCreateContext(mainDisp, cfgs[0]!!, mainCtx, ctxAttr, 0)
            if (renderEglContext != EGL14.EGL_NO_CONTEXT) {
                Log.i(TAG, "shared context created GLES$ver ctx=$renderEglContext")
                break
            }
            Log.w(TAG, "eglCreateContext GLES$ver failed err=0x${Integer.toHexString(EGL14.eglGetError())}")
        }

        val sfcAttr = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        renderEglSurface = EGL14.eglCreatePbufferSurface(mainDisp, cfgs[0]!!, sfcAttr, 0)
        Log.i(TAG, "pbuffer surface=$renderEglSurface")

        renderThread = HandlerThread("AssRenderGL", Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
        renderHandler = Handler(renderThread!!.looper)
    }

    private fun createSharedTex(w: Int, h: Int) {
        if (sharedTexId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(sharedTexId), 0)
            GLES20.glDeleteFramebuffers(1, intArrayOf(sharedFboId), 0)
        }
        val t = intArrayOf(0); GLES20.glGenTextures(1, t, 0); sharedTexId = t[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sharedTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        val f = intArrayOf(0); GLES20.glGenFramebuffers(1, f, 0); sharedFboId = f[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sharedFboId)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, sharedTexId, 0)
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        Log.i(TAG, "shared FBO status=0x${Integer.toHexString(status)} tex=$sharedTexId fbo=$sharedFboId ${w}x${h}")
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        sharedW = w; sharedH = h
    }

    private fun resizeShared(w: Int, h: Int) { createSharedTex(w, h); textureReady = false }

    private fun initBlendShader() {
        val fs = cs(GLES20.GL_FRAGMENT_SHADER, BFS)
        val vs = cs(GLES20.GL_VERTEX_SHADER, BVS)
        blendProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(blendProgram, vs); GLES20.glAttachShader(blendProgram, fs)
        GLES20.glLinkProgram(blendProgram); GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs)
        blendAPos = GLES20.glGetAttribLocation(blendProgram, "a_Pos")
        blendAUv = GLES20.glGetAttribLocation(blendProgram, "a_Uv")
        blendUTex = GLES20.glGetUniformLocation(blendProgram, "u_Tex")
    }

    // -- Render thread GL (atlas draw into shared FBO) --

    private fun initRenderGl() {
        val vs = cs(GLES20.GL_VERTEX_SHADER, AVS); val fs = cs(GLES20.GL_FRAGMENT_SHADER, AFS)
        renderGlProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(renderGlProgram, vs); GLES20.glAttachShader(renderGlProgram, fs)
        GLES20.glLinkProgram(renderGlProgram); GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs)
        rAPos = GLES20.glGetAttribLocation(renderGlProgram, "a_Position")
        rAUv = GLES20.glGetAttribLocation(renderGlProgram, "a_TexCoord")
        rAColor = GLES20.glGetAttribLocation(renderGlProgram, "a_Color")
        rUSurf = GLES20.glGetUniformLocation(renderGlProgram, "u_SurfaceSize")
        rUTex = GLES20.glGetUniformLocation(renderGlProgram, "u_Texture")
        rURgba = GLES20.glGetUniformLocation(renderGlProgram, "u_Rgba")
        val b = intArrayOf(0); GLES20.glGenBuffers(1, b, 0); rVbo = b[0]
        Log.i(TAG, "render GL init prog=$renderGlProgram vbo=$rVbo")
    }

    private fun ensureAtlasTex(frame: AssAtlasFrame) {
        val aw = frame.atlasWidth
        if (aw != atlasAllocW) {
            for (i in 0 until allocPages) if (atlasTexIds[i] != 0) { GLES20.glDeleteTextures(1, intArrayOf(atlasTexIds[i]), 0); atlasTexIds[i] = 0 }
            allocPages = 0; atlasAllocW = aw
        }
        while (allocPages < frame.pageHeights.size) {
            val t = intArrayOf(0); GLES20.glGenTextures(1, t, 0); atlasTexIds[allocPages] = t[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_ALPHA, aw, 4096, 0, GLES20.GL_ALPHA, GLES20.GL_UNSIGNED_BYTE, null)
            allocPages++
        }
    }

    private fun uploadAtlas(frame: AssAtlasFrame) {
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        for (p in 0 until frame.pageHeights.size) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasTexIds[p])
            val ph = frame.pageHeights[p]; val off = p * frame.atlasWidth * 4096
            atlasBuf.position(off); atlasBuf.limit(off + frame.atlasWidth * ph)
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, frame.atlasWidth, ph, GLES20.GL_ALPHA, GLES20.GL_UNSIGNED_BYTE, atlasBuf)
        }
        atlasBuf.position(0); atlasBuf.limit(atlasBuf.capacity())
    }

    private fun drawAtlas(frame: AssAtlasFrame, rw: Int, rh: Int) {
        GLES20.glUseProgram(renderGlProgram)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUniform2f(rUSurf, rw.toFloat(), rh.toFloat())
        GLES20.glUniform1f(rURgba, if (frame.mode == AssAtlasFrame.MODE_COMPOSITE) 1f else 0f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glUniform1i(rUTex, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, rVbo)
        val vb = frame.quadCount * AssAtlasPipelineConfig.BYTES_PER_QUAD
        vertexBuf.position(0); vertexBuf.limit(vb)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vb, vertexBuf, GLES20.GL_STREAM_DRAW)
        val st = AssAtlasPipelineConfig.BYTES_PER_VERTEX
        GLES20.glEnableVertexAttribArray(rAPos); GLES20.glVertexAttribPointer(rAPos, 2, GLES20.GL_FLOAT, false, st, 0)
        GLES20.glEnableVertexAttribArray(rAUv); GLES20.glVertexAttribPointer(rAUv, 2, GLES20.GL_FLOAT, false, st, 8)
        GLES20.glEnableVertexAttribArray(rAColor); GLES20.glVertexAttribPointer(rAColor, 4, GLES20.GL_FLOAT, false, st, 16)
        var qo = 0
        for (p in 0 until frame.pageHeights.size) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasTexIds[p])
            val pq = frame.pageQuadCounts[p]; GLES20.glDrawArrays(GLES20.GL_TRIANGLES, qo * 6, pq * 6); qo += pq
        }
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisableVertexAttribArray(rAPos); GLES20.glDisableVertexAttribArray(rAUv); GLES20.glDisableVertexAttribArray(rAColor)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0); GLES20.glUseProgram(0)
        while (GLES20.glGetError() != GLES20.GL_NO_ERROR) {}
    }

    private fun cs(type: Int, src: String): Int { val s = GLES20.glCreateShader(type); GLES20.glShaderSource(s, src); GLES20.glCompileShader(s); return s }

    override fun releaseOutputFrame(o: GlTextureInfo) { frameInFlight = false; inputListener.onInputFrameProcessed(o); inputListener.onReadyToAcceptInputFrame() }
    override fun signalEndOfCurrentInputStream() { outputListener.onCurrentOutputStreamEnded() }
    override fun flush() { frameInFlight = false; textureReady = false; inputListener.onFlush(); inputListener.onReadyToAcceptInputFrame() }
    override fun release() {
        renderThread?.quitSafely()
        if (renderEglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroySurface(renderEglDisplay, renderEglSurface)
            EGL14.eglDestroyContext(renderEglDisplay, renderEglContext)
        }
        fallbackFbos.values.forEach { GlUtil.deleteFbo(it) }
        if (sharedTexId != 0) GLES20.glDeleteTextures(1, intArrayOf(sharedTexId), 0)
        if (sharedFboId != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(sharedFboId), 0)
        if (blendProgram != 0) GLES20.glDeleteProgram(blendProgram)
    }

    companion object {
        private const val TAG = "AssVideoEffectAsync"
        private val QUAD_V = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f)); position(0) }
        private val QUAD_U = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(0f,0f,1f,0f,0f,1f,1f,1f)); position(0) }
        private const val BVS = "attribute vec2 a_Pos; attribute vec2 a_Uv; varying vec2 v_Uv; void main() { gl_Position = vec4(a_Pos, 0.0, 1.0); v_Uv = a_Uv; }"
        private const val BFS = "precision mediump float; varying vec2 v_Uv; uniform sampler2D u_Tex; void main() { gl_FragColor = texture2D(u_Tex, v_Uv); }"
        private const val AVS = """
            attribute vec2 a_Position; attribute vec2 a_TexCoord; attribute vec4 a_Color;
            uniform vec2 u_SurfaceSize; varying vec2 v_TexCoord; varying vec4 v_Color;
            void main() { vec2 c = (a_Position / u_SurfaceSize) * 2.0 - 1.0; c.y = -c.y;
                gl_Position = vec4(c, 0.0, 1.0); v_TexCoord = a_TexCoord; v_Color = a_Color; }"""
        private const val AFS = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float; precision highp sampler2D;
            #else
            precision mediump float; precision mediump sampler2D;
            #endif
            varying vec2 v_TexCoord; varying vec4 v_Color; uniform sampler2D u_Texture; uniform float u_Rgba;
            void main() { vec4 t = texture2D(u_Texture, v_TexCoord); float a = v_Color.a * t.a;
                gl_FragColor = mix(vec4(v_Color.rgb * a, a), t, u_Rgba); }"""
    }
}
