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
                            "single");

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

    private static float[] calibrateSplitShot(
            JSONObject shot)
            throws Exception {

        JSONArray samples =
                shot.optJSONArray("samples");

        /*
         * Fallback konservatif.
         */
        float fallbackLeftX = 0.25f;
        float fallbackRightX = 0.75f;

        if (samples == null ||
                samples.length() == 0) {

            return new float[]{
                    fallbackLeftX,
                    0.50f,
                    fallbackRightX,
                    0.50f
            };
        }

        double leftXSum = 0.0;
        double rightXSum = 0.0;

        double leftYSum = 0.0;
        double rightYSum = 0.0;

        double leftWeight = 0.0;
        double rightWeight = 0.0;

        for (int i = 0;
             i < samples.length();
             i++) {

            JSONObject sample =
                    samples.getJSONObject(i);

            JSONArray texture =
                    sample.optJSONArray("texture");

            if (texture == null ||
                    texture.length() == 0) {
                continue;
            }

            /*
             * GRID_X = 12
             * GRID_Y = 8
             *
             * Hanya memakai area tengah vertikal.
             * Tujuannya menghindari meja/lantai/langit
             * sebagai sumber pusat aktivitas.
             */

            for (int y = 1;
                 y < 7;
                 y++) {

                for (int x = 0;
                     x < 12;
                     x++) {

                    int index =
                            y * 12 + x;

                    if (index >= texture.length()) {
                        continue;
                    }

                    float activity =
                            (float)
                            texture.optDouble(
                                    index,
                                    0.0);

                    /*
                     * Non-linear boost:
                     * aktivitas tinggi lebih berpengaruh
                     * daripada noise kecil.
                     */
                    double weight =
                            activity * activity;

                    if (weight <= 0.000001) {
                        continue;
                    }

                    float normalizedX =
                            (x + 0.5f) / 12f;

                    float normalizedY =
                            (y + 0.5f) / 8f;

                    /*
                     * Tengah frame sengaja tidak dipakai
                     * untuk menentukan pusat panel.
                     */
                    if (normalizedX < 0.42f) {

                        leftXSum +=
                                normalizedX * weight;

                        leftYSum +=
                                normalizedY * weight;

                        leftWeight +=
                                weight;

                    } else if (normalizedX > 0.58f) {

                        rightXSum +=
                                normalizedX * weight;

                        rightYSum +=
                                normalizedY * weight;

                        rightWeight +=
                                weight;
                    }
                }
            }
        }

        float leftX =
                leftWeight > 0.0001
                        ? (float)
                          (leftXSum / leftWeight)
                        : fallbackLeftX;

        float leftY =
                leftWeight > 0.0001
                        ? (float)
                          (leftYSum / leftWeight)
                        : 0.50f;

        float rightX =
                rightWeight > 0.0001
                        ? (float)
                          (rightXSum / rightWeight)
                        : fallbackRightX;

        float rightY =
                rightWeight > 0.0001
                        ? (float)
                          (rightYSum / rightWeight)
                        : 0.50f;

        /*
         * Safety limits.
         *
         * Jangan biarkan calibration masuk
         * terlalu dekat ke tengah.
         */
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
                        0.30f,
                        0.70f);

        rightY =
                clamp(
                        rightY,
                        0.30f,
                        0.70f);

        /*
         * Jika kedua pusat terlalu dekat,
         * gunakan fallback simetris.
         */
        if (rightX - leftX < 0.25f) {

            leftX = 0.25f;
            rightX = 0.75f;

            leftY = 0.50f;
            rightY = 0.50f;

            Log.w(
                    TAG,
                    "Split calibration terlalu dekat -> fallback");
        }

        return new float[]{
                leftX,
                leftY,
                rightX,
                rightY
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
