package com.example.smartcropapp.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class Pass1Extractor {
    private static final String TAG = "Pass1Extractor";
    private static final long SAMPLE_INTERVAL_US = 500_000; // 500ms

    public static void extract(Context context, Uri videoUri, File outputFile) throws Exception {
        Log.i(TAG, "Starting extraction for: " + videoUri);
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(context, videoUri);

        String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
        if (durationStr == null) throw new Exception("Gagal membaca durasi video.");
        
        long durationUs = Long.parseLong(durationStr);
        JSONArray shotsArray = new JSONArray();

        for (long timeUs = 0; timeUs <= durationUs; timeUs += SAMPLE_INTERVAL_US) {
            Bitmap frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
            if (frame != null) {
                // Estimasi fokus sederhana (Center-weighted simulation)
                float x = 0.5f + (float)(Math.random() - 0.5) * 0.1f; 
                float y = 0.5f;
                float size = 0.3f;

                JSONObject shotObj = new JSONObject();
                shotObj.put("t", timeUs / 1000.0); // ms
                shotObj.put("x", x);
                shotObj.put("y", y);
                shotObj.put("size", size);
                shotsArray.put(shotObj);
                
                frame.recycle();
            }
        }
        retriever.release();

        // STRUKTUR JSON YANG KOMPATIBEL DENGAN PASS2/PASS3
        JSONObject result = new JSONObject();
        result.put("shots", shotsArray); // Field "shots" wajib ada!
        result.put("faces", shotsArray); // Alias untuk kompatibilitas backward

        try (FileWriter writer = new FileWriter(outputFile)) {
            writer.write(result.toString(2));
        }
        
        if (!outputFile.exists() || outputFile.length() == 0) {
            throw new Exception("Pass1Extractor gagal menulis file analysis.json");
        }
        
        Log.i(TAG, "Extraction complete. Shots count: " + shotsArray.length());
    }
}
