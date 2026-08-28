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

    /*
     * Sampling 500 ms:
     * cukup ringan untuk analisis shot-level.
     */
    private static final long INTERVAL_US = 500_000L;

    /*
     * Grid 8x8:
     * cukup murah tetapi masih memberi informasi spasial.
     */
    private static final int GRID = 8;

    /*
     * Threshold shot boundary.
     * Akan kita pertahankan sebagai baseline.
     */
    private static final float CUT_THRESHOLD = 0.35f;

    /*
     * Hanya window tengah shot yang digunakan
     * untuk menentukan layout.
     */
    private static final float WINDOW_START = 0.20f;
    private static final float WINDOW_END = 0.80f;

    /*
     * Threshold awal untuk classifier.
     *
     * Ini sengaja konservatif.
     * Jangan langsung dianggap final sebelum melihat Logcat.
     */
    private static final float SIDE_TEXTURE_MIN = 0.010f;
    private static final float CENTER_TEXTURE_MAX = 0.018f;

    /*
     * Selisih minimum aktivitas kiri/kanan
     * terhadap area tengah.
     */
    private static final float SIDE_MARGIN = 0.004f;

    /*
     * Minimal proporsi frame yang harus mendukung
     * dugaan split.
     */
    private static final float SPLIT_VOTE_MIN = 0.60f;

    /*
     * Jika confidence terlalu rendah, lebih aman
     * memilih SINGLE daripada salah SPLIT.
     */
    private static final float CONFIDENCE_MIN = 0.55f;

    private static class ShotBuffer {
        long startMs;
        List<Long> times = new ArrayList<>();
        List<float[]> brightness = new ArrayList<>();
        List<float[]> texture = new ArrayList<>();
    }

    public static File extract(
            Context context,
            Uri sourceVideoUri,
            File outputFile) {

        MediaMetadataRetriever retriever =
                new MediaMetadataRetriever();

        try {
            Log.i(TAG,
                    "Pass 1: shot-boundary + spatial layout analysis");

            retriever.setDataSource(
                    context,
                    sourceVideoUri);

            long durationMs = 0;

            String durationStr =
                    retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_DURATION);

            if (durationStr != null) {
                durationMs = Long.parseLong(durationStr);
            }

            long durationUs = durationMs * 1000L;

            if (durationUs <= 0) {
                durationUs = 10_000_000L;
            }

            JSONArray shotsArray = new JSONArray();

            ShotBuffer currentShot = new ShotBuffer();
            currentShot.startMs = 0;

            float[] previousBrightness = null;

            int shotId = 0;

            long currentTimeUs = 0;

            while (currentTimeUs <= durationUs) {

                Bitmap bitmap = retriever.getFrameAtTime(
                        currentTimeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC);

                if (bitmap != null) {

                    float[] brightness =
                            computeBrightnessGrid(bitmap);

                    float[] texture =
                            computeTextureGrid(bitmap);

                    if (previousBrightness != null) {

                        float diff =
                                histogramDiff(
                                        previousBrightness,
                                        brightness);

                        if (diff > CUT_THRESHOLD) {

                            if (!currentShot.times.isEmpty()) {

                                shotsArray.put(
                                        classifyAndBuildShot(
                                                shotId,
                                                currentShot));
                            }

                            shotId++;

                            currentShot =
                                    new ShotBuffer();

                            currentShot.startMs =
                                    currentTimeUs / 1000L;

                            Log.i(
                                    TAG,
                                    "CUT @ "
                                            + (currentTimeUs / 1000L)
                                            + "ms diff="
                                            + diff);
                        }
                    }

                    previousBrightness =
                            brightness;

                    currentShot.times.add(
                            currentTimeUs / 1000L);

                    currentShot.brightness.add(
                            brightness);

                    currentShot.texture.add(
                            texture);

                    bitmap.recycle();
                }

                currentTimeUs += INTERVAL_US;
            }

            if (!currentShot.times.isEmpty()) {

                shotsArray.put(
                        classifyAndBuildShot(
                                shotId,
                                currentShot));
            }

            JSONObject root =
                    new JSONObject();

            root.put(
                    "shots",
                    shotsArray);

            try (FileOutputStream fos =
                         new FileOutputStream(outputFile)) {

                fos.write(
                        root.toString()
                                .getBytes("UTF-8"));
            }

            Log.i(
                    TAG,
                    "Pass 1 selesai. shots="
                            + shotsArray.length());

            return outputFile;

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Pass 1 gagal",
                    e);

            throw new RuntimeException(
                    "Pass 1 gagal: "
                            + e.getMessage(),
                    e);

        } finally {

            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static JSONObject classifyAndBuildShot(
            int shotId,
            ShotBuffer shot) throws Exception {

        int n = shot.times.size();

        if (n == 0) {
            throw new IllegalArgumentException(
                    "Shot kosong");
        }

        int startIdx =
                Math.max(
                        0,
                        (int) Math.floor(
                                n * WINDOW_START));

        int endIdx =
                Math.min(
                        n - 1,
                        (int) Math.ceil(
                                n * WINDOW_END));

        if (endIdx < startIdx) {
            startIdx = 0;
            endIdx = n - 1;
        }

        int votes = 0;
        int samples = 0;

        double sumLeft = 0;
        double sumCenter = 0;
        double sumRight = 0;

        double sumCenterSuppression = 0;

        for (int i = startIdx;
             i <= endIdx;
             i++) {

            float[] texture =
                    shot.texture.get(i);

            float left =
                    regionMean(
                            texture,
                            0,
                            2);

            float center =
                    regionMean(
                            texture,
                            3,
                            4);

            float right =
                    regionMean(
                            texture,
                            5,
                            7);

            float sideAverage =
                    (left + right) * 0.5f;

            boolean frameSplit =
                    left >= SIDE_TEXTURE_MIN
                    && right >= SIDE_TEXTURE_MIN
                    && center <= CENTER_TEXTURE_MAX
                    && left > center + SIDE_MARGIN
                    && right > center + SIDE_MARGIN;

            if (frameSplit) {
                votes++;
            }

            sumLeft += left;
            sumCenter += center;
            sumRight += right;

            sumCenterSuppression +=
                    Math.max(
                            0f,
                            sideAverage - center);

            samples++;
        }

        float avgLeft =
                samples > 0
                        ? (float) (sumLeft / samples)
                        : 0f;

        float avgCenter =
                samples > 0
                        ? (float) (sumCenter / samples)
                        : 0f;

        float avgRight =
                samples > 0
                        ? (float) (sumRight / samples)
                        : 0f;

        float voteRatio =
                samples > 0
                        ? (float) votes / samples
                        : 0f;

        float suppression =
                samples > 0
                        ? (float)
                        (sumCenterSuppression / samples)
                        : 0f;

        /*
         * Confidence bukan AI confidence.
         *
         * Ini hanya skor deterministik:
         * - voteRatio
         * - kekuatan kedua sisi
         * - seberapa rendah center dibanding side
         */
        float sideStrength =
                Math.min(
                        avgLeft,
                        avgRight);

        float centerGap =
                Math.max(
                        0f,
                        ((avgLeft + avgRight) * 0.5f)
                                - avgCenter);

        float sideScore =
                clamp01(
                        sideStrength
                                / Math.max(
                                SIDE_TEXTURE_MIN,
                                0.0001f));

        float gapScore =
                clamp01(
                        centerGap
                                / Math.max(
                                SIDE_MARGIN * 4f,
                                0.0001f));

        float confidence =
                0.50f * voteRatio
                        + 0.30f * sideScore
                        + 0.20f * gapScore;

        boolean isSplit =
                voteRatio >= SPLIT_VOTE_MIN
                        && confidence >= CONFIDENCE_MIN;

        Log.i(
                TAG,
                "Shot " + shotId
                        + " layout="
                        + (isSplit
                        ? "SPLIT"
                        : "SINGLE")
                        + " left="
                        + avgLeft
                        + " center="
                        + avgCenter
                        + " right="
                        + avgRight
                        + " vote="
                        + voteRatio
                        + " suppression="
                        + suppression
                        + " confidence="
                        + confidence);

        JSONObject shotObj =
                new JSONObject();

        shotObj.put(
                "shotId",
                shotId);

        shotObj.put(
                "startMs",
                shot.startMs);

        shotObj.put(
                "layout",
                isSplit
                        ? "split"
                        : "single");

        shotObj.put(
                "confidence",
                confidence);

        shotObj.put(
                "voteRatio",
                voteRatio);

        /*
         * Simpan metrik diagnosis.
         * Sangat berguna saat kalibrasi Logcat.
         */
        shotObj.put(
                "leftTexture",
                avgLeft);

        shotObj.put(
                "centerTexture",
                avgCenter);

        shotObj.put(
                "rightTexture",
                avgRight);

        if (!isSplit) {

            JSONArray samplesArray =
                    buildSingleSamples(shot);

            shotObj.put(
                    "samples",
                    samplesArray);
        }

        return shotObj;
    }

    private static JSONArray buildSingleSamples(
            ShotBuffer shot) throws Exception {

        JSONArray samples =
                new JSONArray();

        for (int i = 0;
             i < shot.times.size();
             i++) {

            JSONObject sample =
                    new JSONObject();

            sample.put(
                    "t",
                    shot.times.get(i));

            /*
             * Placeholder focus tetap.
             *
             * Ini sengaja dipertahankan agar
             * Target Lock + EMA Pass2 tetap
             * kompatibel.
             */
            JSONArray faces =
                    new JSONArray();

            JSONObject focus =
                    new JSONObject();

            focus.put("x", 0.5f);
            focus.put("y", 0.4f);
            focus.put("size", 0.3f);

            faces.put(focus);

            sample.put(
                    "faces",
                    faces);

            samples.put(sample);
        }

        return samples;
    }

    private static float regionMean(
            float[] grid,
            int colStart,
            int colEnd) {

        float sum = 0f;
        int count = 0;

        for (int row = 0;
             row < GRID;
             row++) {

            for (int col = colStart;
                 col <= colEnd;
                 col++) {

                sum +=
                        grid[row * GRID + col];

                count++;
            }
        }

        return count > 0
                ? sum / count
                : 0f;
    }

    private static float clamp01(float value) {

        return Math.max(
                0f,
                Math.min(
                        1f,
                        value));
    }

    private static float[] computeBrightnessGrid(
            Bitmap bitmap) {

        int width =
                bitmap.getWidth();

        int height =
                bitmap.getHeight();

        float[] grid =
                new float[GRID * GRID];

        int cellW =
                Math.max(
                        1,
                        width / GRID);

        int cellH =
                Math.max(
                        1,
                        height / GRID);

        for (int gy = 0;
             gy < GRID;
             gy++) {

            for (int gx = 0;
                 gx < GRID;
                 gx++) {

                long sum = 0;
                int count = 0;

                int startX =
                        gx * cellW;

                int startY =
                        gy * cellH;

                int endX =
                        Math.min(
                                width,
                                startX + cellW);

                int endY =
                        Math.min(
                                height,
                                startY + cellH);

                for (int y = startY;
                     y < endY;
                     y += 4) {

                    for (int x = startX;
                         x < endX;
                         x += 4) {

                        int pixel =
                                bitmap.getPixel(
                                        x,
                                        y);

                        int r =
                                (pixel >> 16) & 0xFF;

                        int g =
                                (pixel >> 8) & 0xFF;

                        int b =
                                pixel & 0xFF;

                        sum +=
                                (r + g + b) / 3;

                        count++;
                    }
                }

                grid[
                        gy * GRID + gx
                        ] =
                        count > 0
                                ? (sum / (float) count)
                                / 255f
                                : 0f;
            }
        }

        return grid;
    }

    private static float[] computeTextureGrid(
            Bitmap bitmap) {

        int width =
                bitmap.getWidth();

        int height =
                bitmap.getHeight();

        float[] result =
                new float[GRID * GRID];

        int cellW =
                Math.max(
                        1,
                        width / GRID);

        int cellH =
                Math.max(
                        1,
                        height / GRID);

        for (int gy = 0;
             gy < GRID;
             gy++) {

            for (int gx = 0;
                 gx < GRID;
                 gx++) {

                long energy = 0;
                int count = 0;

                int startX =
                        gx * cellW;

                int startY =
                        gy * cellH;

                int endX =
                        Math.min(
                                width - 1,
                                startX + cellW);

                int endY =
                        Math.min(
                                height - 1,
                                startY + cellH);

                /*
                 * Sampling kasar.
                 *
                 * Kita bandingkan pixel dengan
                 * tetangga kanan dan bawah.
                 */
                for (int y = startY;
                     y < endY - 1;
                     y += 4) {

                    for (int x = startX;
                         x < endX - 1;
                         x += 4) {

                        int p =
                                gray(
                                        bitmap.getPixel(
                                                x,
                                                y));

                        int px =
                                gray(
                                        bitmap.getPixel(
                                                x + 2,
                                                y));

                        int py =
                                gray(
                                        bitmap.getPixel(
                                                x,
                                                y + 2));

                        energy +=
                                Math.abs(p - px)
                                        + Math.abs(p - py);

                        count++;
                    }
                }

                /*
                 * Normalisasi 0..1.
                 *
                 * 255 + 255 adalah maksimum
                 * kasar dari dua gradient.
                 */
                result[
                        gy * GRID + gx
                        ] =
                        count > 0
                                ? (energy / (float) count)
                                / 510f
                                : 0f;
            }
        }

        return result;
    }

    private static int gray(int pixel) {

        int r =
                (pixel >> 16) & 0xFF;

        int g =
                (pixel >> 8) & 0xFF;

        int b =
                pixel & 0xFF;

        return
                (r * 30
                        + g * 59
                        + b * 11)
                        / 100;
    }

    private static float histogramDiff(
            float[] a,
            float[] b) {

        if (a == null
                || b == null
                || a.length != b.length) {

            return 0f;
        }

        float sum = 0f;

        for (int i = 0;
             i < a.length;
             i++) {

            sum +=
                    Math.abs(
                            a[i] - b[i]);
        }

        return
                sum / a.length;
    }
}
