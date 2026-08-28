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

    private static final long INTERVAL_US = 500_000L;

    private static final int GRID_X = 12;
    private static final int GRID_Y = 8;
    private static final int PIXEL_STEP = 4;

    private static final float CUT_THRESHOLD = 0.18f;

    private static final float WINDOW_START = 0.20f;
    private static final float WINDOW_END = 0.80f;

    private static final float SPLIT_THRESHOLD = 0.58f;
    private static final float MIN_SIDE_ACTIVITY = 0.012f;
    private static final float MAX_CENTER_DOMINANCE = 1.20f;
    private static final float MIN_BALANCE = 0.30f;
    private static final float MIN_STABILITY = 0.30f;

    private static class FrameFeature {
        long timeMs;
        float[] brightness;
        float[] texture;

        FrameFeature(long timeMs, float[] brightness, float[] texture) {
            this.timeMs = timeMs;
            this.brightness = brightness;
            this.texture = texture;
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
            Log.i(TAG,
                    "PASS1 START: shot boundary + spatial layout analysis");

            retriever.setDataSource(
                    context,
                    sourceVideoUri);

            long durationMs = 0;

            String duration =
                    retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_DURATION);

            if (duration != null) {
                durationMs = Long.parseLong(duration);
            }

            if (durationMs <= 0) {
                durationMs = 10_000;
            }

            long durationUs =
                    durationMs * 1000L;

            JSONArray shots =
                    new JSONArray();

            ShotBuffer current =
                    new ShotBuffer();

            current.startMs = 0;

            float[] previousBrightness = null;

            int shotId = 0;

            for (long timeUs = 0;
                 timeUs <= durationUs;
                 timeUs += INTERVAL_US) {

                Bitmap bitmap =
                        retriever.getFrameAtTime(
                                timeUs,
                                MediaMetadataRetriever
                                        .OPTION_CLOSEST_SYNC);

                if (bitmap == null) {
                    continue;
                }

                FrameFeature feature =
                        analyzeFrame(
                                bitmap,
                                timeUs / 1000L);

                if (previousBrightness != null) {

                    float diff =
                            histogramDiff(
                                    previousBrightness,
                                    feature.brightness);

                    if (diff > CUT_THRESHOLD) {

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
                    2);

            root.put(
                    "shots",
                    shots);

            try (FileOutputStream fos =
                         new FileOutputStream(outputFile)) {

                fos.write(
                        root.toString()
                                .getBytes("UTF-8"));
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

        float[] brightness =
                new float[
                        GRID_X * GRID_Y];

        float[] texture =
                new float[
                        GRID_X * GRID_Y];

        int width =
                bitmap.getWidth();

        int height =
                bitmap.getHeight();

        int cellW =
                Math.max(
                        1,
                        width / GRID_X);

        int cellH =
                Math.max(
                        1,
                        height / GRID_Y);

        for (int gy = 0;
             gy < GRID_Y;
             gy++) {

            for (int gx = 0;
                 gx < GRID_X;
                 gx++) {

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

                float sum = 0f;
                float textureSum = 0f;

                int count = 0;

                for (int y = startY;
                     y < endY;
                     y += PIXEL_STEP) {

                    for (int x = startX;
                         x < endX;
                         x += PIXEL_STEP) {

                        int pixel =
                                bitmap.getPixel(
                                        x,
                                        y);

                        int r =
                                (pixel >> 16) & 0xff;

                        int g =
                                (pixel >> 8) & 0xff;

                        int b =
                                pixel & 0xff;

                        float gray =
                                (r + g + b)
                                / 765f;

                        sum += gray;

                        if (x + PIXEL_STEP < endX) {

                            int p2 =
                                    bitmap.getPixel(
                                            x + PIXEL_STEP,
                                            y);

                            int r2 =
                                    (p2 >> 16) & 0xff;

                            int g2 =
                                    (p2 >> 8) & 0xff;

                            int b2 =
                                    p2 & 0xff;

                            float gray2 =
                                    (r2 + g2 + b2)
                                    / 765f;

                            textureSum +=
                                    Math.abs(
                                            gray -
                                            gray2);
                        }

                        count++;
                    }
                }

                int index =
                        gy * GRID_X + gx;

                brightness[index] =
                        count == 0
                                ? 0f
                                : sum / count;

                texture[index] =
                        count == 0
                                ? 0f
                                : textureSum / count;
            }
        }

        return new FrameFeature(
                timeMs,
                brightness,
                texture);
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

        return
                n == 0
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

            for (float v :
                    frame.brightness) {

                brightness.put(
                        (double) v);
            }

            JSONArray texture =
                    new JSONArray();

            for (float v :
                    frame.texture) {

                texture.put(
                        (double) v);
            }

            sample.put(
                    "brightness",
                    brightness);

            sample.put(
                    "texture",
                    texture);

            samples.put(
                    sample);
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

        if (frames.isEmpty()) return 0f;

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

        if (frames.isEmpty()) return 0f;

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

                /*
                 * Texture diberi bobot lebih besar.
                 * Brightness hanya menjadi sinyal
                 * pendukung, bukan penentu tunggal.
                 */
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

        if (frames.size() < 2) return 0f;

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

        int n =
                shot.frames.size();

        if (n < 2) {
            Log.i(
                    TAG,
                    "Shot terlalu pendek -> SINGLE");

            return "single";
        }

        int start =
                Math.max(
                        0,
                        (int) (n * WINDOW_START));

        int end =
                Math.min(
                        n,
                        Math.max(
                                start + 1,
                                (int) (n * WINDOW_END)));

        List<FrameFeature> window =
                shot.frames.subList(
                        start,
                        end);

        /*
         * 12 kolom dibagi menjadi:
         *
         * LEFT   = 0..3
         * CENTER = 4..7
         * RIGHT  = 8..11
         *
         * Kita sengaja memakai area tengah
         * yang cukup lebar agar tidak tertipu
         * oleh satu-dua piksel/objek.
         */

        float left =
                spatialContrast(
                        window,
                        0,
                        4);

        float center =
                spatialContrast(
                        window,
                        4,
                        8);

        float right =
                spatialContrast(
                        window,
                        8,
                        12);

        float leftBright =
                averageRegion(
                        window,
                        false,
                        0,
                        4);

        float centerBright =
                averageRegion(
                        window,
                        false,
                        4,
                        8);

        float rightBright =
                averageRegion(
                        window,
                        false,
                        8,
                        12);

        float leftStable =
                temporalConsistency(
                        window,
                        0,
                        4);

        float rightStable =
                temporalConsistency(
                        window,
                        8,
                        12);

        float sideScore =
                (left + right) * 0.5f;

        /*
         * IMPORTANT:
         *
         * Jangan menganggap background terang =
         * subjek.
         *
         * Kita lebih tertarik pada PERBEDAAN
         * struktur antar wilayah.
         */

        float centerRatio =
                center /
                Math.max(
                        0.0001f,
                        sideScore);

        boolean sidesActive =
                left >= MIN_SIDE_ACTIVITY &&
                right >= MIN_SIDE_ACTIVITY;

        boolean centerNotDominant =
                centerRatio <=
                MAX_CENTER_DOMINANCE;

        float balance =
                1f -
                Math.min(
                        1f,
                        Math.abs(left - right) /
                        Math.max(
                                0.0001f,
                                left + right));

        float stability =
                (leftStable +
                 rightStable) * 0.5f;

        /*
         * Relative contrast.
         *
         * Ini membuat detector tidak terlalu
         * bergantung pada warna/background global.
         */

        float leftVsCenter =
                left /
                Math.max(
                        0.0001f,
                        center);

        float rightVsCenter =
                right /
                Math.max(
                        0.0001f,
                        center);

        float separation =
                Math.min(
                        2f,
                        (leftVsCenter +
                         rightVsCenter) * 0.5f);

        float splitScore =
                sideScore * 2.0f
                + balance * 0.30f
                + stability * 0.35f
                + separation * 0.15f
                - Math.max(
                        0f,
                        centerRatio - 1f)
                  * 0.60f;

        Log.i(
                TAG,
                "Shot adaptive:" +
                " sideScore=" + sideScore +
                " centerRatio=" + centerRatio +
                " separation=" + separation +
                " sidesActive=" + sidesActive +
                " centerOK=" + centerNotDominant);

        boolean leftStrong =
                left >= MIN_SIDE_ACTIVITY;

        boolean rightStrong =
                right >= MIN_SIDE_ACTIVITY;

        boolean balancedEnough =
                balance >= MIN_BALANCE;

        boolean stableEnough =
                stability >= MIN_STABILITY;

        Log.i(
                TAG,
                "SHOT_DIAGNOSTIC " +
                "start=" + shot.startMs +
                " L=" + left +
                " C=" + center +
                " R=" + right +
                " Lstrong=" + leftStrong +
                " Rstrong=" + rightStrong +
                " balance=" + balance +
                " stable=" + stability +
                " centerRatio=" + centerRatio +
                " separation=" + separation +
                " score=" + splitScore);

        /*
         * Background Guard:
         *
         * Background yang seragam biasanya mempunyai
         * texture rendah dan relatif stabil.
         *
         * Jika kiri + kanan sama-sama tidak aktif,
         * jangan pernah memaksakan SPLIT.
         */

        if (!leftStrong || !rightStrong) {

            Log.i(
                    TAG,
                    "SPLIT_REJECT: side activity terlalu rendah");

            return "single";
        }

        if (!balancedEnough) {

            Log.i(
                    TAG,
                    "SPLIT_REJECT: kiri/kanan terlalu tidak seimbang");

            return "single";
        }

        if (!stableEnough) {

            Log.i(
                    TAG,
                    "SPLIT_REJECT: side activity tidak stabil");

            return "single";
        }

        /*
         * BILATERAL SPLIT OVERRIDE
         *
         * Jika kedua sisi sama-sama aktif, cukup seimbang,
         * stabil, dan secara relatif jauh lebih bertekstur
         * daripada center, jangan biarkan center membatalkan
         * deteksi dua-orang.
         *
         * Ini penting untuk wide shot:
         *
         *      PERSON        EMPTY        PERSON
         *        L             C             R
         *
         * Center kosong bukan alasan untuk memilih center.
         */

        boolean bilateralEvidence =
                leftStrong &&
                rightStrong &&
                balance >= 0.30f &&
                stableEnough &&
                separation >= 1.15f;

        if (bilateralEvidence) {

            Log.i(
                    TAG,
                    "BILATERAL_OVERRIDE => SPLIT" +
                    " separation=" + separation +
                    " balance=" + balance +
                    " stability=" + stability);

            return "split";
        }

        if (!centerNotDominant) {

            Log.i(
                    TAG,
                    "SPLIT_REJECT: center terlalu dominan");

            return "single";
        }

        if (splitScore < SPLIT_THRESHOLD) {

            Log.i(
                    TAG,
                    "SPLIT_REJECT: score di bawah threshold");

            return "single";
        }

        /*
         * Threshold sementara.
         * Belum final — bagian 03 akan membuat
         * scoring lebih adaptif terhadap shot.
         */

        Log.i(
                TAG,
                "LAYOUT => SPLIT");

        return "split";
    }
}
