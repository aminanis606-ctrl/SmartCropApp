package com.example.smartcropapp.render;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.view.Surface;

public class GlRenderContext {
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglEncoderSurface = EGL14.EGL_NO_SURFACE;

    private int decoderTextureId = -1;
    private SurfaceTexture decoderSurfaceTexture;
    private Surface decoderInputSurface;

    public void setupEncoderSurface(Surface encoderSurface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw new RuntimeException("eglGetDisplay gagal");

        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw new RuntimeException("eglInitialize gagal");
        }

        int[] attribList = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                0x3142 /* EGL_RECORDABLE_ANDROID */, 1,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0);
        if (numConfigs[0] == 0) throw new RuntimeException("eglChooseConfig tidak menemukan config");

        int[] ctxAttribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw new RuntimeException("eglCreateContext gagal");

        int[] surfaceAttribs = { EGL14.EGL_NONE };
        eglEncoderSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], encoderSurface, surfaceAttribs, 0);
        if (eglEncoderSurface == EGL14.EGL_NO_SURFACE) throw new RuntimeException("eglCreateWindowSurface gagal");

        makeCurrent();
        createDecoderTexture();
    }

    private void makeCurrent() {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglEncoderSurface, eglEncoderSurface, eglContext)) {
            throw new RuntimeException("eglMakeCurrent gagal");
        }
    }

    private void createDecoderTexture() {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        decoderTextureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, decoderTextureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        decoderSurfaceTexture = new SurfaceTexture(decoderTextureId);
        decoderInputSurface = new Surface(decoderSurfaceTexture);
    }

    public Surface getDecoderInputSurface() { return decoderInputSurface; }
    public SurfaceTexture getDecoderSurfaceTexture() { return decoderSurfaceTexture; }
    public int getDecoderTextureId() { return decoderTextureId; }

    public void swapBuffers() {
        EGL14.eglSwapBuffers(eglDisplay, eglEncoderSurface);
    }

    public void setPresentationTime(long nanos) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglEncoderSurface, nanos);
    }

    public void release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(eglDisplay, eglEncoderSurface);
            EGL14.eglDestroyContext(eglDisplay, eglContext);
            EGL14.eglTerminate(eglDisplay);
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        eglContext = EGL14.EGL_NO_CONTEXT;
        eglEncoderSurface = EGL14.EGL_NO_SURFACE;

        if (decoderSurfaceTexture != null) decoderSurfaceTexture.release();
        if (decoderInputSurface != null) decoderInputSurface.release();
    }
}
