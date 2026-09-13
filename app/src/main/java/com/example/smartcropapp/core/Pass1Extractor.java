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

    private static final long INTERVAL_US = 50_000L;
    private static final long MLKIT_NORMAL_INTERVAL_MS = 200L;
    private static final long MLKIT_EDGE_INTERVAL_MS = 100L;
    private static final float MLKIT_EDGE_X = 0.82f;

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

    public static File extract(
            Context context,
            Uri sourceVideoUri,
            File outputFile) {

        MediaMetadataRetriever retriever =
                new MediaMetadataRetriever();

        long pass1StartNs = System.nanoTime();

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

            long analyzeTotalNs = 0L;
            long analyzeMinNs = Long.MAX_VALUE;
            long analyzeMaxNs = 0L;
            long mlKitTotalNs = 0L;
            long mlKitMinNs = Long.MAX_VALUE;
            long mlKitMaxNs = 0L;
            int perfFrames = 0;
            int mlKitCalls = 0;
            long lastMlKitTimeMs = -200L;
            List<SubjectDetector.Subject> lastSubjects =
                    new ArrayList<>();

            long getFrameTotalNs = 0L;
            long getFrameMinNs = Long.MAX_VALUE;
            long getFrameMaxNs = 0L;
            int getFrameCalls = 0;
            int getFrameSuccessCount = 0;
            int getFrameFailedCount = 0;
            long firstFailedTimeUs = -1L;
            long lastFailedTimeUs = -1L;
            long failedTimeMinUs = Long.MAX_VALUE;
            long failedTimeMaxUs = Long.MIN_VALUE;
            int failedLast10PctCount = 0;

            long jsonBuildStartNs = 0L;
            long jsonBuildTotalNs = 0L;

            for (
                    long timeUs = 0;
                    timeUs <= durationUs;
                    timeUs += INTERVAL_US) {

                long getFrameStartNs = System.nanoTime();
                Bitmap bitmap =
                        retriever.getFrameAtTime(
                                timeUs,
                                MediaMetadataRetriever.OPTION_CLOSEST);
                long getFrameElapsedNs = System.nanoTime() - getFrameStartNs;

                getFrameCalls++;
                getFrameTotalNs += getFrameElapsedNs;
                getFrameMinNs = Math.min(getFrameMinNs, getFrameElapsedNs);
                getFrameMaxNs = Math.max(getFrameMaxNs, getFrameElapsedNs);

                if (bitmap == null) {
                    getFrameFailedCount++;
                    if (firstFailedTimeUs < 0) firstFailedTimeUs = timeUs;
                    lastFailedTimeUs = timeUs;
                    failedTimeMinUs = Math.min(failedTimeMinUs, timeUs);
                    failedTimeMaxUs = Math.max(failedTimeMaxUs, timeUs);
                    if (timeUs >= (durationUs * 90L) / 100L) {
                        failedLast10PctCount++;
                    }
                    continue;
                }

                getFrameSuccessCount++;

                long analyzeStartNs = System.nanoTime();
                FrameFeature feature =
                        analyzeFrame(
                                bitmap,
                                timeUs / 1000L,
                                new ArrayList<SubjectDetector.Subject>());
                long analyzeElapsedNs =
                        System.nanoTime() - analyzeStartNs;
                analyzeTotalNs += analyzeElapsedNs;
                analyzeMinNs = Math.min(analyzeMinNs, analyzeElapsedNs);
                analyzeMaxNs = Math.max(analyzeMaxNs, analyzeElapsedNs);


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

                            jsonBuildStartNs = System.nanoTime();
                            shots.put(
                                    buildShot(
                                            shotId,
                                            current));
                            jsonBuildTotalNs += System.nanoTime() - jsonBuildStartNs;

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

                float mlKitIntervalMs =
                        MLKIT_NORMAL_INTERVAL_MS;

                if (!lastSubjects.isEmpty()) {
                    SubjectDetector.Subject lastSubject =
                            lastSubjects.get(0);

                    boolean nearHorizontalEdge =
                            lastSubject.x <= MLKIT_EDGE_X ||
                            lastSubject.x >= MLKIT_EDGE_X;

                    if (nearHorizontalEdge) {
                        mlKitIntervalMs =
                                MLKIT_EDGE_INTERVAL_MS;
                    }
                }

                boolean forceMlKit =
                        current.frames.size() <= 1 ||
                        feature.timeMs - lastMlKitTimeMs >= mlKitIntervalMs;

                if (previousBrightness != null) {
                    float frameDiff =
                            histogramDiff(
                                    previousBrightness,
                                    feature.brightness);
                    if (frameDiff >= WEAK_CUT_BRIGHTNESS) {
                        forceMlKit = true;
                    }
                }

                if (forceMlKit) {
                    long mlKitStartNs = System.nanoTime();
                    List<SubjectDetector.Subject> subjects =
                            SUBJECT_DETECTOR.detect(bitmap);
                    long mlKitElapsedNs =
                            System.nanoTime() - mlKitStartNs;
                    mlKitTotalNs += mlKitElapsedNs;
                    mlKitMinNs = Math.min(mlKitMinNs, mlKitElapsedNs);
                    mlKitMaxNs = Math.max(mlKitMaxNs, mlKitElapsedNs);
                    mlKitCalls++;
                    lastMlKitTimeMs = feature.timeMs;
                    lastSubjects = subjects;
                }

                perfFrames++;
                feature.subjects = lastSubjects;
                current.frames.add(feature);

                previousBrightness =
                        feature.brightness;

                previousTexture =
                        feature.texture;

                bitmap.recycle();
            }

            if (!current.frames.isEmpty()) {

                jsonBuildStartNs = System.nanoTime();
                shots.put(
                        buildShot(
                                shotId,
                                current));
                jsonBuildTotalNs += System.nanoTime() - jsonBuildStartNs;
            }

            JSONObject root =
                    new JSONObject();

            root.put(
                    "version",
                    3);

            root.put(
                    "shots",
                    shots);

            long jsonWriteStartNs = System.nanoTime();
            try (FileOutputStream fos =
                         new FileOutputStream(outputFile)) {

                fos.write(
                        root.toString(2)
                                .getBytes("UTF-8"));
            }
            long jsonWriteElapsedNs = System.nanoTime() - jsonWriteStartNs;

            if (!outputFile.exists()
                    || outputFile.length() == 0) {

                throw new Exception(
                        "analysis.json gagal ditulis.");
            }

            long pass1EndNs = System.nanoTime();
            long pass1TotalNs = pass1EndNs - pass1StartNs;

            // Write comprehensive diagnostics
            writeDiagnostics(
                    outputFile,
                    perfFrames,
                    mlKitCalls,
                    pass1TotalNs,
                    analyzeTotalNs,
                    analyzeMinNs,
                    analyzeMaxNs,
                    mlKitTotalNs,
                    mlKitMinNs,
                    mlKitMaxNs,
                    jsonBuildTotalNs,
                    jsonWriteElapsedNs,
                    getFrameCalls,
                    getFrameSuccessCount,
                    getFrameFailedCount,
                    getFrameTotalNs,
                    getFrameMinNs,
                    getFrameMaxNs,
                    durationUs,
                    firstFailedTimeUs,
                    lastFailedTimeUs,
                    failedTimeMinUs,
                    failedTimeMaxUs,
                    failedLast10PctCount);

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

    private static void writeDiagnostics(
            File outputFile,
            int perfFrames,
            int mlKitCalls,
            long pass1TotalNs,
            long analyzeTotalNs,
            long analyzeMinNs,
            long analyzeMaxNs,
            long mlKitTotalNs,
            long mlKitMinNs,
            long mlKitMaxNs,
            long jsonBuildTotalNs,
            long jsonWriteElapsedNs,
            int getFrameCalls,
            int getFrameSuccessCount,
            int getFrameFailedCount,
            long getFrameTotalNs,
            long getFrameMinNs,
            long getFrameMaxNs,
            long durationUs,
            long firstFailedTimeUs,
            long lastFailedTimeUs,
            long failedTimeMinUs,
            long failedTimeMaxUs,
            int failedLast10PctCount) {

        try {
            File diagDir = new File(
                    "/storage/emulated/0/Download/SmartReframe/diagnostics");
            if (!diagDir.exists()) {
                diagDir.mkdirs();
            }

            File diagFile = new File(diagDir, "pipeline_perf.txt");

            long pass1Ms = pass1TotalNs / 1_000_000L;
            long analyzeAvgMs = perfFrames == 0 ? 0 : (analyzeTotalNs / perfFrames) / 1_000_000L;
            long analyzeMinMs = analyzeMinNs == Long.MAX_VALUE ? 0 : analyzeMinNs / 1_000_000L;
            long analyzeMaxMs = analyzeMaxNs / 1_000_000L;
            long mlKitAvgMs = mlKitCalls == 0 ? 0 : (mlKitTotalNs / mlKitCalls) / 1_000_000L;
            long mlKitMinMs = mlKitMinNs == Long.MAX_VALUE ? 0 : mlKitMinNs / 1_000_000L;
            long mlKitMaxMs = mlKitMaxNs / 1_000_000L;
            long jsonBuildMs = jsonBuildTotalNs / 1_000_000L;
            long jsonWriteMs = jsonWriteElapsedNs / 1_000_000L;

            long getFrameAvgMs = getFrameCalls == 0 ? 0 : (getFrameTotalNs / getFrameCalls) / 1_000_000L;
            long getFrameMinMs = getFrameMinNs == Long.MAX_VALUE ? 0 : getFrameMinNs / 1_000_000L;
            long getFrameMaxMs = getFrameMaxNs / 1_000_000L;

            StringBuilder sb = new StringBuilder();
            sb.append("=== SMARTREFRAME PIPELINE PERFORMANCE DIAGNOSTICS ===\n");
            sb.append("\n[FRAME PROCESSING]\n");
            sb.append("total_frames=").append(perfFrames).append("\n");
            sb.append("\n[PASS1 TOTAL]\n");
            sb.append("pass1_total_ms=").append(pass1Ms).append("\n");
            sb.append("\n[FRAME RETRIEVAL]\n");
            sb.append("getframe_calls=").append(getFrameCalls).append("\n");
            sb.append("getframe_success=").append(getFrameSuccessCount).append("\n");
            sb.append("getframe_failed=").append(getFrameFailedCount).append("\n");
            sb.append("getframe_total_ms=").append(getFrameTotalNs / 1_000_000L).append("\n");
            sb.append("getframe_avg_ms=").append(getFrameAvgMs).append("\n");
            sb.append("getframe_min_ms=").append(getFrameMinMs).append("\n");
            sb.append("getframe_max_ms=").append(getFrameMaxMs).append("\n");
            sb.append("duration_us=").append(durationUs).append("\n");
            sb.append("first_failed_time_us=").append(firstFailedTimeUs).append("\n");
            sb.append("last_failed_time_us=").append(lastFailedTimeUs).append("\n");
            sb.append("failed_time_min_us=").append(
                    failedTimeMinUs == Long.MAX_VALUE ? -1L : failedTimeMinUs).append("\n");
            sb.append("failed_time_max_us=").append(
                    failedTimeMaxUs == Long.MIN_VALUE ? -1L : failedTimeMaxUs).append("\n");
            sb.append("failed_last_10pct_count=").append(failedLast10PctCount).append("\n");
            sb.append("\n[ANALYZEFRAME]\n");
            sb.append("analyze_total_ms=").append(analyzeTotalNs / 1_000_000L).append("\n");
            sb.append("analyze_avg_ms=").append(analyzeAvgMs).append("\n");
            sb.append("analyze_min_ms=").append(analyzeMinMs).append("\n");
            sb.append("analyze_max_ms=").append(analyzeMaxMs).append("\n");
            sb.append("\n[ML KIT DETECTION]\n");
            sb.append("mlkit_calls=").append(mlKitCalls).append("\n");
            sb.append("mlkit_total_ms=").append(mlKitTotalNs / 1_000_000L).append("\n");
            sb.append("mlkit_avg_ms=").append(mlKitAvgMs).append("\n");
            sb.append("mlkit_min_ms=").append(mlKitMinMs).append("\n");
            sb.append("mlkit_max_ms=").append(mlKitMaxMs).append("\n");
            sb.append("\n[JSON BUILD & WRITE]\n");
            sb.append("json_build_total_ms=").append(jsonBuildMs).append("\n");
            sb.append("json_write_ms=").append(jsonWriteMs).append("\n");
            sb.append("\n[BREAKDOWN %]\n");
            if (pass1Ms > 0) {
                sb.append("getframe_pct=").append((getFrameTotalNs * 100 / pass1TotalNs)).append("%\n");
                sb.append("analyze_pct=").append((analyzeTotalNs * 100 / pass1TotalNs)).append("%\n");
                sb.append("mlkit_pct=").append((mlKitTotalNs * 100 / pass1TotalNs)).append("%\n");
                sb.append("json_build_pct=").append((jsonBuildTotalNs * 100 / pass1TotalNs)).append("%\n");
                sb.append("json_write_pct=").append((jsonWriteElapsedNs * 100 / pass1TotalNs)).append("%\n");
            }
            sb.append("\n");

            try (FileOutputStream fos = new FileOutputStream(diagFile)) {
                fos.write(sb.toString().getBytes("UTF-8"));
            }

            Log.i(TAG, "Diagnostics written: " + diagFile.getAbsolutePath());

        } catch (Exception e) {
            Log.w(TAG, "Failed to write diagnostics (non-blocking)", e);
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

                    for (int x = startX; x < endX; x += PIXEL_STEP) {
                        int pixel = bitmap.getPixel(x, y);

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

                        for (int x = startX;
                             x < endX;
                             x += PIXEL_STEP) {

                            int pixel =
                                    bitmap.getPixel(
                                            x,
                                            y + PIXEL_STEP);

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
