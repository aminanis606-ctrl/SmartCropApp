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
import com.example.smartcropapp.core.TrajectoryReader;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

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

    private static void renderVideoTrack(Context context, Uri sourceVideoUri, File outputVideoFile,
                                          TrajectoryReader trajectory) throws Exception {

        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(context, sourceVideoUri, null);

        int videoTrackIndex = selectTrack(extractor, "video/");
        if (videoTrackIndex < 0) throw new RuntimeException("Tidak ada video track di sumber");
        extractor.selectTrack(videoTrackIndex);
        MediaFormat inputFormat = extractor.getTrackFormat(videoTrackIndex);
        String inputMime = inputFormat.getString(MediaFormat.KEY_MIME);
        int srcWidth = inputFormat.containsKey(MediaFormat.KEY_WIDTH) ? inputFormat.getInteger(MediaFormat.KEY_WIDTH) : OUTPUT_WIDTH;
        int srcHeight = inputFormat.containsKey(MediaFormat.KEY_HEIGHT) ? inputFormat.getInteger(MediaFormat.KEY_HEIGHT) : OUTPUT_HEIGHT;

        MediaFormat encoderFormat = MediaFormat.createVideoFormat(OUTPUT_MIME, OUTPUT_WIDTH, OUTPUT_HEIGHT);
        encoderFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, OUTPUT_BITRATE);
        encoderFormat.setInteger(MediaFormat.KEY_FRAME_RATE, OUTPUT_FPS);
        encoderFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);

        MediaCodec encoder = MediaCodec.createEncoderByType(OUTPUT_MIME);
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface encoderInputSurface = encoder.createInputSurface();
        encoder.start();

        GlRenderContext glContext = new GlRenderContext();
        glContext.setupEncoderSurface(encoderInputSurface);
        CropShaderProgram shader = new CropShaderProgram();

        final AtomicBoolean frameAvailable = new AtomicBoolean(false);
        SurfaceTexture decoderSurfaceTexture = glContext.getDecoderSurfaceTexture();
        decoderSurfaceTexture.setOnFrameAvailableListener(st -> {
            synchronized (frameAvailable) {
                frameAvailable.set(true);
                frameAvailable.notifyAll();
            }
        });

        MediaCodec decoder = MediaCodec.createDecoderByType(inputMime);
        decoder.configure(inputFormat, glContext.getDecoderInputSurface(), null, 0);
        decoder.start();

        MediaMuxer muxer = new MediaMuxer(outputVideoFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int muxerVideoTrack = -1;
        boolean muxerStarted = false;

        MediaCodec.BufferInfo decoderInfo = new MediaCodec.BufferInfo();
        MediaCodec.BufferInfo encoderInfo = new MediaCodec.BufferInfo();

        boolean inputEOS = false;
        boolean decoderEOS = false;
        boolean encoderEOS = false;

        while (!encoderEOS) {
            if (!inputEOS) {
                int inIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
                if (inIndex >= 0) {
                    ByteBuffer inBuffer = decoder.getInputBuffer(inIndex);
                    int sampleSize = extractor.readSampleData(inBuffer, 0);
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputEOS = true;
                    } else {
                        long pts = extractor.getSampleTime();
                        decoder.queueInputBuffer(inIndex, 0, sampleSize, pts, 0);
                        extractor.advance();
                    }
                }
            }

            if (!decoderEOS) {
                int outIndex = decoder.dequeueOutputBuffer(decoderInfo, TIMEOUT_US);
                if (outIndex >= 0) {
                    boolean doRender = decoderInfo.size > 0;
                    decoder.releaseOutputBuffer(outIndex, doRender);
                    if (doRender) {
                        synchronized (frameAvailable) {
                            while (!frameAvailable.get()) {
                                frameAvailable.wait(1000);
                            }
                            frameAvailable.set(false);
                        }
                        decoderSurfaceTexture.updateTexImage();

                        float[] stMatrix = new float[16];
                        decoderSurfaceTexture.getTransformMatrix(stMatrix);

                        TrajectoryReader.Point p = trajectory.getPositionAt(decoderInfo.presentationTimeUs);

                        float cropHeightNorm = clamp(p.size * 2f, 0.15f, 1f);
                        float cropWidthNorm = clamp(
                                cropHeightNorm * (OUTPUT_WIDTH / (float) OUTPUT_HEIGHT) * (srcHeight / (float) srcWidth),
                                0.1f, 1f);

                        GLES20.glViewport(0, 0, OUTPUT_WIDTH, OUTPUT_HEIGHT);
                        shader.draw(glContext.getDecoderTextureId(), stMatrix, p.x, p.y, cropWidthNorm, cropHeightNorm);
                        glContext.setPresentationTime(decoderInfo.presentationTimeUs * 1000);
                        glContext.swapBuffers();
                    }
                    if ((decoderInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        decoderEOS = true;
                        encoder.signalEndOfInputStream();
                    }
                }
            }

            int encOutIndex = encoder.dequeueOutputBuffer(encoderInfo, TIMEOUT_US);
            if (encOutIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                muxerVideoTrack = muxer.addTrack(encoder.getOutputFormat());
                muxer.start();
                muxerStarted = true;
            } else if (encOutIndex >= 0) {
                ByteBuffer encodedData = encoder.getOutputBuffer(encOutIndex);
                if ((encoderInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    encoderInfo.size = 0;
                }
                if (encoderInfo.size > 0 && muxerStarted) {
                    encodedData.position(encoderInfo.offset);
                    encodedData.limit(encoderInfo.offset + encoderInfo.size);
                    muxer.writeSampleData(muxerVideoTrack, encodedData, encoderInfo);
                }
                encoder.releaseOutputBuffer(encOutIndex, false);
                if ((encoderInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    encoderEOS = true;
                }
            }
        }

        decoder.stop(); decoder.release();
        encoder.stop(); encoder.release();
        glContext.release();
        extractor.release();
        if (muxerStarted) muxer.stop();
        muxer.release();

        Log.i(TAG, "Pass 3 render selesai (VIDEO-ONLY): " + outputVideoFile.getAbsolutePath());
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int selectTrack(MediaExtractor extractor, String mimePrefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(mimePrefix)) return i;
        }
        return -1;
    }
}
