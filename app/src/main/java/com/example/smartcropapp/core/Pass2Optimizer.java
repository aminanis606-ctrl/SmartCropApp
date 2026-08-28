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

    private static final float EMA_ALPHA =
            0.40f;

    public static void optimize(
            File analysisFile,
            File trajectoryFile)
            throws Exception {

        byte[] data;

        try (FileInputStream fis =
                     new FileInputStream(
                             analysisFile)) {

            data =
                    new byte[
                            (int) analysisFile.length()];

            int read =
                    fis.read(data);

            if (read <= 0) {
                throw new RuntimeException(
                        "Analysis file kosong");
            }
        }

        String content =
                new String(
                        data,
                        StandardCharsets.UTF_8);

        JSONObject root =
                new JSONObject(content);

        JSONArray shots =
                root.getJSONArray("shots");

        JSONArray outputShots =
                new JSONArray();

        for (int i = 0;
             i < shots.length();
             i++) {

            JSONObject shot =
                    shots.getJSONObject(i);

            int shotId =
                    shot.optInt(
                            "shotId",
                            i);

            long startMs =
                    shot.optLong(
                            "startMs",
                            0);

            String layout =
                    shot.optString(
                            "layout",
                            "single");

            JSONObject out =
                    new JSONObject();

            out.put("shotId", shotId);
            out.put("startMs", startMs);
            out.put("layout", layout);

            if ("split".equals(layout)) {

                /*
                 * SPLIT sengaja tidak tracking.
                 *
                 * Prinsip:
                 * satu shot = dua anchor stabil.
                 *
                 * Kita beri sedikit margin dari
                 * tepi agar crop tidak terlalu ekstrem.
                 */
                out.put(
                        "topX",
                        0.25f);

                out.put(
                        "topY",
                        0.50f);

                out.put(
                        "bottomX",
                        0.75f);

                out.put(
                        "bottomY",
                        0.50f);

                /*
                 * Informasi diagnostik.
                 */
                out.put(
                        "topSize",
                        0.50f);

                out.put(
                        "bottomSize",
                        0.50f);

                Log.i(
                        TAG,
                        "Shot "
                                + shotId
                                + " SPLIT: "
                                + "left=0.25 "
                                + "right=0.75");

            } else {

                JSONArray samples =
                        shot.optJSONArray(
                                "samples");

                if (samples == null) {
                    samples =
                            new JSONArray();
                }

                out.put(
                        "track",
                        processSingleShot(
                                shotId,
                                samples));
            }

            outputShots.put(out);
        }

        JSONObject outputRoot =
                new JSONObject();

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
                "Pass 2 selesai -> "
                        + trajectoryFile
                        .getAbsolutePath());
    }

    private static JSONArray processSingleShot(
            int shotId,
            JSONArray samples)
            throws Exception {

        int n =
                samples.length();

        JSONArray track =
                new JSONArray();

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

        float lastX = 0.30f;
        float lastY = 0.40f;
        float lastSize = 0.30f;

        boolean hasEverHadFace =
                false;

        String currentSide =
                "LEFT";

        String pendingSide =
                "LEFT";

        int sideCounter = 0;

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

            if (faces == null) {
                faces = new JSONArray();
            }

            if (faces.length() == 0) {

                /*
                 * Target Lock:
                 * jangan pindah ke center
                 * hanya karena detector kosong.
                 */
                lockedX[i] = lastX;
                lockedY[i] = lastY;
                lockedSize[i] = lastSize;

            } else if (faces.length() == 1) {

                JSONObject face =
                        faces.getJSONObject(0);

                float x =
                        (float) face.optDouble(
                                "x",
                                lastX);

                float y =
                        (float) face.optDouble(
                                "y",
                                lastY);

                float size =
                        (float) face.optDouble(
                                "size",
                                lastSize);

                lockedX[i] = x;
                lockedY[i] = y;
                lockedSize[i] = size;

                lastX = x;
                lastY = y;
                lastSize = size;

                currentSide =
                        x < 0.5f
                                ? "LEFT"
                                : "RIGHT";

                pendingSide =
                        currentSide;

                sideCounter = 0;

                hasEverHadFace =
                        true;

            } else {

                /*
                 * Multi-face logic dilanjutkan
                 * pada bagian berikutnya.
                 */
                MultiFaceResult result =
                        chooseMultiFace(
                                faces,
                                currentSide,
                                pendingSide,
                                sideCounter,
                                lastX);

                currentSide =
                        result.currentSide;

                pendingSide =
                        result.pendingSide;

                sideCounter =
                        result.sideCounter;

                JSONObject chosenFace =
                        result.face;

                float x =
                        (float) chosenFace.optDouble(
                                "x",
                                lastX);

                float y =
                        (float) chosenFace.optDouble(
                                "y",
                                lastY);

                float size =
                        (float) chosenFace.optDouble(
                                "size",
                                lastSize);

                /*
                 * SMART CENTER GUARD.
                 *
                 * Jika kandidat jatuh di tengah,
                 * jangan langsung menggeser kamera
                 * ke ruang kosong.
                 */
                if (x >= CENTER_MIN
                        && x <= CENTER_MAX) {

                    x =
                            "LEFT".equals(
                                    currentSide)
                                    ? 0.30f
                                    : 0.70f;
                }

                lockedX[i] = x;
                lockedY[i] = y;
                lockedSize[i] = size;

                lastX = x;
                lastY = y;
                lastSize = size;

                hasEverHadFace =
                        true;
            }
        }

        if (!hasEverHadFace) {

            /*
             * Fallback hanya jika benar-benar
             * tidak ada kandidat sepanjang shot.
             */
            for (int i = 0;
                 i < n;
                 i++) {

                lockedX[i] = 0.50f;
                lockedY[i] = 0.40f;
                lockedSize[i] = 0.30f;
            }

            Log.w(
                    TAG,
                    "Shot "
                            + shotId
                            + ": no target -> center");
        }

        return applyEMA(
                times,
                lockedX,
                lockedY,
                lockedSize);
    }

    private static class MultiFaceResult {

        JSONObject face;

        String currentSide;
        String pendingSide;

        int sideCounter;
    }

    private static MultiFaceResult chooseMultiFace(
            JSONArray faces,
            String currentSide,
            String pendingSide,
            int sideCounter,
            float lastX)
            throws Exception {

        JSONObject leftFace = null;
        JSONObject rightFace = null;

        float minX = 2f;
        float maxX = -1f;

        for (int i = 0;
             i < faces.length();
             i++) {

            JSONObject candidate =
                    faces.getJSONObject(i);

            float x =
                    (float) candidate.optDouble(
                            "x",
                            0.5f);

            if (x < minX) {
                minX = x;
                leftFace = candidate;
            }

            if (x > maxX) {
                maxX = x;
                rightFace = candidate;
            }
        }

        JSONObject chosen;

        if (leftFace == null
                && rightFace == null) {

            chosen =
                    new JSONObject();

        } else if (leftFace == null) {

            chosen = rightFace;

        } else if (rightFace == null) {

            chosen = leftFace;

        } else {

            float lx =
                    (float) leftFace.optDouble(
                            "x",
                            0.3f);

            float rx =
                    (float) rightFace.optDouble(
                            "x",
                            0.7f);

            String detectedSide =
                    Math.abs(lx - lastX)
                            <= Math.abs(rx - lastX)
                            ? "LEFT"
                            : "RIGHT";

            if (!detectedSide.equals(
                    pendingSide)) {

                pendingSide =
                        detectedSide;

                sideCounter = 1;

            } else {

                sideCounter++;
            }

            if (sideCounter
                    >= HYSTERESIS_THRESHOLD) {

                currentSide =
                        pendingSide;
            }

            chosen =
                    "RIGHT".equals(
                            currentSide)
                            ? rightFace
                            : leftFace;
        }

        MultiFaceResult result =
                new MultiFaceResult();

        result.face = chosen;
        result.currentSide =
                currentSide;
        result.pendingSide =
                pendingSide;
        result.sideCounter =
                sideCounter;

        return result;
    }

    private static JSONArray applyEMA(
            long[] times,
            float[] xs,
            float[] ys,
            float[] sizes)
            throws Exception {

        JSONArray track =
                new JSONArray();

        float smoothX = xs[0];
        float smoothY = ys[0];
        float smoothSize = sizes[0];

        for (int i = 0;
             i < xs.length;
             i++) {

            if (i > 0) {

                smoothX +=
                        EMA_ALPHA
                                * (xs[i]
                                - smoothX);

                smoothY +=
                        EMA_ALPHA
                                * (ys[i]
                                - smoothY);

                smoothSize +=
                        EMA_ALPHA
                                * (sizes[i]
                                - smoothSize);
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
}
