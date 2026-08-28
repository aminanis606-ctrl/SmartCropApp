package com.example.smartcropapp.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

public class Pass1Extractor {
    private static final String TAG = "Pass1Extractor";
    private static final long INTERVAL_US = 500_000L;
    private static final int HIST_GRID = 8;

    // Kalibrasi awal -- perlu disesuaikan berdasarkan log setelah tes nyata
    private static final float CUT_THRESHOLD = 0.35f;
    private static final float WINDOW_START = 0.2f;
    private static final float WINDOW_END = 0.8f;
    private static final float MID_FLAT_MAX = 0.02f;      // makin kecil = makin ketat (tengah harus makin "kosong")
    private static final float SIDE_TEXTURE_MIN = 0.01f;  // makin besar = makin ketat (kiri/kanan harus makin "ramai")

    private static class ShotBuffer {
        long startMs;
        List<Long> times = new ArrayList<>();
        List<float[]> histograms = new ArrayList<>();
    }

    public static File extract(Context context, Uri sourceVideoUri, File outputFile) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();

        try {
            Log.i(TAG, "Pass 1: shot-boundary + per-shot layout classification...");
            retriever.setDataSource(context, sourceVideoUri);

            long durationMs = 0;
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr != null) durationMs = Long.parseLong(durationStr);
            long durationUs = durationMs * 1000L;
            if (durationUs <= 0) durationUs = 10_000_000L;

            JSONArray shotsArray = new JSONArray();
            ShotBuffer currentShot = new ShotBuffer();
            currentShot.startMs = 0;
            float[] prevHistogram = null;
            int shotId = 0;

            long currentTimeUs = 0;
            while (currentTimeUs <= durationUs) {
                Bitmap rawBitmap = retriever.getFrameAtTime(currentTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);

                if (rawBitmap != null) {
                    float[] hist = computeGrayHistogram(rawBitmap);

                    if (prevHistogram != null) {
                        float diff = histogramDiff(prevHistogram, hist);
                        if (diff > CUT_THRESHOLD) {
                            shotsArray.put(classifyAndBuildShot(shotId, currentShot));
                            shotId++;
                            currentShot = new ShotBuffer();
                            currentShot.startMs = currentTimeUs / 1000;
                            Log.i(TAG, "Cut terdeteksi t=" + (currentTimeUs / 1000) + "ms diff=" + diff);
                        }
                    }
                    prevHistogram = hist;

                    currentShot.times.add(currentTimeUs / 1000);
                    currentShot.histograms.add(hist);

                    rawBitmap.recycle();
                }

                currentTimeUs += INTERVAL_US;
            }

            if (!currentShot.times.isEmpty()) {
                shotsArray.put(classifyAndBuildShot(shotId, currentShot));
            }

            JSONObject root = new JSONObject();
            root.put("shots", shotsArray);

            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                fos.write(root.toString().getBytes());
            }

            Log.i(TAG, "Pass 1 selesai, total shot: " + shotsArray.length());
            return outputFile;

        } catch (Exception e) {
            Log.e(TAG, "Gagal pada Pass 1", e);
            throw new RuntimeException("Pass 1 gagal: " + e.getMessage(), e);
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
    }

    private static JSONObject classifyAndBuildShot(int shotId, ShotBuffer shot) throws Exception {
        int n = shot.histograms.size();
        int startIdx = Math.max(0, (int) (n * WINDOW_START));
        int endIdx = Math.min(n - 1, (int) (n * WINDOW_END));
        if (endIdx < startIdx) { startIdx = 0; endIdx = n - 1; } // shot sangat pendek, pakai semua

        double sumLeft = 0, sumMid = 0, sumRight = 0;
        int count = 0;
        for (int i = startIdx; i <= endIdx; i++) {
            float[] h = shot.histograms.get(i);
            sumLeft += regionVariance(h, 0, 2);
            sumMid += regionVariance(h, 3, 4);
            sumRight += regionVariance(h, 5, 7);
            count++;
        }
        float avgLeft = count > 0 ? (float) (sumLeft / count) : 0f;
        float avgMid = count > 0 ? (float) (sumMid / count) : 0f;
        float avgRight = count > 0 ? (float) (sumRight / count) : 0f;

        boolean isSplit = avgMid < MID_FLAT_MAX && avgLeft > SIDE_TEXTURE_MIN && avgRight > SIDE_TEXTURE_MIN;

        Log.i(TAG, "Shot " + shotId + " klasifikasi: left=" + avgLeft + " mid=" + avgMid
                + " right=" + avgRight + " -> " + (isSplit ? "SPLIT" : "SINGLE"));

        JSONObject shotObj = new JSONObject();
        shotObj.put("shotId", shotId);
        shotObj.put("startMs", shot.startMs);
        shotObj.put("layout", isSplit ? "split" : "single");

        if (!isSplit) {
            // Mode single: sediakan samples dengan titik fokus (saat ini konstan, tanpa deteksi wajah asli)
            JSONArray samplesArray = new JSONArray();
            for (int i = 0; i < shot.times.size(); i++) {
                JSONObject sampleObj = new JSONObject();
                sampleObj.put("t", shot.times.get(i));
                JSONArray facesArray = new JSONArray();
                JSONObject faceObj = new JSONObject();
                faceObj.put("x", 0.5f);
                faceObj.put("y", 0.4f);
                faceObj.put("size", 0.3f);
                facesArray.put(faceObj);
                sampleObj.put("faces", facesArray);
                samplesArray.put(sampleObj);
            }
            shotObj.put("samples", samplesArray);
        }

        return shotObj;
    }

    /** Variance brightness antar sel dalam rentang kolom [colStart, colEnd] pada grid histogram. */
    private static float regionVariance(float[] hist, int colStart, int colEnd) {
        List<Float> values = new ArrayList<>();
        for (int row = 0; row < HIST_GRID; row++) {
            for (int col = colStart; col <= colEnd; col++) {
                values.add(hist[row * HIST_GRID + col]);
            }
        }
        float mean = 0f;
        for (float v : values) mean += v;
        mean /= values.size();

        float variance = 0f;
        for (float v : values) variance += (v - mean) * (v - mean);
        return variance / values.size();
    }

    private static float[] computeGrayHistogram(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        float[] hist = new float[HIST_GRID * HIST_GRID];
        int cellW = Math.max(1, w / HIST_GRID);
        int cellH = Math.max(1, h / HIST_GRID);

        for (int gy = 0; gy < HIST_GRID; gy++) {
            for (int gx = 0; gx < HIST_GRID; gx++) {
                long sum = 0;
                int count = 0;
                int startX = gx * cellW;
                int startY = gy * cellH;
                int endX = Math.min(w, startX + cellW);
                int endY = Math.min(h, startY + cellH);
                for (int y = startY; y < endY; y += 4) {
                    for (int x = startX; x < endX; x += 4) {
                        int pixel = bitmap.getPixel(x, y);
                        int r = (pixel >> 16) & 0xFF;
                        int g = (pixel >> 8) & 0xFF;
                        int b = pixel & 0xFF;
                        sum += (r + g + b) / 3;
                        count++;
                    }
                }
                hist[gy * HIST_GRID + gx] = count > 0 ? (sum / (float) count) / 255f : 0f;
            }
        }
        return hist;
    }

    private static float histogramDiff(float[] a, float[] b) {
        float sum = 0f;
        for (int i = 0; i < a.length; i++) sum += Math.abs(a[i] - b[i]);
        return sum / a.length;
    }
}
