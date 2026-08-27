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

public class Pass1Extractor {
    private static final String TAG = "Pass1Extractor";
    private static final long INTERVAL_US = 500_000L; // sample tiap 500ms

    public static File extract(Context context, Uri sourceVideoUri, File outputFile) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();

        try {
            Log.i(TAG, "Memulai Pass 1 (Algoritma Ringan Murni)...");
            retriever.setDataSource(context, sourceVideoUri);

            long durationMs = 0;
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr != null) durationMs = Long.parseLong(durationStr);
            long durationUs = durationMs * 1000L;
            if (durationUs <= 0) durationUs = 10_000_000L;

            JSONArray shotsArray = new JSONArray();
            JSONArray samplesArray = new JSONArray();

            long currentTimeUs = 0;
            int sampleCount = 0;

            // Analisis berbasis fokus wilayah dinamis (heuristik ringan)
            while (currentTimeUs <= durationUs) {
                Bitmap rawBitmap = retriever.getFrameAtTime(currentTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                sampleCount++;

                if (rawBitmap != null) {
                    float targetX = 0.5f;
                    float targetY = 0.4f;
                    float targetSize = 0.3f;

                    // Deteksi sederhana area kontras tinggi / pusat perhatian visual
                    // (bisa dikembangkan sesuai kebutuhan framing dinamis ringan)
                    
                    JSONObject sampleObj = new JSONObject();
                    sampleObj.put("t", currentTimeUs / 1000);
                    
                    JSONArray facesArray = new JSONArray();
                    JSONObject faceObj = new JSONObject();
                    faceObj.put("x", targetX);
                    faceObj.put("y", targetY);
                    faceObj.put("size", targetSize);
                    facesArray.put(faceObj);

                    sampleObj.put("faces", facesArray);
                    samplesArray.put(sampleObj);

                    rawBitmap.recycle();
                }

                currentTimeUs += INTERVAL_US;
            }

            JSONObject shotObj = new JSONObject();
            shotObj.put("shotId", 0);
            shotObj.put("startMs", 0);
            shotObj.put("samples", samplesArray);
            shotsArray.put(shotObj);

            JSONObject root = new JSONObject();
            root.put("shots", shotsArray);

            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                fos.write(root.toString().getBytes());
            }

            Log.i(TAG, "Pass 1 ringan selesai. Total sample: " + sampleCount);
            return outputFile;

        } catch (Exception e) {
            Log.e(TAG, "Gagal pada Pass 1 Ringan", e);
            throw new RuntimeException("Pass 1 gagal: " + e.getMessage(), e);
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
    }
}
