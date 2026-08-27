package com.example.smartcropapp.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Log;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

public class Pass1Extractor {
    private static final String TAG = "Pass1Extractor";
    private static final long INTERVAL_US = 500_000L; // sample tiap 500ms
    private static final String MODEL_ASSET = "face_landmarker.task";

    public static File extract(Context context, Uri sourceVideoUri, File outputFile) {
        FaceLandmarker faceLandmarker = null;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();

        try {
            Log.i(TAG, "Memulai Pass 1: MediaPipe FaceLandmarker (real detection)...");
            retriever.setDataSource(context, sourceVideoUri);

            long durationMs = 0;
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr != null) durationMs = Long.parseLong(durationStr);
            long durationUs = durationMs * 1000L;
            if (durationUs <= 0) durationUs = 10_000_000L; // fallback aman

            // --- Setup MediaPipe FaceLandmarker (mode IMAGE, sinkron per-frame) ---
            BaseOptions baseOptions = BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET)
                    .build();

            FaceLandmarker.FaceLandmarkerOptions options = FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumFaces(1) // B1: fokus 1 wajah dulu, multi-face di B2
                    .build();

            faceLandmarker = FaceLandmarker.createFromOptions(context, options);

            JSONArray facesArray = new JSONArray();
            long currentTimeUs = 0;
            int detectedCount = 0;
            int sampleCount = 0;

            while (currentTimeUs <= durationUs) {
                Bitmap frameBitmap = retriever.getFrameAtTime(currentTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                sampleCount++;

                if (frameBitmap != null) {
                    MPImage mpImage = new BitmapImageBuilder(frameBitmap).build();
                    FaceLandmarkerResult result = faceLandmarker.detect(mpImage);

                    if (result != null && !result.faceLandmarks().isEmpty()) {
                        List<NormalizedLandmark> landmarks = result.faceLandmarks().get(0);
                        float[] bbox = computeBoundingBox(landmarks);

                        JSONObject faceObj = new JSONObject();
                        faceObj.put("t", currentTimeUs / 1000);
                        faceObj.put("x", bbox[0]); // center X normalized 0..1
                        faceObj.put("y", bbox[1]); // center Y normalized 0..1
                        faceObj.put("size", bbox[2]); // perkiraan tinggi wajah normalized
                        facesArray.put(faceObj);
                        detectedCount++;
                    }
                    frameBitmap.recycle();
                }

                currentTimeUs += INTERVAL_US;
            }

            Log.i(TAG, "Deteksi wajah: " + detectedCount + " dari " + sampleCount + " sample.");

            if (facesArray.length() == 0) {
                Log.w(TAG, "TIDAK ADA wajah terdeteksi sama sekali -- fallback ke titik tengah statis.");
                JSONObject fallback = new JSONObject();
                fallback.put("t", 0);
                fallback.put("x", 0.5f);
                fallback.put("y", 0.4f);
                fallback.put("size", 0.3f);
                facesArray.put(fallback);
            }

            JSONObject root = new JSONObject();
            root.put("faces", facesArray);

            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                fos.write(root.toString().getBytes());
            }

            Log.i(TAG, "Pass 1 selesai: " + outputFile.getAbsolutePath());
            return outputFile;

        } catch (Exception e) {
            Log.e(TAG, "Gagal pada Pass 1", e);
            throw new RuntimeException("Pass 1 gagal: " + e.getMessage(), e);
        } finally {
            if (faceLandmarker != null) faceLandmarker.close();
            retriever.release();
        }
    }

    /**
     * Hitung bounding box sederhana dari seluruh landmark wajah.
     * Return: [centerX, centerY, heightNormalized]
     */
    private static float[] computeBoundingBox(List<NormalizedLandmark> landmarks) {
        float minX = 1f, maxX = 0f, minY = 1f, maxY = 0f;
        for (NormalizedLandmark lm : landmarks) {
            minX = Math.min(minX, lm.x());
            maxX = Math.max(maxX, lm.x());
            minY = Math.min(minY, lm.y());
            maxY = Math.max(maxY, lm.y());
        }
        float centerX = (minX + maxX) / 2f;
        float centerY = (minY + maxY) / 2f;
        float height = maxY - minY;
        return new float[]{centerX, centerY, height};
    }
}
