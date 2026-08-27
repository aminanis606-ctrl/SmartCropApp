package com.smartreframe.core;

import android.media.MediaMetadataRetriever;
import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class Pass1Extractor {
    private static final String TAG = "Pass1Extractor";
    private static final long SAMPLE_INTERVAL_MS = 400; // Sampling tiap 400ms sesuai blueprint

    /**
     * Menjalankan ekstraksi Pass 1 pada video sumber dan menghasilkan objek JSON analisis.
     */
    public static JSONObject extract(String videoPath, File outputJsonFile) {
        JSONObject analysisResult = new JSONObject();
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();

        try {
            retriever.setDataSource(videoPath);
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durationMs = durationStr != null ? Long.parseLong(durationStr) : 0;

            JSONArray facesArray = new JSONArray();
            JSONArray cutsArray = new JSONArray();
            JSONArray audioEnergyArray = new JSONArray();

            Log.i(TAG, "Memulai Pass 1 ekstraksi untuk durasi: " + durationMs + " ms");

            // Loop sampling sepanjang durasi video dengan interval tertentu
            for (long timeMs = 0; timeMs < durationMs; timeMs += SAMPLE_INTERVAL_MS) {
                long timeUs = timeMs * 1000;

                // Placeholder data trajectory wajah per sample (nanti diisi output MediaPipe Face Landmarker + MAR)
                JSONObject faceSample = new JSONObject();
                faceSample.put("t", timeMs);
                faceSample.put("x", 0.5); // Default center normalized
                faceSample.put("y", 0.5);
                faceSample.put("size", 0.2);
                faceSample.put("mar", 0.0); // Mouth Aspect Ratio untuk Active Speaker Detection
                facesArray.put(faceSample);
            }

            analysisResult.put("faces", facesArray);
            analysisResult.put("cuts", cutsArray);
            analysisResult.put("audioEnergy", audioEnergyArray);
            analysisResult.put("dominantColor", "#2b2f38"); // Default warna latar sinematik

            // Simpan ke file analysis.json
            try (FileWriter fileWriter = new FileWriter(outputJsonFile)) {
                fileWriter.write(analysisResult.toString(4));
                fileWriter.flush();
            }

            Log.i(TAG, "Pass 1 selesai. Berhasil menulis ke: " + outputJsonFile.getAbsolutePath());

        } catch (Exception e) {
            Log.e(TAG, "Gagal menjalankan Pass 1 ekstraksi", e);
        } finally {
            try {
                retriever.release();
            } catch (IOException ignored) {}
        }

        return analysisResult;
    }
}
