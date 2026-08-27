package com.example.smartcropapp.core;

import android.content.Context;
import android.graphics.Bitmap;
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
    private static final long INTERVAL_US = 500_000L;
    private static final String MODEL_ASSET = "face_landmarker.task";
    private static final int MAX_FACES = 2;
    private static final int HIST_GRID = 8;
    private static final float CUT_THRESHOLD = 0.35f;

    public static File extract(Context context, Uri sourceVideoUri, File outputFile) {
        FaceLandmarker faceLandmarker = null;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();

        try {
            Log.i(TAG, "Pass 1: multi-face + shot-boundary detection...");
            retriever.setDataSource(context, sourceVideoUri);

            long durationMs = 0;
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr != null) durationMs = Long.parseLong(durationStr);
            long durationUs = durationMs * 1000L;
            if (durationUs <= 0) durationUs = 10_000_000L;

            BaseOptions baseOptions = BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build();
            FaceLandmarker.FaceLandmarkerOptions options = FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumFaces(MAX_FACES)
                    .build();
            faceLandmarker = FaceLandmarker.createFromOptions(context, options);

            JSONArray shotsArray = new JSONArray();
            JSONArray currentSamples = new JSONArray();
            int shotId = 0;
            long shotStartMs = 0;
            float[] prevHistogram = null;

            long currentTimeUs = 0;
            int sampleCount = 0;
            int detectedAnyCount = 0;

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
                        // --- Shot boundary check via spatial brightness grid diff ---
                        float[] hist = computeGrayHistogram(argbBitmap);
                        if (prevHistogram != null) {
                            float diff = histogramDiff(prevHistogram, hist);
                            if (diff > CUT_THRESHOLD) {
                                JSONObject shotObj = new JSONObject();
                                shotObj.put("shotId", shotId);
                                shotObj.put("startMs", shotStartMs);
                                shotObj.put("samples", currentSamples);
                                shotsArray.put(shotObj);

                                Log.i(TAG, "SHOT CUT -> shotId=" + shotId + " t=" + (currentTimeUs / 1000) + "ms diff=" + diff);

                                shotId++;
                                shotStartMs = currentTimeUs / 1000;
                                currentSamples = new JSONArray();
                            }
                        } else {
                            shotStartMs = currentTimeUs / 1000;
                        }
                        prevHistogram = hist;

                        // --- Face detection ---
                        MPImage mpImage = new BitmapImageBuilder(argbBitmap).build();
                        FaceLandmarkerResult result = faceLandmarker.detect(mpImage);

                        JSONArray facesArray = new JSONArray();
                        if (result != null && !result.faceLandmarks().isEmpty()) {
                            for (List<NormalizedLandmark> landmarks : result.faceLandmarks()) {
                                float[] bbox = computeBoundingBox(landmarks);
                                JSONObject faceObj = new JSONObject();
                                faceObj.put("x", bbox[0]);
                                faceObj.put("y", bbox[1]);
                                faceObj.put("size", bbox[2]);
                                facesArray.put(faceObj);
                            }
                            detectedAnyCount++;
                        }

                        JSONObject sampleObj = new JSONObject();
                        sampleObj.put("t", currentTimeUs / 1000);
                        sampleObj.put("faces", facesArray);
                        currentSamples.put(sampleObj);

                        argbBitmap.recycle();
                    }
                }

                currentTimeUs += INTERVAL_US;
            }

            JSONObject lastShot = new JSONObject();
            lastShot.put("shotId", shotId);
            lastShot.put("startMs", shotStartMs);
            lastShot.put("samples", currentSamples);
            shotsArray.put(lastShot);

            Log.i(TAG, "Total shot: " + (shotId + 1) + ", sample dgn wajah: " + detectedAnyCount + "/" + sampleCount);

            JSONObject root = new JSONObject();
            root.put("shots", shotsArray);

            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                fos.write(root.toString().getBytes());
            }

            Log.i(TAG, "Pass 1 selesai: " + outputFile.getAbsolutePath());
            return outputFile;

        } catch (Exception e) {
            Log.e(TAG, "Gagal pada Pass 1", e);
            throw new RuntimeException("Pass 1 gagal: " + e.getMessage(), e);
        } finally {
            if (faceLandmarker != null) {
                try { faceLandmarker.close(); } catch (Exception ignored) {}
            }
            try { retriever.release(); } catch (Exception ignored) {}
        }
    }

    /** Spatial grayscale brightness grid 8x8 untuk deteksi perubahan shot. */
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

    private static float[] computeBoundingBox(List<NormalizedLandmark> landmarks) {
        float minX = 1f, maxX = 0f, minY = 1f, maxY = 0f;
        for (NormalizedLandmark lm : landmarks) {
            minX = Math.min(minX, lm.x());
            maxX = Math.max(maxX, lm.x());
            minY = Math.min(minY, lm.y());
            maxY = Math.max(maxY, lm.y());
        }
        return new float[]{(minX + maxX) / 2f, (minY + maxY) / 2f, maxY - minY};
    }
}
