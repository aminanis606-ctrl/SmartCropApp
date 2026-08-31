package com.example.smartcropapp.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class Pass1Extractor {
    private static final String TAG = "Pass1Extractor";
    private static final long INTERVAL_US = 500_000L;
    private static final int GRID_X = 12;
    private static final int GRID_Y = 8;
    private static final int PIXEL_STEP = 4;
    private static final float CUT_THRESHOLD = 0.12f;

    private static class FrameFeature {
        long timeMs;
        float[] brightness;
        float[] texture;

        FrameFeature(long timeMs, float[] brightness, float[] texture) {
            this.timeMs = timeMs;
            this.brightness = brightness;
            this.texture = texture;
        }
    }

    private static class ShotBuffer {
        long startMs;
        List<FrameFeature> frames = new ArrayList<>();
    }

    public static File extract(Context context, Uri sourceVideoUri, File outputFile) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            Log.i(TAG, "PASS1 START: shot boundary + spatial layout analysis");
            
            // Coba setDataSource dengan Context untuk menangani content:// URI
            retriever.setDataSource(context, sourceVideoUri);

            long durationMs = 0;
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (duration != null) {
                durationMs = Long.parseLong(duration);
            }
            if (durationMs <= 0) durationMs = 10_000;

            long durationUs = durationMs * 1000L;
            JSONArray shots = new JSONArray();
            ShotBuffer current = new ShotBuffer();
            current.startMs = 0;
            float[] previousBrightness = null;
            int shotId = 0;

            for (long timeUs = 0; timeUs <= durationUs; timeUs += INTERVAL_US) {
                Bitmap bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
                if (bitmap == null) continue;

                FrameFeature feature = analyzeFrame(bitmap, timeUs / 1000L);
                
                if (previousBrightness != null) {
                    float diff = histogramDiff(previousBrightness, feature.brightness);
                    if (diff > CUT_THRESHOLD) {
                        if (!current.frames.isEmpty()) {
                            shots.put(buildShot(shotId, current));
                            shotId++;
                        }
                        current = new ShotBuffer();
                        current.startMs = feature.timeMs;
                        Log.i(TAG, "CUT t=" + feature.timeMs + " diff=" + diff);
                    }
                }
                current.frames.add(feature);
                previousBrightness = feature.brightness;
                bitmap.recycle();
            }

            if (!current.frames.isEmpty()) {
                shots.put(buildShot(shotId, current));
            }

            JSONObject root = new JSONObject();
            root.put("version", 2);
            root.put("shots", shots);

            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                fos.write(root.toString().getBytes("UTF-8"));
            }

            Log.i(TAG, "PASS1 DONE shots=" + shots.length());
            return outputFile;

        } catch (Exception e) {
            Log.e(TAG, "PASS1 FAILED", e);
            throw new RuntimeException("Pass 1 gagal: " + e.getMessage(), e);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static FrameFeature analyzeFrame(Bitmap bitmap, long timeMs) {
        float[] brightness = new float[GRID_X * GRID_Y];
        float[] texture = new float[GRID_X * GRID_Y];
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int cellW = Math.max(1, width / GRID_X);
        int cellH = Math.max(1, height / GRID_Y);

        for (int gy = 0; gy < GRID_Y; gy++) {
            for (int gx = 0; gx < GRID_X; gx++) {
                int startX = gx * cellW;
                int startY = gy * cellH;
                int endX = Math.min(width, startX + cellW);
                int endY = Math.min(height, startY + cellH);
                float sum = 0f;
                float textureSum = 0f;
                int count = 0;

                for (int y = startY; y < endY; y += PIXEL_STEP) {
                    for (int x = startX; x < endX; x += PIXEL_STEP) {
                        int pixel = bitmap.getPixel(x, y);
                        int r = (pixel >> 16) & 0xff;
                        int g = (pixel >> 8) & 0xff;
                        int b = pixel & 0xff;
                        float gray = (r + g + b) / 765f;
                        sum += gray;
                        
                        if (x + PIXEL_STEP < endX) {
                            int p2 = bitmap.getPixel(x + PIXEL_STEP, y);
                            int r2 = (p2 >> 16) & 0xff;
                            int g2 = (p2 >> 8) & 0xff;
                            int b2 = p2 & 0xff;
                            float gray2 = (r2 + g2 + b2) / 765f;
                            textureSum += Math.abs(gray - gray2);
                        }
                        count++;
                    }
                }
                int index = gy * GRID_X + gx;
                brightness[index] = count == 0 ? 0f : sum / count;
                texture[index] = count == 0 ? 0f : textureSum / count;
            }
        }
        return new FrameFeature(timeMs, brightness, texture);
    }

    private static float histogramDiff(float[] a, float[] b) {
        float sum = 0f;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            sum += Math.abs(a[i] - b[i]);
        }
        return n == 0 ? 0f : sum / n;
    }

    private static JSONObject buildShot(int shotId, ShotBuffer shot) throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("id", shotId);
        obj.put("startMs", shot.startMs);
        
        JSONArray features = new JSONArray();
        for (FrameFeature f : shot.frames) {
            JSONObject featObj = new JSONObject();
            featObj.put("timeMs", f.timeMs);
            
            JSONArray bArr = new JSONArray();
            for (float v : f.brightness) bArr.put(v);
            featObj.put("brightness", bArr);
            
            JSONArray tArr = new JSONArray();
            for (float v : f.texture) tArr.put(v);
            featObj.put("texture", tArr);
            
            features.put(featObj);
        }
        obj.put("features", features);
        return obj;
    }
}
