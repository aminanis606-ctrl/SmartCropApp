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
        if (context == null || sourceVideoUri == null || trajectoryFile == null || outputVideoFile == null) {
            throw new NullPointerException("Parameter render tidak boleh null");
        }
        
        Log.d(TAG, "Starting Pass 3...");
        
        TrajectoryReader trajectory = null;
        try {
            trajectory = TrajectoryReader.load(trajectoryFile);
        } catch (Exception e) {
            throw new RuntimeException("Gagal memuat trajectory: " + e.getMessage(), e);
        }

        if (trajectory == null) {
            throw new RuntimeException("Trajectory invalid/null");
        }

        try {
            renderVideoTrack(context, sourceVideoUri, outputVideoFile, trajectory);
            Log.d(TAG, "Pass 3 Finished Successfully");
            return outputVideoFile;
        } catch (Exception e) {
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
            if (format.getString(MediaFormat.KEY_MIME).startsWith("video/")) {
                videoTrackIndex = i;
                inputFormat = format;
                break;
            }
        }
        if (videoTrackIndex == -1) throw new RuntimeException("No video track");

        extractor.selectTrack(videoTrackIndex);
        int srcWidth = inputFormat.getInteger(MediaFormat.KEY_WIDTH);
        int srcHeight = inputFormat.getInteger(MediaFormat.KEY_HEIGHT);

        // Setup Decoder
        MediaCodec decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));
        decoder.configure(inputFormat, null, null, 0);
        SurfaceTexture decoderST = new SurfaceTexture(0);
        decoderST.setDefaultBufferSize(srcWidth, srcHeight);
        Surface decoderSurface = new Surface(decoderST);
        
        // Setup Encoder
        MediaFormat outputFormat = MediaFormat.createVideoFormat(OUTPUT_MIME, OUTPUT_WIDTH, OUTPUT_HEIGHT);
        outputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, OUTPUT_BITRATE);
        outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, OUTPUT_FPS);
        outputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        MediaCodec encoder = MediaCodec.createEncoderByType(OUTPUT_MIME);
        encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface encoderSurface = encoder.createInputSurface();
        
        MediaMuxer muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        
        // Start Components
        decoder.start();
        encoder.start();
        
        GlRenderContext glContext = new GlRenderContext();
        glContext.setupEncoderSurface(encoderSurface);
        CropShaderProgram shader = new CropShaderProgram();

        boolean muxerStarted = false;
        int muxerTrackIndex = -1;
        boolean inputDone = false;
        boolean encoderDone = false;

        float cropWidthNorm = clamp((float) OUTPUT_WIDTH / srcWidth * (srcHeight / (float) OUTPUT_HEIGHT), 0.1f, 1f);
        final int panelH = OUTPUT_HEIGHT / 2;

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        while (!encoderDone) {
            // 1. Feed Decoder (Input Side)
            if (!inputDone) {
                int inIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
                if (inIndex >= 0) {
                    ByteBuffer buf = decoder.getInputBuffer(inIndex);
                    int sampleSize = extractor.readSampleData(buf, 0);
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                        Log.d(TAG, "Decoder Input EOS");
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }

            // 2. Process Decoder Output -> Render -> Encoder Input
            boolean isEos = false;
            int outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
            if (outIndex >= 0) {
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    isEos = true;
                    Log.d(TAG, "Decoder Output EOS");
                }
                
                decoder.releaseOutputBuffer(outIndex, true); // Release to SurfaceTexture
                decoderST.updateTexImage();
                
                float[] stMatrix = new float[16];
                decoderST.getTransformMatrix(stMatrix);

                // RENDER SPLIT
                GLES20.glClearColor(0.3f, 0.3f, 0.3f, 1f); // Abu-abu
                GLES20.glViewport(0, panelH, OUTPUT_WIDTH, panelH);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                shader.draw(glContext.getDecoderTextureId(), stMatrix, 0.5f, 0.5f, cropWidthNorm, 1.0f);

                GLES20.glClearColor(0f, 0f, 0f, 1f); // Hitam
                GLES20.glViewport(0, 0, OUTPUT_WIDTH, panelH);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                shader.draw(glContext.getDecoderTextureId(), stMatrix, 0.5f, 0.5f, cropWidthNorm, 1.0f);

                glContext.setPresentationTime(info.presentationTimeUs * 1000);
                glContext.swapBuffers(); // Submit to Encoder Surface

                if (isEos) {
                    encoder.signalEndOfInputStream();
                    Log.d(TAG, "Signaled Encoder EOS");
                }
            }

            // 3. Drain Encoder (Output Side)
            int encIndex = encoder.dequeueOutputBuffer(info, TIMEOUT_US);
            if (encIndex >= 0) {
                if (!muxerStarted) {
                    muxerTrackIndex = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                    Log.d(TAG, "Muxer Started");
                }
                
                if (info.size > 0 && muxerStarted) {
                    ByteBuffer encodedData = encoder.getOutputBuffer(encIndex);
                    encodedData.position(info.offset);
                    encodedData.limit(info.offset + info.size);
                    muxer.writeSampleData(muxerTrackIndex, encodedData, info);
                }
                
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    encoderDone = true;
                    Log.d(TAG, "Encoder Done");
                }
                encoder.releaseOutputBuffer(encIndex, false);
            }
        }

        // Cleanup
        decoder.stop(); decoder.release();
        decoderST.release(); decoderSurface.release();
        encoder.stop(); encoder.release();
        encoderSurface.release();
        muxer.stop(); muxer.release();
        extractor.release();
        glContext.release();
    }

    private static float clamp(float v, float min, float max) { return Math.max(min, Math.min(max, v)); }
}
