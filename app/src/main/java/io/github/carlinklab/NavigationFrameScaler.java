package io.github.carlinklab;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.EGLExt;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Downsamples the SDK's density-aware display to the negotiated car video size on the GPU. */
final class NavigationFrameScaler implements AutoCloseable {
    private final HandlerThread thread = new HandlerThread("navigation-scale");
    private final Handler handler;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final int width, height;
    private final long intervalNs;
    private final Consumer<String> failure;
    private final float[] transform = new float[16];
    private final FloatBuffer vertices = floats(-1,-1, 1,-1, -1,1, 1,1);
    private final FloatBuffer coordinates = floats(0,0, 1,0, 0,1, 1,1);
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface window = EGL14.EGL_NO_SURFACE;
    private SurfaceTexture texture;
    private Surface input;
    private int program, textureId, position, uv, matrix;
    private long lastFrame;
    private volatile Exception startupError;

    NavigationFrameScaler(Surface encoderSurface, int width, int height, int renderWidth, int renderHeight,
                          int fps, Consumer<String> failure) throws Exception {
        this.width = width;
        this.height = height;
        this.intervalNs = 1_000_000_000L / Math.max(1, fps);
        this.failure = failure;
        thread.start();
        handler = new Handler(thread.getLooper());
        CountDownLatch initialized = new CountDownLatch(1);
        handler.post(() -> {
            try { initialize(encoderSurface, renderWidth, renderHeight); }
            catch (Exception error) { startupError = error; }
            finally { initialized.countDown(); }
        });
        if (!initialized.await(8, TimeUnit.SECONDS)) { close(); throw new IllegalStateException("地图画面缩放初始化超时"); }
        if (startupError != null) { close(); throw startupError; }
    }
    Surface inputSurface() { return input; }
    private static FloatBuffer floats(float... values) {
        FloatBuffer data = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        data.put(values).position(0);
        return data;
    }
    private void initialize(Surface target, int renderWidth, int renderHeight) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        require(display != EGL14.EGL_NO_DISPLAY && EGL14.eglInitialize(display, version, 0, version, 1), "EGL display");
        int[] attributes = { EGL14.EGL_RED_SIZE,8, EGL14.EGL_GREEN_SIZE,8, EGL14.EGL_BLUE_SIZE,8,
                EGL14.EGL_ALPHA_SIZE,8, EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT, 0x3142,1, EGL14.EGL_NONE };
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        require(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0, "EGL config");
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{ EGL14.EGL_CONTEXT_CLIENT_VERSION,2, EGL14.EGL_NONE }, 0);
        require(context != EGL14.EGL_NO_CONTEXT, "EGL context");
        window = EGL14.eglCreateWindowSurface(display, configs[0], target, new int[]{ EGL14.EGL_NONE }, 0);
        require(window != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, window, window, context), "EGL surface");
        int vertex = shader(GLES20.GL_VERTEX_SHADER, "attribute vec2 aPosition; attribute vec2 aTex; uniform mat4 uMatrix;"
                + " varying vec2 vTex; void main(){gl_Position=vec4(aPosition,0.0,1.0);vTex=(uMatrix*vec4(aTex,0.0,1.0)).xy;}");
        int fragment = shader(GLES20.GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external : require\n"
                + "precision mediump float; uniform samplerExternalOES uTexture; varying vec2 vTex;"
                + "void main(){gl_FragColor=texture2D(uTexture,vTex);}");
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        require(linked[0] != 0, "GL link");
        position = GLES20.glGetAttribLocation(program, "aPosition");
        uv = GLES20.glGetAttribLocation(program, "aTex");
        matrix = GLES20.glGetUniformLocation(program, "uMatrix");
        int[] names = new int[1];
        GLES20.glGenTextures(1, names, 0);
        textureId = names[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        texture = new SurfaceTexture(textureId);
        texture.setDefaultBufferSize(renderWidth, renderHeight);
        texture.setOnFrameAvailableListener(available -> draw(), handler);
        input = new Surface(texture);
    }
    private static int shader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) { GLES20.glDeleteShader(shader); throw new IllegalStateException("地图画面 GL 着色器失败"); }
        return shader;
    }
    private static void require(boolean success, String stage) {
        if (!success) throw new IllegalStateException(stage + " failed");
    }
    private void draw() {
        if (closed.get()) return;
        try {
            texture.updateTexImage();
            long time = texture.getTimestamp();
            if (time == 0) time = System.nanoTime();
            if (lastFrame != 0 && time - lastFrame < intervalNs) return;
            texture.getTransformMatrix(transform);
            GLES20.glViewport(0, 0, width, height);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0);
            GLES20.glUniformMatrix4fv(matrix, 1, false, transform, 0);
            GLES20.glEnableVertexAttribArray(position);
            GLES20.glEnableVertexAttribArray(uv);
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices);
            GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 0, coordinates);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            EGLExt.eglPresentationTimeANDROID(display, window, time);
            require(EGL14.eglSwapBuffers(display, window), "GL swap");
            lastFrame = time;
        } catch (Exception error) {
            if (!closed.get()) { failure.accept("独立导航画面缩放失败"); close(); }
        }
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        handler.post(() -> {
            if (input != null) { input.release(); input = null; }
            if (texture != null) { texture.release(); texture = null; }
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window);
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
                EGL14.eglReleaseThread();
                EGL14.eglTerminate(display);
            }
            thread.quitSafely();
        });
    }
}
