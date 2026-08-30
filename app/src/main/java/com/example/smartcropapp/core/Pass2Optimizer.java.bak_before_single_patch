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

            String layout =
                    shot.optString(
                            "layout",
                            "unclassified");

            /*
             * MANUAL LAYOUT MODE
             *
             * Pass 1 sekarang hanya menentukan shot boundary.
             * Layout SPLIT/SINGLE harus diberikan oleh UI/manual
             * sebelum Pass 2 dipakai untuk rendering.
             *
             * Jangan mengubah UNCLASSIFIED menjadi SINGLE di sini.
             */

            if ("".equals(layout.trim())) {
                layout = "unclassified";
            }

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

        JSONArray track =
                new JSONArray();

        int n =
                samples.length();

        if (n == 0) {
            return track;
        }

        float[] lockedX =
                new float[n];

        float[] lockedY =
                new float[n];

        float[] lockedSize =
                new float[n];

        long[] times =
                new long[n];

        float lastX =
                0.30f;

        float lastY =
                0.40f;

        float lastSize =
                0.30f;

        boolean hasFace =
                false;

        String currentSide =
                "LEFT";

        String pendingSide =
                "LEFT";

        int sideCounter =
                0;

        for (int i = 0;
             i < n;
             i++) {

            JSONObject sample =
                    samples.getJSONObject(i);

            times[i] =
                    sample.optLong(
                            "t",
                            0);

            JSONArray faces =
                    sample.optJSONArray(
                            "faces");

            if (faces == null ||
                    faces.length() == 0) {

                lockedX[i] =
                        lastX;

                lockedY[i] =
                        lastY;

                lockedSize[i] =
                        lastSize;

                continue;
            }

            if (faces.length() == 1) {

                JSONObject face =
                        faces.getJSONObject(0);

                float x =
                        (float)
                        face.optDouble(
                                "x",
                                lastX);

                float y =
                        (float)
                        face.optDouble(
                                "y",
                                lastY);

                float size =
                        (float)
                        face.optDouble(
                                "size",
                                lastSize);

                lockedX[i] =
                        x;

                lockedY[i] =
                        y;

                lockedSize[i] =
                        size;

                lastX = x;
                lastY = y;
                lastSize = size;

                currentSide =
                        x < 0.5f
                                ? "LEFT"
                                : "RIGHT";

                pendingSide =
                        currentSide;

                sideCounter =
                        0;

                hasFace =
                        true;

                continue;
            }

            JSONObject leftFace =
                    null;

            JSONObject rightFace =
                    null;

            float minX =
                    Float.MAX_VALUE;

            float maxX =
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
                                0.5f);

                if (x < minX) {
                    minX = x;
                    leftFace =
                            candidate;
                }

                if (x > maxX) {
                    maxX = x;
                    rightFace =
                            candidate;
                }
            }

            JSONObject chosen =
                    "RIGHT".equals(
                            currentSide)
                            ? rightFace
                            : leftFace;

            String detectedSide =
                    currentSide;

            if (leftFace != null &&
                    rightFace != null) {

                float lx =
                        (float)
                        leftFace.optDouble(
                                "x",
                                0.3f);

                float rx =
                        (float)
                        rightFace.optDouble(
                                "x",
                                0.7f);

                detectedSide =
                        Math.abs(
                                lx - lastX)
                                <
                                Math.abs(
                                        rx - lastX)
                                ? "LEFT"
                                : "RIGHT";

                if (!detectedSide.equals(
                        pendingSide)) {

                    pendingSide =
                            detectedSide;

                    sideCounter =
                            1;

                } else {

                    sideCounter++;
                }

                if (sideCounter >=
                        HYSTERESIS_THRESHOLD) {

                    currentSide =
                            pendingSide;
                }

                chosen =
                        "RIGHT".equals(
                                currentSide)
                                ? rightFace
                                : leftFace;
            }

            if (chosen == null) {
                chosen =
                        leftFace != null
                                ? leftFace
                                : rightFace;
            }

            if (chosen == null) {

                lockedX[i] =
                        lastX;

                lockedY[i] =
                        lastY;

                lockedSize[i] =
                        lastSize;

                continue;
            }

            float x =
                    (float)
                    chosen.optDouble(
                            "x",
                            lastX);

            float y =
                    (float)
                    chosen.optDouble(
                            "y",
                            lastY);

            float size =
                    (float)
                    chosen.optDouble(
                            "size",
                            lastSize);

            if (x >= CENTER_MIN &&
                    x <= CENTER_MAX) {

                x =
                        "LEFT".equals(
                                currentSide)
                                ? 0.30f
                                : 0.70f;
            }

            lockedX[i] =
                    x;

            lockedY[i] =
                    y;

            lockedSize[i] =
                    size;

            lastX = x;
            lastY = y;
            lastSize = size;

            hasFace =
                    true;
        }

        if (!hasFace) {

            Log.w(
                    TAG,
                    "Shot " + shotId +
                    ": no face -> center");

            for (int i = 0;
                 i < n;
                 i++) {

                lockedX[i] =
                        0.50f;

                lockedY[i] =
                        0.40f;

                lockedSize[i] =
                        0.30f;
            }
        }

        float smoothX =
                lockedX[0];

        float smoothY =
                lockedY[0];

        float smoothSize =
                lockedSize[0];

        final float alpha =
                0.40f;

        for (int i = 0;
             i < n;
             i++) {

            if (i > 0) {

                smoothX +=
                        alpha *
                        (lockedX[i] -
                                smoothX);

                smoothY +=
                        alpha *
                        (lockedY[i] -
                                smoothY);

                smoothSize +=
                        alpha *
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
                    (double) smoothX);

            point.put(
                    "y",
                    (double) smoothY);

            point.put(
                    "size",
                    (double) smoothSize);

            track.put(point);
        }

        return track;
    }
    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

}
