package com.example.smartcropapp.core;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class Pass2Optimizer {

    private static final String TAG =
            "Pass2Optimizer";

    private static final float CENTER_MIN =
            0.40f;

    private static final float CENTER_MAX =
            0.60f;

    private static final int HYSTERESIS_THRESHOLD =
            3;

    public static void optimize(
            File analysisFile,
            File trajectoryFile)
            throws Exception {

        String content;

        try (FileInputStream fis =
                     new FileInputStream(analysisFile)) {

            byte[] data =
                    new byte[(int)
                            analysisFile.length()];

            int read =
                    fis.read(data);

            content =
                    new String(
                            data,
                            0,
                            read,
                            StandardCharsets.UTF_8);
        }

        JSONObject root =
                new JSONObject(content);

        JSONArray shots =
                root.getJSONArray("shots");

        JSONArray outputShots =
                new JSONArray();

        for (int s = 0;
             s < shots.length();
             s++) {

            JSONObject shot =
                    shots.getJSONObject(s);

            /*
             * AUTO LAYOUT
             *
             * Pass 1 hanya menentukan shot boundary.
             * Layout ditentukan otomatis di Pass 2.
             *
             * Pilihan SINGLE/SPLIT dari UI manual
             * tidak digunakan pada jalur normal.
             */
            String layout = autoClassifyLayout(shot);

            int shotId =
                    shot.optInt(
                            "shotId",
                            s);

            long startMs =
                    shot.optLong(
                            "startMs",
                            0);

            JSONObject output =
                    new JSONObject();

            output.put(
                    "shotId",
                    shotId);

            output.put(
                    "startMs",
                    startMs);

            output.put(
                    "layout",
                    layout);

            if ("split".equals(layout)) {

                /*
                 * SPLIT CALIBRATION
                 *
                 * Tidak tracking.
                 * Kita hanya mencari pusat aktivitas
                 * kiri dan kanan SATU KALI per shot.
                 *
                 * Hasil kemudian dibekukan sepanjang shot.
                 */

                float[] calibration =
                        calibrateSplitShot(shot);

                output.put(
                        "topX",
                        calibration[0]);

                output.put(
                        "topY",
                        calibration[1]);

                output.put(
                        "bottomX",
                        calibration[2]);

                output.put(
                        "bottomY",
                        calibration[3]);

                Log.i(
                        TAG,
                        "Shot " + shotId +
                        " SPLIT CALIBRATED:" +
                        " left=" + calibration[0] +
                        "," + calibration[1] +
                        " right=" + calibration[2] +
                        "," + calibration[3]);

                Log.i(
                        TAG,
                        "Shot " + shotId +
                        " SPLIT");

            } else {

                JSONArray samples =
                        shot.optJSONArray(
                                "samples");

                if (samples == null) {
                    samples =
                            new JSONArray();
                }

                output.put(
                        "track",
                        processSingleShot(
                                shotId,
                                samples));
            }

            outputShots.put(
                    output);
        }

        JSONObject outputRoot =
                new JSONObject();

        outputRoot.put(
                "version",
                2);

        outputRoot.put(
                "shots",
                outputShots);

        try (FileOutputStream fos =
                     new FileOutputStream(
                             trajectoryFile)) {

            fos.write(
                    outputRoot
                            .toString()
                            .getBytes(
                                    StandardCharsets.UTF_8));
        }

        Log.i(
                TAG,
                "PASS2 DONE -> " +
                trajectoryFile.getAbsolutePath());
    }

    private static boolean hasStableTwoFaceEvidence(
            JSONObject shot)
            throws Exception {

        JSONArray samples =
                shot.optJSONArray("samples");

        if (samples == null ||
                samples.length() == 0) {
            return false;
        }

        int validSamples = 0;

        for (int i = 0;
             i < samples.length();
             i++) {

            JSONObject sample =
                    samples.getJSONObject(i);

            JSONArray faces =
                    sample.optJSONArray("faces");

            if (faces == null ||
                    faces.length() < 2) {
                continue;
            }

            float minX =
                    Float.MAX_VALUE;

            float maxX =
                    -Float.MAX_VALUE;

            for (int f = 0;
                 f < faces.length();
                 f++) {

                JSONObject face =
                        faces.getJSONObject(f);

                float x =
                        (float)
                        face.optDouble(
                                "x",
                                0.5f);

                if (x < minX) {
                    minX = x;
                }

                if (x > maxX) {
                    maxX = x;
                }
            }

            /*
             * Dua wajah harus benar-benar terpisah.
             *
             * 0.25 = jarak horizontal minimum.
             * Close-up satu wajah dengan beberapa deteksi
             * berdekatan tidak lolos.
             */
            if (maxX - minX >= 0.25f) {
                validSamples++;
            }
        }

        /*
         * Minimal 2 sample berbeda harus mendukung
         * keberadaan dua wajah.
         *
         * Karena Pass1 sampling sekitar 500 ms,
         * ini cukup kuat untuk menolak false-positive
         * satu frame.
         */
        return validSamples >= 2;
    }


    /*
     * ============================================================
     * AUTO LAYOUT CLASSIFIER
     * ============================================================
     */
    private static String autoClassifyLayout(
            JSONObject shot) {

        try {
            JSONArray samples =
                    shot.optJSONArray("samples");

            if (samples == null ||
                    samples.length() == 0) {
                return "single";
            }

            int validSamples = 0;
            int twoFaceSamples = 0;

            /*
             * Dua wajah dianggap benar-benar terpisah
             * bila posisi horizontalnya cukup jauh.
             */
            final float MIN_HORIZONTAL_SEPARATION = 0.16f;

            /*
             * Tidak perlu menunggu 35% sample.
             * Satu bukti kuat dua wajah yang konsisten
             * sudah cukup untuk mengaktifkan SPLIT.
             */
            final int MIN_TWO_FACE_SAMPLES = 2;

            for (int i = 0;
                    i < samples.length();
                    i++) {

                JSONObject sample =
                        samples.optJSONObject(i);

                if (sample == null) {
                    continue;
                }

                JSONArray faces =
                        sample.optJSONArray("faces");

                if (faces == null ||
                        faces.length() < 2) {
                    continue;
                }

                validSamples++;

                float minX = Float.MAX_VALUE;
                float maxX = -Float.MAX_VALUE;

                int usableFaces = 0;

                for (int f = 0;
                        f < faces.length();
                        f++) {

                    JSONObject face =
                            faces.optJSONObject(f);

                    if (face == null) {
                        continue;
                    }

                    double rawX =
                            face.optDouble(
                                    "x",
                                    Double.NaN);

                    double rawSize =
                            face.optDouble(
                                    "size",
                                    Double.NaN);

                    if (Double.isNaN(rawX) ||
                            Double.isNaN(rawSize)) {
                        continue;
                    }

                    float x = (float) rawX;
                    float size = (float) rawSize;

                    if (x < 0.0f ||
                            x > 1.0f ||
                            size <= 0.0f) {
                        continue;
                    }

                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);

                    usableFaces++;
                }

                if (usableFaces >= 2 &&
                        (maxX - minX) >=
                                MIN_HORIZONTAL_SEPARATION) {

                    twoFaceSamples++;
                }
            }

            /*
             * Tidak ada bukti dua wajah.
             */
            if (twoFaceSamples == 0) {

                Log.i(
                        TAG,
                        "AUTO LAYOUT shot=" +
                                shot.optInt("shotId", -1) +
                                " -> SINGLE (no two-face evidence)");

                return "single";
            }

            /*
             * Dua sample atau lebih = SPLIT.
             *
             * Ini sengaja dibuat agresif agar wide shot
             * dua pembicara tidak jatuh ke tengah kosong.
             */
            if (twoFaceSamples >=
                    MIN_TWO_FACE_SAMPLES) {

                Log.i(
                        TAG,
                        "AUTO LAYOUT shot=" +
                                shot.optInt("shotId", -1) +
                                " twoFaceSamples=" +
                                twoFaceSamples +
                                " -> SPLIT");

                return "split";
            }

            /*
             * Satu bukti saja:
             * tetap SPLIT bila pemisahan sangat besar.
             */
            if (validSamples > 0) {

                Log.i(
                        TAG,
                        "AUTO LAYOUT shot=" +
                                shot.optInt("shotId", -1) +
                                " twoFaceSamples=" +
                                twoFaceSamples +
                                " -> SPLIT (strong evidence)");

                return "split";
            }

            return "single";

        } catch (Exception e) {

            Log.w(
                    TAG,
                    "AUTO LAYOUT failed -> SINGLE",
                    e);

            return "single";
        }
    }

    private static float[] calibrateSplitShot(
            JSONObject shot)
            throws Exception {

        JSONArray samples =
                shot.optJSONArray("samples");

        /*
         * Fallback konservatif:
         * LEFT  = 0.25
         * RIGHT = 0.75
         */
        float fallbackLeftX = 0.25f;
        float fallbackRightX = 0.75f;

        if (samples == null ||
                samples.length() == 0) {

            return new float[]{
                    fallbackRightX,
                    0.50f,
                    fallbackLeftX,
                    0.50f
            };
        }

        double leftXSum = 0.0;
        double leftYSum = 0.0;
        double leftWeight = 0.0;

        double rightXSum = 0.0;
        double rightYSum = 0.0;
        double rightWeight = 0.0;

        int validTwoFaceSamples = 0;

        /*
         * IMPORTANT:
         *
         * SPLIT calibration sekarang memakai FACE,
         * bukan texture activity.
         *
         * Ini mencegah:
         * - close-up satu orang
         * - tangan
         * - meja
         * - background
         * - objek lain
         *
         * menjadi pasangan kiri/kanan palsu.
         */
        for (int i = 0;
             i < samples.length();
             i++) {

            JSONObject sample =
                    samples.getJSONObject(i);

            JSONArray faces =
                    sample.optJSONArray("faces");

            if (faces == null ||
                    faces.length() < 2) {
                continue;
            }

            JSONObject leftFace = null;
            JSONObject rightFace = null;

            float minX = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;

            for (int f = 0;
                 f < faces.length();
                 f++) {

                JSONObject face =
                        faces.getJSONObject(f);

                float x =
                        (float)
                        face.optDouble(
                                "x",
                                0.5f);

                if (x < minX) {
                    minX = x;
                    leftFace = face;
                }

                if (x > maxX) {
                    maxX = x;
                    rightFace = face;
                }
            }

            if (leftFace == null ||
                    rightFace == null ||
                    leftFace == rightFace) {
                continue;
            }

            float lx =
                    (float)
                    leftFace.optDouble(
                            "x",
                            0.25f);

            float ly =
                    (float)
                    leftFace.optDouble(
                            "y",
                            0.50f);

            float ls =
                    (float)
                    leftFace.optDouble(
                            "size",
                            0.30f);

            float rx =
                    (float)
                    rightFace.optDouble(
                            "x",
                            0.75f);

            float ry =
                    (float)
                    rightFace.optDouble(
                            "y",
                            0.50f);

            float rs =
                    (float)
                    rightFace.optDouble(
                            "size",
                            0.30f);

            /*
             * Dua wajah harus benar-benar terpisah.
             *
             * Jarak minimum 0.25 mencegah satu close-up
             * atau dua deteksi yang terlalu berdekatan
             * menghasilkan SPLIT.
             */
            if (rx - lx < 0.25f) {
                continue;
            }

            /*
             * Bobot berdasarkan ukuran wajah.
             * Wajah yang lebih jelas mendapat bobot lebih besar,
             * tetapi tidak boleh mendominasi terlalu ekstrem.
             */
            double lw =
                    Math.max(
                            0.10,
                            Math.min(
                                    1.0,
                                    ls));

            double rw =
                    Math.max(
                            0.10,
                            Math.min(
                                    1.0,
                                    rs));

            leftXSum += lx * lw;
            leftYSum += ly * lw;
            leftWeight += lw;

            rightXSum += rx * rw;
            rightYSum += ry * rw;
            rightWeight += rw;

            validTwoFaceSamples++;
        }

        /*
         * Tidak ada bukti dua wajah:
         *
         * Jangan memaksakan posisi berdasarkan texture.
         * Fallback tetap simetris.
         */
        if (validTwoFaceSamples == 0) {

            Log.w(
                    TAG,
                    "SPLIT calibration: tidak ditemukan dua wajah terpisah -> fallback");

            /*
             * RETURN ORDER:
             *
             * [TOP_X, TOP_Y, BOTTOM_X, BOTTOM_Y]
             *
             * TOP    = RIGHT
             * BOTTOM = LEFT
             */
            return new float[]{
                    fallbackRightX,
                    0.50f,
                    fallbackLeftX,
                    0.50f
            };
        }

        float leftX =
                (float)
                (leftXSum / leftWeight);

        float leftY =
                (float)
                (leftYSum / leftWeight);

        float rightX =
                (float)
                (rightXSum / rightWeight);

        float rightY =
                (float)
                (rightYSum / rightWeight);

        leftX =
                clamp(
                        leftX,
                        0.16f,
                        0.42f);

        rightX =
                clamp(
                        rightX,
                        0.58f,
                        0.84f);

        leftY =
                clamp(
                        leftY,
                        0.25f,
                        0.75f);

        rightY =
                clamp(
                        rightY,
                        0.25f,
                        0.75f);

        /*
         * Final separation guard.
         */
        if (rightX - leftX < 0.25f) {

            Log.w(
                    TAG,
                    "SPLIT calibration: separation gagal -> fallback");

            rightX = fallbackRightX;
            rightY = 0.50f;

            leftX = fallbackLeftX;
            leftY = 0.50f;
        }

        Log.i(
                TAG,
                "SPLIT FACE CALIBRATION: " +
                "RIGHT->TOP x=" + rightX +
                " y=" + rightY +
                " | LEFT->BOTTOM x=" + leftX +
                " y=" + leftY +
                " | twoFaceSamples=" +
                validTwoFaceSamples);

        /*
         * RETURN:
         *
         * index 0 = TOP X    = RIGHT
         * index 1 = TOP Y    = RIGHT
         * index 2 = BOTTOM X = LEFT
         * index 3 = BOTTOM Y = LEFT
         */
        return new float[]{
                rightX,
                rightY,
                leftX,
                leftY
        };
    }

    private static JSONArray processSingleShot(
            int shotId,
            JSONArray samples)
            throws Exception {

        JSONArray track = new JSONArray();
        int n = samples.length();

        if (n == 0) {
            return track;
        }

        float[] lockedX = new float[n];
        float[] lockedY = new float[n];
        float[] lockedSize = new float[n];
        long[] times = new long[n];

        final float DEFAULT_X = 0.50f;
        final float DEFAULT_Y = 0.40f;
        final float DEFAULT_SIZE = 0.30f;

        final float MIN_SIZE = 0.20f;
        final float MAX_SIZE = 0.45f;

        final float EDGE_LEFT = 0.20f;
        final float EDGE_RIGHT = 0.80f;

        float previousX = DEFAULT_X;
        float previousY = DEFAULT_Y;
        float previousSize = DEFAULT_SIZE;

        boolean hasValidSubject = false;

        for (int i = 0; i < n; i++) {

            JSONObject sample =
                    samples.getJSONObject(i);

            times[i] =
                    sample.optLong("t", 0);

            JSONArray faces =
                    sample.optJSONArray("faces");

            /*
             * No face:
             * fallback ke center.
             */
            if (faces == null ||
                    faces.length() == 0) {

                lockedX[i] = DEFAULT_X;
                lockedY[i] = DEFAULT_Y;
                lockedSize[i] = DEFAULT_SIZE;

                previousX = DEFAULT_X;
                previousY = DEFAULT_Y;
                previousSize = DEFAULT_SIZE;

                hasValidSubject = false;
                continue;
            }

            JSONObject chosen = null;

            /*
             * SINGLE dengan satu wajah:
             * gunakan langsung.
             */
            if (faces.length() == 1) {

                chosen =
                        faces.getJSONObject(0);

            } else {

                /*
                 * Multi-face SINGLE:
                 *
                 * Jangan menebak speaker.
                 *
                 * Score =
                 * ukuran + kedekatan center + kestabilan.
                 */
                float bestScore =
                        -Float.MAX_VALUE;

                for (int f = 0;
                     f < faces.length();
                     f++) {

                    JSONObject candidate =
                            faces.getJSONObject(f);

                    float x =
                            (float)
                            candidate.optDouble(
                                    "x",
                                    DEFAULT_X);

                    float y =
                            (float)
                            candidate.optDouble(
                                    "y",
                                    DEFAULT_Y);

                    float size =
                            (float)
                            candidate.optDouble(
                                    "size",
                                    DEFAULT_SIZE);

                    if (x < 0.0f ||
                            x > 1.0f ||
                            y < 0.0f ||
                            y > 1.0f) {
                        continue;
                    }

                    size =
                            clamp(
                                    size,
                                    MIN_SIZE,
                                    MAX_SIZE);

                    float sizeScore =
                            clamp(
                                    size,
                                    0.0f,
                                    1.0f);

                    float centerDistance =
                            Math.abs(x - 0.50f);

                    float centerScore =
                            1.0f -
                            clamp(
                                    centerDistance * 2.0f,
                                    0.0f,
                                    1.0f);

                    float movement =
                            Math.abs(
                                    x - previousX);

                    float stabilityScore =
                            1.0f -
                            clamp(
                                    movement * 2.0f,
                                    0.0f,
                                    1.0f);

                    float score =
                            (sizeScore * 0.35f) +
                            (centerScore * 0.30f) +
                            (stabilityScore * 0.35f);

                    if (score > bestScore) {
                        bestScore = score;
                        chosen = candidate;
                    }
                }
            }

            /*
             * Tidak ada kandidat valid.
             */
            if (chosen == null) {

                lockedX[i] = DEFAULT_X;
                lockedY[i] = DEFAULT_Y;
                lockedSize[i] = DEFAULT_SIZE;

                previousX = DEFAULT_X;
                previousY = DEFAULT_Y;
                previousSize = DEFAULT_SIZE;

                hasValidSubject = false;
                continue;
            }

            float detectedX =
                    (float)
                    chosen.optDouble(
                            "x",
                            DEFAULT_X);

            float detectedY =
                    (float)
                    chosen.optDouble(
                            "y",
                            DEFAULT_Y);

            float detectedSize =
                    (float)
                    chosen.optDouble(
                            "size",
                            DEFAULT_SIZE);

            detectedX =
                    clamp(
                            detectedX,
                            0.05f,
                            0.95f);

            detectedY =
                    clamp(
                            detectedY,
                            0.15f,
                            0.85f);

            detectedSize =
                    clamp(
                            detectedSize,
                            MIN_SIZE,
                            MAX_SIZE);

            /*
             * Normal:
             * center.
             *
             * Jika subjek terlalu dekat edge:
             * mulai ikuti.
             */
            float targetX;

            if (detectedX >= EDGE_LEFT &&
                    detectedX <= EDGE_RIGHT) {

                targetX = DEFAULT_X;

            } else {

                targetX = detectedX;
            }

            /*
             * Y:
             * prioritaskan upper-middle.
             */
            float targetY =
                    detectedY;

            if (targetY >= 0.25f &&
                    targetY <= 0.60f) {

                targetY = DEFAULT_Y;
            }

            /*
             * Size:
             * perubahan kecil jangan dikejar.
             */
            float targetSize =
                    detectedSize;

            if (hasValidSubject) {

                float sizeDelta =
                        Math.abs(
                                targetSize -
                                previousSize);

                if (sizeDelta < 0.05f) {
                    targetSize =
                            previousSize;
                }
            }

            lockedX[i] = targetX;
            lockedY[i] = targetY;
            lockedSize[i] = targetSize;

            previousX = targetX;
            previousY = targetY;
            previousSize = targetSize;

            hasValidSubject = true;
        }

        /*
         * Adaptive smoothing.
         *
         * Perpindahan kecil = lambat.
         * Perpindahan besar = lebih cepat.
         */
        float smoothX = lockedX[0];
        float smoothY = lockedY[0];
        float smoothSize = lockedSize[0];

        for (int i = 0;
             i < n;
             i++) {

            if (i > 0) {

                float distanceX =
                        Math.abs(
                                lockedX[i] -
                                smoothX);

                float alphaX;

                if (distanceX < 0.10f) {
                    alphaX = 0.20f;
                } else if (distanceX < 0.25f) {
                    alphaX = 0.35f;
                } else {
                    alphaX = 0.60f;
                }

                smoothX +=
                        alphaX *
                        (lockedX[i] -
                                smoothX);

                final float alphaY = 0.25f;

                smoothY +=
                        alphaY *
                        (lockedY[i] -
                                smoothY);

                final float alphaSize = 0.15f;

                smoothSize +=
                        alphaSize *
                        (lockedSize[i] -
                                smoothSize);
            }

            JSONObject point =
                    new JSONObject();

            point.put(
                    "t",
                    times[i]);

            point.put(
                    "x",
                    (double)
                    clamp(
                            smoothX,
                            0.05f,
                            0.95f));

            point.put(
                    "y",
                    (double)
                    clamp(
                            smoothY,
                            0.15f,
                            0.85f));

            point.put(
                    "size",
                    (double)
                    clamp(
                            smoothSize,
                            MIN_SIZE,
                            MAX_SIZE));

            track.put(point);
        }

        Log.i(
                TAG,
                "Shot " + shotId +
                " SINGLE: adaptive subject tracking");

        return track;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

}
