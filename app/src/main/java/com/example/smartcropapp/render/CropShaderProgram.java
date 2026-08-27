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
            "void main() {\n" +
            "  gl_Position = uMVPMatrix * aPosition;\n" +
            "  vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
            "}\n";

    private final float[] vertexData = {
            -1f, -1f, 0, 0f, 0f,
             1f, -1f, 0, 1f, 0f,
            -1f,  1f, 0, 0f, 1f,
             1f,  1f, 0, 1f, 1f,
    };

    private final int program;
    private final int aPositionHandle;
    private final int aTextureCoordHandle;
    private final int uMVPMatrixHandle;
    private final int uSTMatrixHandle;
    private final FloatBuffer vertexBuffer;

    public CropShaderProgram() {
        vertexBuffer = ByteBuffer.allocateDirect(vertexData.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertexBuffer.put(vertexData).position(0);

        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition");
        aTextureCoordHandle = GLES20.glGetAttribLocation(program, "aTextureCoord");
        uMVPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix");
        uSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix");
    }

    public void draw(int textureId, float[] stMatrix, float cropCenterX, float cropCenterY,
                      float cropWidthNorm, float cropHeightNorm) {
        GLES20.glUseProgram(program);

        float[] mvpMatrix = new float[16];
        Matrix.setIdentityM(mvpMatrix, 0);

        float[] cropMatrix = new float[16];
        Matrix.setIdentityM(cropMatrix, 0);
        Matrix.translateM(cropMatrix, 0, cropCenterX - cropWidthNorm / 2f, cropCenterY - cropHeightNorm / 2f, 0);
        Matrix.scaleM(cropMatrix, 0, cropWidthNorm, cropHeightNorm, 1f);

        float[] combinedST = new float[16];
        Matrix.multiplyMM(combinedST, 0, stMatrix, 0, cropMatrix, 0);

        vertexBuffer.position(0);
        GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false, 20, vertexBuffer);
        GLES20.glEnableVertexAttribArray(aPositionHandle);

        vertexBuffer.position(3);
        GLES20.glVertexAttribPointer(aTextureCoordHandle, 2, GLES20.GL_FLOAT, false, 20, vertexBuffer);
        GLES20.glEnableVertexAttribArray(aTextureCoordHandle);

        GLES20.glUniformMatrix4fv(uMVPMatrixHandle, 1, false, mvpMatrix, 0);
        GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, combinedST, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private int createProgram(String vertexSrc, String fragmentSrc) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSrc);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc);
        int prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, vertexShader);
        GLES20.glAttachShader(prog, fragmentShader);
        GLES20.glLinkProgram(prog);
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(prog);
            GLES20.glDeleteProgram(prog);
            throw new RuntimeException("Link program gagal: " + log);
        }
        return prog;
    }

    private int loadShader(int type, String src) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, src);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new RuntimeException("Compile shader gagal: " + log);
        }
        return shader;
    }
}
