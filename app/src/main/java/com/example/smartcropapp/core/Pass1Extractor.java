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
            Log.i(TAG, "Memulai Pass 1: Multi-Face Extraction (setNumFaces = 2)...");
            retriever.setDataSource(context, sourceVideoUri);

            long durationMs = 0;
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr != null) durationMs = Long.parseLong(durationStr);
            long durationUs = durationMs * 1000L;
            if (durationUs <= 0) durationUs = 10_000_000L; // fallback aman

            BaseOptions baseOptions = BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET)
                    .build();

            // Set numFaces ke 2 untuk menangkap wide shot (dua orang sisi kiri/kanan)
            FaceLandmarker.FaceLandmarkerOptions options = FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumFaces(2)
                    .build();

            faceLandmarker = FaceLandmarker.createFromOptions(context, options);

            JSONArray framesArray = new JSONArray();
            long currentTimeUs = 0;
            int sampleCount = 0;
            int multiFaceDetectedCount = 0;

            while (currentTimeUs <= durationUs) {
                Bitmap rawBitmap = retriever.getFrameAtTime(currentTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                sampleCount++;

                if (rawBitmap != null) {
                    Bitmap argbBitmap = rawBitmap;
                    if (rawBitmap.getConfig() != Bitmap.Config.ARGB_8888) {
                        argbBitmap = rawBitmap.copy(Bitmap.Config.ARGB_8888, true);
                        rawBitmap.recycle();
                    }

                    if (argbBitmap != null) {
                        MPImage mpImage = new BitmapImageBuilder(argbBitmap).build();
                        FaceLandmarkerResult result = faceLandmarker.detect(mpImage);

                        JSONObject frameObj = new JSONObject();
                        frameObj.put("t", currentTimeUs / 1000);

                        JSONArray candidatesArray = new JSONArray();

                        if (result != null && result.faceLandmarks() != null && !result.faceLandmarks().isEmpty()) {
                            if (result.faceLandmarks().size() > 1) {
                                multiFaceDetectedCount++;
                            }

                            // Loop semua wajah yang terdeteksi dalam frame ini (maksimal 2)
                            for (List<NormalizedLandmark> landmarks : result.faceLandmarks()) {
                                float[] bbox = computeBoundingBox(landmarks);
                                JSONObject faceObj = new JSONObject();
                                faceObj.put("x", bbox[0]);
                                faceObj.put("y", bbox[1]);
                                faceObj.put("size", bbox[2]);
                                candidatesArray.put(faceObj);
                            }
                        }

                        frameObj.put("candidates", candidatesArray);
                        framesArray.put(frameObj);
                        argbBitmap.recycle();
                    }
                }

                currentTimeUs += INTERVAL_US;
            }

            Log.i(TAG, "Pass 1 Selesai. Total sample: " + sampleCount + ", Frame dengan multi-wajah (>1): " + multiFaceDetectedCount);

            // Bungkus dalam root JSON terstruktur baru
            JSONObject root = new JSONObject();
            root.put("frames", framesArray);

            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                fos.write(root.toString().getBytes());
            }

            Log.i(TAG, "Trajectory multi-wajah tersimpan di: " + outputFile.getAbsolutePath());
            return outputFile;

        } catch (Exception e) {
            Log.e(TAG, "Gagal pada Pass 1 Multi-Face", e);
            throw new RuntimeException("Pass 1 gagal: " + e.getMessage(), e);
        } finally {
            if (faceLandmarker != null) {
                try { faceLandmarker.close(); } catch (Exception ignored) {}
            }
            try { retriever.release(); } catch (Exception ignored) {}
        }
    }

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
