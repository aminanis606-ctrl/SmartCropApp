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
    private static final SubjectDetector SUBJECT_DETECTOR = new SubjectDetector();

    private static final long INTERVAL_US = 250_000L;

    private static final int GRID_X = 12;
    private static final int GRID_Y = 8;
    private static final int PIXEL_STEP = 4;

    private static final float CUT_THRESHOLD = 0.12f;
    private static final float WEAK_CUT_BRIGHTNESS = 0.085f;
    private static final float WEAK_CUT_TEXTURE = 0.020f;

    private static class FrameFeature {
        long timeMs;
        float[] brightness;
        float[] texture;
        float[] contrast;
        float[] edge;
        float[] verticalEdge;
        float[] horizontalEdge;
        float[] motion;

        FrameFeature(
                long timeMs,
                float[] brightness,
                float[] texture,
                float[] contrast,
                float[] edge,
                float[] verticalEdge,
                float[] horizontalEdge,
                float[] motion) {
            this.timeMs = timeMs;
            this.brightness = brightness;
            this.texture = texture;
            this.contrast = contrast;
            this.edge = edge;
            this.verticalEdge = verticalEdge;
            this.horizontalEdge = horizontalEdge;
            this.motion = motion;
        }
    }

    private static class ShotBuffer {
        long startMs;
        List<FrameFeature> frames = new ArrayList<>();
    }

    public static File extract(
            Context context,
            Uri sourceVideoUri,
            File outputFile) {

        MediaMetadataRetriever retriever =
                new MediaMetadataRetriever();

        try {
            Log.i(
                    TAG,
                    "PASS1 START: shot boundary + spatial analysis");

            retriever.setDataSource(
                    context,
                    sourceVideoUri);

            long durationMs = 0;

            String duration =
                    retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_DURATION);

            if (duration != null) {
                durationMs =
                        Long.parseLong(duration);
            }

            if (durationMs <= 0) {
                throw new Exception(
                        "Durasi video tidak valid.");
            }

            long durationUs =
                    durationMs * 1000L;

            JSONArray shots =
                    new JSONArray();

            ShotBuffer current =
                    new ShotBuffer();

            current.startMs = 0;

            float[] previousBrightness = null;
            float[] previousTexture = null;

            int shotId = 0;

            for (
                    long timeUs = 0;
                    timeUs <= durationUs;
                    timeUs += INTERVAL_US) {

                Bitmap bitmap =
                        retriever.getFrameAtTime(
                                timeUs,
                                MediaMetadataRetriever.OPTION_CLOSEST);

                if (bitmap == null) {
                    continue;
                }

                FrameFeature feature =
                        analyzeFrame(
                                bitmap,
                                timeUs / 1000L);
                List<SubjectDetector.Subject> subjects =
                        SUBJECT_DETECTOR.detect(bitmap);

                Log.i(TAG, "SUBJECT_DIAG t=" + (timeUs / 1000L)
                        + " faces=" + subjects.size());

                // TEMPORAL MOTION: per-cell brightness change
                if (previousBrightness != null &&
                        previousBrightness.length == feature.brightness.length) {

                    for (int i = 0; i < feature.brightness.length; i++) {
                        feature.motion[i] =
                                Math.abs(
                                        feature.brightness[i] -
                                        previousBrightness[i]);
                    }
                }


                if (previousBrightness != null) {

                    float diff =
                            histogramDiff(
                                    previousBrightness,
                                    feature.brightness);

                    float textureDiff =
                            previousTexture == null
                                    ? 0f
                                    : histogramDiff(
                                            previousTexture,
                                            feature.texture);

                    boolean weakCut =
                            diff >= WEAK_CUT_BRIGHTNESS &&
                            textureDiff >= WEAK_CUT_TEXTURE;

                    Log.i(
                            TAG,
                            "CUT_DIAG t=" +
                            feature.timeMs +
                            " diff=" +
                            diff);

                    if (diff > CUT_THRESHOLD || weakCut) {

                        if (!current.frames.isEmpty()) {

                            shots.put(
                                    buildShot(
                                            shotId,
                                            current));

                            shotId++;
                        }

                        current =
                                new ShotBuffer();

                        current.startMs =
                                feature.timeMs;

                        // First frame after a shot cut has no valid temporal predecessor.
                        java.util.Arrays.fill(feature.motion, 0f);

                        Log.i(
                                TAG,
                                "CUT t=" +
                                feature.timeMs +
                                " diff=" +
                                diff);
                    }
                }

                current.frames.add(feature);

                previousBrightness =
                        feature.brightness;

                previousTexture =
                        feature.texture;

                bitmap.recycle();
            }

            if (!current.frames.isEmpty()) {

                shots.put(
                        buildShot(
                                shotId,
                                current));
            }

            JSONObject root =
                    new JSONObject();

            root.put(
                    "version",
                    3);

            root.put(
                    "shots",
                    shots);

            try (FileOutputStream fos =
                         new FileOutputStream(outputFile)) {

                fos.write(
                        root.toString(2)
                                .getBytes("UTF-8"));
            }

            if (!outputFile.exists()
                    || outputFile.length() == 0) {

                throw new Exception(
                        "analysis.json gagal ditulis.");
            }

            Log.i(
                    TAG,
                    "PASS1 DONE shots=" +
                    shots.length());

            return outputFile;

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "PASS1 FAILED",
                    e);

            throw new RuntimeException(
                    "Pass 1 gagal: " +
                    e.getMessage(),
                    e);

        } finally {

            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static FrameFeature analyzeFrame(
            Bitmap bitmap,
            long timeMs) {

        int cells = GRID_X * GRID_Y;

        float[] brightness = new float[cells];
        float[] texture = new float[cells];
        float[] contrast = new float[cells];
        float[] edge = new float[cells];
        float[] verticalEdge = new float[cells];
        float[] horizontalEdge = new float[cells];

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
                float squareSum = 0f;
                float textureSum = 0f;
                float edgeSum = 0f;
                float verticalSum = 0f;
                float horizontalSum = 0f;
                int count = 0;

                for (int y = startY; y < endY; y += PIXEL_STEP) {
                    for (int x = startX; x < endX; x += PIXEL_STEP) {

                        int pixel = bitmap.getPixel(x, y);

                        int r = (pixel >> 16) & 0xff;
                        int g = (pixel >> 8) & 0xff;
                        int b = pixel & 0xff;

                        float gray = (r + g + b) / 765f;

                        sum += gray;
                        squareSum += gray * gray;

                        float dx = 0f;
                        float dy = 0f;

                        if (x + PIXEL_STEP < endX) {
                            int p2 = bitmap.getPixel(
                                    x + PIXEL_STEP,
                                    y);

                            int r2 = (p2 >> 16) & 0xff;
                            int g2 = (p2 >> 8) & 0xff;
                            int b2 = p2 & 0xff;

                            float gray2 =
                                    (r2 + g2 + b2) / 765f;

                            dx = Math.abs(gray - gray2);
                            horizontalSum += dx;
                        }

                        if (y + PIXEL_STEP < endY) {
                            int p3 = bitmap.getPixel(
                                    x,
                                    y + PIXEL_STEP);

                            int r3 = (p3 >> 16) & 0xff;
                            int g3 = (p3 >> 8) & 0xff;
                            int b3 = p3 & 0xff;

                            float gray3 =
                                    (r3 + g3 + b3) / 765f;

                            dy = Math.abs(gray - gray3);
                            verticalSum += dy;
                        }

                        textureSum += dx;
                        edgeSum += dx + dy;

                        count++;
                    }
                }

                int index = gy * GRID_X + gx;

                if (count == 0) {
                    brightness[index] = 0f;
                    texture[index] = 0f;
                    contrast[index] = 0f;
                    edge[index] = 0f;
                    verticalEdge[index] = 0f;
                    horizontalEdge[index] = 0f;
                } else {
                    float mean = sum / count;
                    float variance =
                            Math.max(
                                    0f,
                                    (squareSum / count)
                                            - (mean * mean));

                    brightness[index] = mean;
                    texture[index] = textureSum / count;
                    contrast[index] = (float) Math.sqrt(variance);
                    edge[index] = edgeSum / count;
                    verticalEdge[index] = verticalSum / count;
                    horizontalEdge[index] = horizontalSum / count;
                }
            }
        }

        return new FrameFeature(
                timeMs,
                brightness,
                texture,
                contrast,
                edge,
                verticalEdge,
                horizontalEdge,
                new float[brightness.length]);
    }

    private static float histogramDiff(
            float[] a,
            float[] b) {

        float sum = 0f;

        int n =
                Math.min(
                        a.length,
                        b.length);

        for (int i = 0;
             i < n;
             i++) {

            sum +=
                    Math.abs(
                            a[i] - b[i]);
        }

        return n == 0
                ? 0f
                : sum / n;
    }

    private static JSONObject buildShot(
            int shotId,
            ShotBuffer shot)
            throws Exception {

        JSONObject result =
                new JSONObject();

        result.put(
                "shotId",
                shotId);

        result.put(
                "startMs",
                shot.startMs);

        result.put(
                "layout",
                "single");

        JSONArray samples =
                new JSONArray();

        for (FrameFeature frame :
                shot.frames) {

            JSONObject sample =
                    new JSONObject();

            sample.put(
                    "t",
                    frame.timeMs);

            JSONArray brightness =
                    new JSONArray();

            for (float value :
                    frame.brightness) {

                brightness.put(
                        (double) value);
            }

            JSONArray texture =
                    new JSONArray();

            for (float value :
                    frame.texture) {

                texture.put(
                        (double) value);
            }

            sample.put(
                    "brightness",
                    brightness);

            sample.put(
                    "texture",
                    texture);

            JSONArray contrast =
                    new JSONArray();

            for (float value :
                    frame.contrast) {
                contrast.put((double) value);
            }

            JSONArray edge =
                    new JSONArray();

            for (float value :
                    frame.edge) {
                edge.put((double) value);
            }

            JSONArray verticalEdge =
                    new JSONArray();

            for (float value :
                    frame.verticalEdge) {
                verticalEdge.put((double) value);
            }

            JSONArray horizontalEdge =
                    new JSONArray();

            for (float value :
                    frame.horizontalEdge) {
                horizontalEdge.put((double) value);
            }

            sample.put(
                    "contrast",
                    contrast);

            sample.put(
                    "edge",
                    edge);

            sample.put(
                    "verticalEdge",
                    verticalEdge);

            sample.put(
                    "horizontalEdge",
                    horizontalEdge);

            JSONArray motion = new JSONArray();

            for (float value :
                    frame.motion) {
                motion.put((double) value);
            }

            sample.put(
                    "motion",
                    motion);

            samples.put(sample);
        }

        result.put(
                "samples",
                samples);

        return result;
    }

    private static float averageRegion(
            List<FrameFeature> frames,
            boolean useTexture,
            int startX,
            int endX) {

        if (frames.isEmpty()) {
            return 0f;
        }

        double total = 0.0;
        int count = 0;

        for (FrameFeature frame : frames) {

            float[] data =
                    useTexture
                            ? frame.texture
                            : frame.brightness;

            for (int y = 0;
                 y < GRID_Y;
                 y++) {

                for (int x = startX;
                     x < endX;
                     x++) {

                    total +=
                            data[y * GRID_X + x];

                    count++;
                }
            }
        }

        return count == 0
                ? 0f
                : (float) (total / count);
    }

    private static float spatialContrast(
            List<FrameFeature> frames,
            int startX,
            int endX) {

        if (frames.isEmpty()) {
            return 0f;
        }

        double total = 0.0;
        int count = 0;

        for (FrameFeature frame : frames) {

            float[] texture =
                    frame.texture;

            float[] brightness =
                    frame.brightness;

            float tex = 0f;
            float bright = 0f;
            int cells = 0;

            for (int y = 1;
                 y < GRID_Y - 1;
                 y++) {

                for (int x = startX;
                     x < endX;
                     x++) {

                    int index =
                            y * GRID_X + x;

                    tex += texture[index];
                    bright += brightness[index];

                    cells++;
                }
            }

            if (cells > 0) {

                float localTexture =
                        tex / cells;

                float localBrightness =
                        bright / cells;

                float score =
                        localTexture * 0.75f +
                        localBrightness * 0.25f;

                total += score;
                count++;
            }
        }

        return count == 0
                ? 0f
                : (float) (total / count);
    }

    private static float temporalConsistency(
            List<FrameFeature> frames,
            int startX,
            int endX) {

        if (frames.size() < 2) {
            return 0f;
        }

        float previous = 0f;
        boolean first = true;

        double stability = 0.0;
        int count = 0;

        for (FrameFeature frame : frames) {

            float current =
                    averageFrameRegion(
                            frame,
                            startX,
                            endX);

            if (!first) {

                float diff =
                        Math.abs(
                                current -
                                previous);

                float stable =
                        1f -
                        Math.min(
                                1f,
                                diff * 5f);

                stability += stable;
                count++;
            }

            previous = current;
            first = false;
        }

        return count == 0
                ? 0f
                : (float) (stability / count);
    }

    private static float averageFrameRegion(
            FrameFeature frame,
            int startX,
            int endX) {

        float total = 0f;
        int count = 0;

        for (int y = 1;
             y < GRID_Y - 1;
             y++) {

            for (int x = startX;
                 x < endX;
                 x++) {

                total +=
                        frame.texture[
                                y * GRID_X + x];

                count++;
            }
        }

        return count == 0
                ? 0f
                : total / count;
    }


    private static String classifyLayout(
            ShotBuffer shot) {

        /*
         * PASS 1 TIDAK MENENTUKAN SPLIT.
         *
         * Tugas Pass 1:
         * 1. mendeteksi batas shot
         * 2. menyimpan shotId
         * 3. menyimpan startMs
         * 4. menyimpan data spasial brightness/texture
         *
         * Keputusan layout single/split dilakukan
         * oleh Pass 2 berdasarkan data Pass 1 dan
         * konfigurasi manual_split.txt.
         */

        Log.i(
                TAG,
                "Shot " +
                shot.startMs +
                " -> layout candidate: single");

        return "single";
    }
}
