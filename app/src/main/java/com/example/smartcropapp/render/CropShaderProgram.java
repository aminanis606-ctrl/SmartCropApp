package com.example.smartcropapp.render;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class CropShaderProgram {

    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTextureCoord;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform float uLeft;\n" +
            "uniform float uBottom;\n" +
            "uniform float uWidth;\n" +
            "uniform float uHeight;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vTextureCoord = vec2(\n" +
            "        uLeft + aTextureCoord.x * uWidth,\n" +
            "        uBottom + aTextureCoord.y * uHeight\n" +
            "    );\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "varying vec2 vTextureCoord;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
            "}\n";

    private static final float[] VERTEX_DATA = {
            -1f, -1f, 0f, 0f, 0f,
             1f, -1f, 0f, 1f, 0f,
            -1f,  1f, 0f, 0f, 1f,
             1f,  1f, 0f, 1f, 1f
    };

    private final FloatBuffer vertexBuffer;
    private final int program;
    private final int aPositionHandle;
    private final int aTextureCoordHandle;
    private final int uLeftHandle;
    private final int uBottomHandle;
    private final int uWidthHandle;
    private final int uHeightHandle;

    public CropShaderProgram() {
        vertexBuffer =
                ByteBuffer
                        .allocateDirect(VERTEX_DATA.length * 4)
                        .order(ByteOrder.nativeOrder())
                        .asFloatBuffer();

        vertexBuffer.put(VERTEX_DATA).position(0);

        program = createProgram(
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

        uLeftHandle =
                GLES20.glGetUniformLocation(
                        program,
                        "uLeft");

        uBottomHandle =
                GLES20.glGetUniformLocation(
                        program,
                        "uBottom");

        uWidthHandle =
                GLES20.glGetUniformLocation(
                        program,
                        "uWidth");

        uHeightHandle =
                GLES20.glGetUniformLocation(
                        program,
                        "uHeight");
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

        float left = cx - halfW;
        float bottom = cy - halfH;

        GLES20.glUniform1f(
                uLeftHandle,
                left);

        GLES20.glUniform1f(
                uBottomHandle,
                bottom);

        GLES20.glUniform1f(
                uWidthHandle,
                w);

        GLES20.glUniform1f(
                uHeightHandle,
                h);

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

        int[] linkStatus = new int[1];

        GLES20.glGetProgramiv(
                prog,
                GLES20.GL_LINK_STATUS,
                linkStatus,
                0);

        if (linkStatus[0] == 0) {
            String error =
                    GLES20.glGetProgramInfoLog(prog);

            GLES20.glDeleteProgram(prog);

            throw new RuntimeException(
                    "Program link failed: " + error);
        }

        return prog;
    }

    private int loadShader(
            int type,
            String source) {

        int shader =
                GLES20.glCreateShader(type);

        GLES20.glShaderSource(
                shader,
                source);

        GLES20.glCompileShader(shader);

        int[] status = new int[1];

        GLES20.glGetShaderiv(
                shader,
                GLES20.GL_COMPILE_STATUS,
                status,
                0);

        if (status[0] == 0) {
            String error =
                    GLES20.glGetShaderInfoLog(shader);

            GLES20.glDeleteShader(shader);

            throw new RuntimeException(
                    "Shader compile failed: " + error);
        }

        return shader;
    }
}
