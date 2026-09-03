package com.example.smartcropapp.render;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class CropShaderProgram {

    private static final String VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;\n" +
            "uniform mat4 uSTMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTextureCoord;\n" +
            "varying vec2 vTextureCoord;\n" +
            "varying vec2 vPosition;\n" +
            "void main() {\n" +
            "    gl_Position = uMVPMatrix * aPosition;\n" +
            "    vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
            "    vPosition = aPosition.xy * 0.5 + 0.5;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "varying vec2 vTextureCoord;\n" +
            "varying vec2 vPosition;\n" +
            "void main() {\n" +
            "    vec4 color = texture2D(sTexture, vTextureCoord);\n" +
            "    gl_FragColor = color;\n" +
            "}\n";

    private final FloatBuffer vertexBuffer;

    private final int program;
    private final int aPositionHandle;
    private final int aTextureCoordHandle;
    private final int uMVPMatrixHandle;
    private final int uSTMatrixHandle;

    private static final float[] VERTEX_DATA = {
            -1f, -1f, 0f, 0f, 0f,
             1f, -1f, 0f, 1f, 0f,
            -1f,  1f, 0f, 0f, 1f,
             1f,  1f, 0f, 1f, 1f
    };

    public CropShaderProgram() {

        vertexBuffer =
                ByteBuffer
                        .allocateDirect(
                                VERTEX_DATA.length * 4)
                        .order(ByteOrder.nativeOrder())
                        .asFloatBuffer();

        vertexBuffer.put(VERTEX_DATA).position(0);

        program =
                createProgram(
                        VERTEX_SHADER,
                        FRAGMENT_SHADER);

        aPositionHandle =
                GLES20.glGetAttribLocation(
                        program,
                        "aPosition");

        aTextureCoordHandle =
                GLES20.glGetAttribLocation(
                        program,
                        "aTextureCoord");

        uMVPMatrixHandle =
                GLES20.glGetUniformLocation(
                        program,
                        "uMVPMatrix");

        uSTMatrixHandle =
                GLES20.glGetUniformLocation(
                        program,
                        "uSTMatrix");

    }

    public void draw(
            int textureId,
            float[] stMatrix,
            float cropCenterX,
            float cropCenterY,
            float cropWidthNorm,
            float cropHeightNorm) {

        GLES20.glUseProgram(program);

        float w = clamp(
                cropWidthNorm,
                0.05f,
                1.0f);

        float h = clamp(
                cropHeightNorm,
                0.05f,
                1.0f);

        float halfW = w * 0.5f;
        float halfH = h * 0.5f;

        float cx = clamp(
                cropCenterX,
                halfW,
                1.0f - halfW);

        float cy = clamp(
                cropCenterY,
                halfH,
                1.0f - halfH);

        /*
         * Explicit crop rectangle.
         *
         * Texture coordinate:
         *
         * left   = cx - halfW
         * right  = cx + halfW
         * bottom = cy - halfH
         * top    = cy + halfH
         */

        float left = cx - halfW;
        float right = cx + halfW;
        float bottom = cy - halfH;
        float top = cy + halfH;

        float sx = right - left;
        float sy = top - bottom;

        float tx = left;
        float ty = bottom;

        /*
         * Build texture matrix:
         *
         * output 0..1
         *      ↓
         * crop rectangle
         *
         * Jadi masing-masing panel benar-benar
         * mengambil area sumber yang berbeda.
         */

        float[] cropMatrix =
                new float[16];

        Matrix.setIdentityM(
                cropMatrix,
                0);

        Matrix.translateM(
                cropMatrix,
                0,
                tx,
                ty,
                0f);

        Matrix.scaleM(
                cropMatrix,
                0,
                sx,
                sy,
                1f);

        float[] combinedST =
                new float[16];

        Matrix.multiplyMM(
                combinedST,
                0,
                cropMatrix,
                0,
                stMatrix,
                0);

        float[] mvpMatrix =
                new float[16];

        Matrix.setIdentityM(
                mvpMatrix,
                0);

        vertexBuffer.position(0);

        GLES20.glVertexAttribPointer(
                aPositionHandle,
                3,
                GLES20.GL_FLOAT,
                false,
                20,
                vertexBuffer);

        GLES20.glEnableVertexAttribArray(
                aPositionHandle);

        vertexBuffer.position(3);

        GLES20.glVertexAttribPointer(
                aTextureCoordHandle,
                2,
                GLES20.GL_FLOAT,
                false,
                20,
                vertexBuffer);

        GLES20.glEnableVertexAttribArray(
                aTextureCoordHandle);

        GLES20.glUniformMatrix4fv(
                uMVPMatrixHandle,
                1,
                false,
                mvpMatrix,
                0);

        GLES20.glUniformMatrix4fv(
                uSTMatrixHandle,
                1,
                false,
                combinedST,
                0);

        GLES20.glActiveTexture(
                GLES20.GL_TEXTURE0);

        GLES20.glBindTexture(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                textureId);

        GLES20.glDrawArrays(
                GLES20.GL_TRIANGLE_STRIP,
                0,
                4);

        GLES20.glDisableVertexAttribArray(
                aPositionHandle);

        GLES20.glDisableVertexAttribArray(
                aTextureCoordHandle);
    }

    private float clamp(
            float value,
            float min,
            float max) {

        return Math.max(
                min,
                Math.min(
                        max,
                        value));
    }

    private int createProgram(
            String vertexSrc,
            String fragmentSrc) {

        int vertexShader =
                loadShader(
                        GLES20.GL_VERTEX_SHADER,
                        vertexSrc);

        int fragmentShader =
                loadShader(
                        GLES20.GL_FRAGMENT_SHADER,
                        fragmentSrc);

        int prog =
                GLES20.glCreateProgram();

        GLES20.glAttachShader(
                prog,
                vertexShader);

        GLES20.glAttachShader(
                prog,
                fragmentShader);

        GLES20.glLinkProgram(prog);

        int[] linkStatus =
                new int[1];

        GLES20.glGetProgramiv(
                prog,
                GLES20.GL_LINK_STATUS,
                linkStatus,
                0);

        if (linkStatus[0] == 0) {

            String log =
                    GLES20.glGetProgramInfoLog(
                            prog);

            GLES20.glDeleteProgram(prog);

            throw new RuntimeException(
                    "Link program gagal: "
                            + log);
        }

        return prog;
    }

    private int loadShader(
            int type,
            String src) {

        int shader =
                GLES20.glCreateShader(type);

        GLES20.glShaderSource(
                shader,
                src);

        GLES20.glCompileShader(
                shader);

        int[] compiled =
                new int[1];

        GLES20.glGetShaderiv(
                shader,
                GLES20.GL_COMPILE_STATUS,
                compiled,
                0);

        if (compiled[0] == 0) {

            String log =
                    GLES20.glGetShaderInfoLog(
                            shader);

            GLES20.glDeleteShader(
                    shader);

            throw new RuntimeException(
                    "Compile shader gagal: "
                            + log);
        }

        return shader;
    }
}
