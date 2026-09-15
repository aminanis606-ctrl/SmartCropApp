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
        List<SubjectDetector.Subject> subjects;

        FrameFeature(
                long timeMs,
                float[] brightness,
                float[] texture,
                float[] contrast,
                float[] edge,
                float[] verticalEdge,
                float[] horizontalEdge,
                float[] motion,
                List<SubjectDetector.Subject> subjects) {
            this.timeMs = timeMs;
            this.brightness = brightness;
            this.texture = texture;
            this.contrast = contrast;
            this.edge = edge;
            this.verticalEdge = verticalEdge;
            this.horizontalEdge = horizontalEdge;
            this.motion = motion;
            this.subjects = subjects;
        }
    }

    private static class ShotBuffer {
        long startMs;
        List<FrameFeature> frames = new ArrayList<>();
    }

    private static long refineCutTimeMs(
            MediaMetadataRetriever retriever,
            FrameFeature previous,
            FrameFeature current) {

        long startMs = previous.timeMs;
        long endMs = current.timeMs;

        if (endMs <= startMs) {
            return (startMs + endMs) / 2L;
        }

        FrameFeature left = previous;
        long bestCutMs = (startMs + endMs) / 2L;
        float bestScore = -1f;

        for (int i = 1; i <= 4; i++) {
            long rightMs =
                    startMs + ((endMs - startMs) * i) / 4L;

            Bitmap bitmap =
                    retriever.getFrameAtTime(
                            rightMs * 1000L,
                            MediaMetadataRetriever.OPTION_CLOSEST);

            if (bitmap == null) {
                continue;
            }

            FrameFeature right =
                    analyzeFrame(
                            bitmap,
                            rightMs,
                            new ArrayList<SubjectDetector.Subject>());

            float diff =
                    histogramDiff(
                            left.brightness,
                            right.brightness);

            float textureDiff =
                    histogramDiff(
                            left.texture,
                            right.texture);

            float score = diff + textureDiff;

            if (score > bestScore) {
                bestScore = score;
                bestCutMs =
                        (left.timeMs + right.timeMs) / 2L;
            }

            bitmap.recycle();
            left = right;
        }

        return bestCutMs;
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
                                timeUs / 1000L,
                                new ArrayList<SubjectDetector.Subject>());


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

                            FrameFeature previousFrame =
                                    current.frames.get(current.frames.size() - 1);

                            current =
                                    new ShotBuffer();

                            current.startMs =
                                    refineCutTimeMs(
                                            retriever,
                                            previousFrame,
                                            feature);
                        }

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
            long timeMs,
            List<SubjectDetector.Subject> subjects) {

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

        int sampleWidth =
                Math.max(1, (cellW + PIXEL_STEP - 1) / PIXEL_STEP);
        float[] currentGray = new float[sampleWidth];
        float[] nextGray = new float[sampleWidth];
        int[] pixelBuffer = new int[cellW];

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

                    int sampleCount = 0;

                    int rowWidth = endX - startX;
                    bitmap.getPixels(
                            pixelBuffer,
                            0,
                            rowWidth,
                            startX,
                            y,
                            rowWidth,
                            1);

                    for (int x = 0; x < rowWidth; x += PIXEL_STEP) {
                        int pixel = pixelBuffer[x];

                        int r = (pixel >> 16) & 0xff;
                        int g = (pixel >> 8) & 0xff;
                        int b = pixel & 0xff;

                        currentGray[sampleCount++] =
                                (r + g + b) / 765f;
                    }

                    boolean hasNextRow =
                            y + PIXEL_STEP < endY;

                    if (hasNextRow) {
                        int nextCount = 0;

                        bitmap.getPixels(
                                pixelBuffer,
                                0,
                                rowWidth,
                                startX,
                                y + PIXEL_STEP,
                                rowWidth,
                                1);

                        for (int x = 0; x < rowWidth; x += PIXEL_STEP) {
                            int pixel = pixelBuffer[x];

                            int r = (pixel >> 16) & 0xff;
                            int g = (pixel >> 8) & 0xff;
                            int b = pixel & 0xff;

                            nextGray[nextCount++] =
                                    (r + g + b) / 765f;
                        }
                    }

                    for (int i = 0; i < sampleCount; i++) {
                        float gray = currentGray[i];

                        sum += gray;
                        squareSum += gray * gray;

                        float dx = 0f;
                        float dy = 0f;

                        if (i + 1 < sampleCount) {
                            dx = Math.abs(
                                    gray - currentGray[i + 1]);
                            horizontalSum += dx;
                        }

                        if (hasNextRow) {
                            dy = Math.abs(
                                    gray - nextGray[i]);
                            verticalSum += dy;
                        }

                        textureSum += dx;
                        edgeSum += dx + dy;

                        count++;
                    }

                    float[] swap = currentGray;
                    currentGray = nextGray;
                    nextGray = swap;
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
                new float[brightness.length],
                subjects);
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
                classifyLayout(shot));

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

            JSONArray subjects =
                    new JSONArray();

            for (SubjectDetector.Subject subject :
                    frame.subjects) {

                JSONObject subjectJson =
                        new JSONObject();

                subjectJson.put(
                        "x",
                        (double) subject.x);

                subjectJson.put(
                        "y",
                        (double) subject.y);

                subjectJson.put(
                        "width",
                        (double) subject.width);

                subjectJson.put(
                        "height",
                        (double) subject.height);

                subjectJson.put(
                        "areaScore",
                        (double) subject.areaScore);

                subjectJson.put(
                        "trackingId",
                        subject.trackingId);

                subjects.put(subjectJson);
            }

            sample.put(
                    "subjects",
                    subjects);

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
