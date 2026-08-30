package com.example.smartcropapp.core;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.opengl.GLES20;
import android.util.Log;
import android.view.Surface;

import com.example.smartcropapp.render.CropShaderProgram;
import com.example.smartcropapp.render.GlRenderContext;

import java.io.File;
import java.nio.ByteBuffer;

public class Pass3Renderer {
    private static final String TAG = "Pass3Renderer";
    private static final String OUTPUT_MIME = "video/avc";
    private static final int OUTPUT_WIDTH = 720;
    private static final int OUTPUT_HEIGHT = 1280;
    private static final int OUTPUT_BITRATE = 2_500_000;
    private static final int OUTPUT_FPS = 30;
    private static final long TIMEOUT_US = 10000;

    public static File render(Context context, Uri sourceVideoUri, File trajectoryFile, File outputVideoFile) {
        try {
            TrajectoryReader trajectory = TrajectoryReader.load(trajectoryFile);
            renderVideoTrack(context, sourceVideoUri, outputVideoFile, trajectory);
            return outputVideoFile;
        } catch (Exception e) {
            Log.e(TAG, "Pass 3 gagal", e);
            throw new RuntimeException("Pass 3 gagal: " + e.getMessage(), e);
        }
    }

    private static void renderVideoTrack(Context context, Uri sourceVideoUri, File outputFile, TrajectoryReader trajectory) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(context, sourceVideoUri, null);

        int videoTrackIndex = -1;
        MediaFormat inputFormat = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith("video/")) {
                videoTrackIndex = i;
                inputFormat = format;
                break;
            }
        }

        if (videoTrackIndex == -1 || inputFormat == null) {
            throw new RuntimeException("Tidak ditemukan track video");
        }

        extractor.selectTrack(videoTrackIndex);

        int srcWidth = inputFormat.getInteger(MediaFormat.KEY_WIDTH);
        int srcHeight = inputFormat.getInteger(MediaFormat.KEY_HEIGHT);

        MediaCodec decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));
        decoder.configure(inputFormat, null, null, 0);
        SurfaceTexture decoderSurfaceTexture = new SurfaceTexture(0);
        decoderSurfaceTexture.setDefaultBufferSize(srcWidth, srcHeight);
        Surface decoderSurface = new Surface(decoderSurfaceTexture);
        decoder.start();

        // FIX 1: Gunakan konstruktor tanpa argumen sesuai API asli
        GlRenderContext glContext = new GlRenderContext();
        CropShaderProgram shader = new CropShaderProgram();

        MediaFormat outputFormat = MediaFormat.createVideoFormat(OUTPUT_MIME, OUTPUT_WIDTH, OUTPUT_HEIGHT);
        outputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, OUTPUT_BITRATE);
        outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, OUTPUT_FPS);
        outputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        MediaCodec encoder = MediaCodec.createEncoderByType(OUTPUT_MIME);
        encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface encoderSurface = encoder.createInputSurface();
        encoder.start();

        MediaMuxer muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int muxerVideoTrack = -1;
        boolean muxerStarted = false;

        ByteBuffer[] decoderInputBuffers = decoder.getInputBuffers();
        MediaCodec.BufferInfo decoderInfo = new MediaCodec.BufferInfo();
        MediaCodec.BufferInfo encoderInfo = new MediaCodec.BufferInfo();
        boolean decoderEOS = false;
        boolean encoderEOS = false;

        float cropWidthNorm = clamp(
                (float) OUTPUT_WIDTH / srcWidth * (srcHeight / (float) OUTPUT_HEIGHT),
                0.1f, 1f);
        float cropHeightNorm = 1.0f;
        final int panelH = OUTPUT_HEIGHT / 2;

        while (!encoderEOS) {
            if (!decoderEOS) {
                int inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
                if (inputBufIndex >= 0) {
                    ByteBuffer inputBuf = decoderInputBuffers[inputBufIndex];
                    int sampleSize = extractor.readSampleData(inputBuf, 0);
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inputBufIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        decoderEOS = true;
                    } else {
                        decoder.queueInputBuffer(inputBufIndex, 0, sampleSize, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }

            int outputBufIndex = decoder.dequeueOutputBuffer(decoderInfo, TIMEOUT_US);
            if (outputBufIndex >= 0) {
                decoder.releaseOutputBuffer(outputBufIndex, true);
                decoderSurfaceTexture.updateTexImage();

                float[] stMatrix = new float[16];
                decoderSurfaceTexture.getTransformMatrix(stMatrix);

                // === FORCE SPLIT DEBUG MODE ===
                TrajectoryReader.Point topP = new TrajectoryReader.Point(0.5f, 0.5f, 0.5f);
                TrajectoryReader.Point botP = new TrajectoryReader.Point(0.5f, 0.5f, 0.5f);
                TrajectoryReader.ShotResult shotResult = new TrajectoryReader.ShotResult("split", null, topP, botP);

                Log.d(TAG, "DEBUG FORCE SPLIT: topX=" + shotResult.top.x + ", topY=" + shotResult.top.y 
                        + ", bottomX=" + shotResult.bottom.x + ", bottomY=" + shotResult.bottom.y);

                // FIX 2: Hapus GLES20.glUseProgram(shader.getProgram()) karena tidak ada di API asli
                // shader.draw() sudah menangani glUseProgram secara internal

                /* Panel ATAS - Warna ABU-ABU untuk debug */
                GLES20.glClearColor(0.3f, 0.3f, 0.3f, 1f);
                GLES20.glViewport(0, panelH, OUTPUT_WIDTH, panelH);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                shader.draw(
                        glContext.getDecoderTextureId(),
                        stMatrix,
                        shotResult.top.x,
                        shotResult.top.y,
                        cropWidthNorm,
                        cropHeightNorm);

                /* Panel BAWAH - Warna HITAM untuk debug */
                GLES20.glClearColor(0f, 0f, 0f, 1f);
                GLES20.glViewport(0, 0, OUTPUT_WIDTH, panelH);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                shader.draw(
                        glContext.getDecoderTextureId(),
                        stMatrix,
                        shotResult.bottom.x,
                        shotResult.bottom.y,
                        cropWidthNorm,
                        cropHeightNorm);

                glContext.setPresentationTime(decoderInfo.presentationTimeUs * 1000);
                glContext.swapBuffers();
            } else if (outputBufIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxerStarted) {
                    muxerVideoTrack = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                }
            }

            int encOutIndex = encoder.dequeueOutputBuffer(encoderInfo, TIMEOUT_US);
            if (encOutIndex >= 0) {
                ByteBuffer encodedData = encoder.getOutputBuffer(encOutIndex);
                if ((encoderInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    encoderInfo.size = 0;
                }
                if (encoderInfo.size != 0 && muxerStarted) {
                    encodedData.position(encoderInfo.offset);
                    encodedData.limit(encoderInfo.offset + encoderInfo.size);
                    muxer.writeSampleData(muxerVideoTrack, encodedData, encoderInfo);
                }
                encoder.releaseOutputBuffer(encOutIndex, false);
                if ((encoderInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    encoderEOS = true;
                }
            }

            if ((decoderInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                decoderEOS = true;
                encoder.signalEndOfInputStream();
            }
        }

        decoder.stop();
        decoder.release();
        decoderSurfaceTexture.release();
        decoderSurface.release();
        encoder.stop();
        encoder.release();
        encoderSurface.release();
        muxer.stop();
        muxer.release();
        extractor.release();
        glContext.release();
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
