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
        int audioTrackIndex = -1;
        MediaFormat inputFormat = null;
        MediaFormat audioFormat = null;

        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith("video/")) {
                videoTrackIndex = i;
                inputFormat = format;
            } else if (mime.startsWith("audio/")) {
                audioTrackIndex = i;
                audioFormat = format;
            }
        }

        if (videoTrackIndex == -1) throw new RuntimeException("No video track");

        // Setup Decoder
        MediaCodec decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));
        decoder.configure(inputFormat, null, null, 0);
        SurfaceTexture decoderST = new SurfaceTexture(0);
        decoderST.setDefaultBufferSize(
            inputFormat.getInteger(MediaFormat.KEY_WIDTH), 
            inputFormat.getInteger(MediaFormat.KEY_HEIGHT)
        );
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

        // Use final arrays to allow modification from inner threads/classes
        final int[] muxerVideoTrackRef = {-1};
        final int[] muxerAudioTrackRef = {-1};
        final boolean[] muxerStartedRef = {false};

        boolean inputDone = false;
        boolean encoderDone = false;

        int srcWidth = inputFormat.getInteger(MediaFormat.KEY_WIDTH);
        int srcHeight = inputFormat.getInteger(MediaFormat.KEY_HEIGHT);
        float cropWidthNorm = clamp((float) OUTPUT_WIDTH / srcWidth * (srcHeight / (float) OUTPUT_HEIGHT), 0.1f, 1f);
        
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        // --- AUDIO COPY THREAD ---
        Thread audioThread = null;
        if (audioTrackIndex != -1 && audioFormat != null) {
            final Uri finalSourceUri = sourceVideoUri;
            final Context finalContext = context;
            final int finalAudioTrackIndex = audioTrackIndex;
            
            audioThread = new Thread(() -> {
                try {
                    MediaExtractor audioExtractor = new MediaExtractor();
                    audioExtractor.setDataSource(finalContext, finalSourceUri, null);
                    audioExtractor.selectTrack(finalAudioTrackIndex);
                    
                    MediaCodec.BufferInfo audioInfo = new MediaCodec.BufferInfo();
                    ByteBuffer audioBuf = ByteBuffer.allocate(1024 * 1024); 
                    
                    while (!muxerStartedRef[0]) { 
                        try { Thread.sleep(10); } catch (Exception e) {} 
                    }
                    
                    while (true) {
                        audioBuf.clear();
                        int sampleSize = audioExtractor.readSampleData(audioBuf, 0);
                        if (sampleSize < 0) break;
                        
                        long time = audioExtractor.getSampleTime();
                        int flags = audioExtractor.getSampleFlags();
                        
                        audioInfo.set(0, sampleSize, time, flags);
                        audioBuf.position(0);
                        audioBuf.limit(sampleSize);
                        
                        muxer.writeSampleData(muxerAudioTrackRef[0], audioBuf, audioInfo);
                        
                        if (!audioExtractor.advance()) break;
                    }
                    audioExtractor.release();
                } catch (Exception e) {
                    Log.e(TAG, "Audio copy error", e);
                }
            });
            audioThread.start();
        }
        // -------------------------

        extractor.selectTrack(videoTrackIndex);

        while (!encoderDone) {
            // 1. Feed Decoder
            if (!inputDone) {
                int inIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
                if (inIndex >= 0) {
                    ByteBuffer buf = decoder.getInputBuffer(inIndex);
                    int sampleSize = extractor.readSampleData(buf, 0);
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
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
                }
                
                // CRITICAL: Release to SurfaceTexture
                decoder.releaseOutputBuffer(outIndex, true);
                decoderST.updateTexImage();
                
                float[] stMatrix = new float[16];
                decoderST.getTransformMatrix(stMatrix);

                // === FULL SCREEN RENDERING (DEBUG MODE) ===
                GLES20.glClearColor(1.0f, 0.0f, 0.0f, 1.0f); // RED BACKGROUND
                GLES20.glViewport(0, 0, OUTPUT_WIDTH, OUTPUT_HEIGHT);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                
                // Draw the video texture
                shader.draw(glContext.getDecoderTextureId(), stMatrix, 0.5f, 0.5f, cropWidthNorm, 1.0f);

                glContext.setPresentationTime(info.presentationTimeUs * 1000);
                glContext.swapBuffers();

                if (isEos) {
                    encoder.signalEndOfInputStream();
                }
            }

            // 3. Drain Encoder
            int encIndex = encoder.dequeueOutputBuffer(info, TIMEOUT_US);
            if (encIndex >= 0) {
                if (!muxerStartedRef[0]) {
                    muxerVideoTrackRef[0] = muxer.addTrack(encoder.getOutputFormat());
                    if (audioTrackIndex != -1) {
                        muxerAudioTrackRef[0] = muxer.addTrack(audioFormat);
                    }
                    muxer.start();
                    muxerStartedRef[0] = true;
                    Log.d(TAG, "Muxer Started");
                }
                
                if (info.size > 0 && muxerStartedRef[0]) {
                    ByteBuffer encodedData = encoder.getOutputBuffer(encIndex);
                    encodedData.position(info.offset);
                    encodedData.limit(info.offset + info.size);
                    muxer.writeSampleData(muxerVideoTrackRef[0], encodedData, info);
                }
                
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    encoderDone = true;
                }
                encoder.releaseOutputBuffer(encIndex, false);
            }
        }

        if (audioThread != null) {
            audioThread.join();
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
