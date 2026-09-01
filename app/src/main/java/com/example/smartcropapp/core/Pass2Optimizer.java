package com.example.smartcropapp.core;

import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class Pass2Optimizer {

    private static final String TAG = "Pass2Optimizer";

    private static final float DEFAULT_X = 0.50f;
    private static final float DEFAULT_Y = 0.45f;
    private static final float DEFAULT_SIZE = 0.30f;

    /*
     * CONFIG dan DIAGNOSTIC SENGAJA DIPISAH.
     *
     * Config:
     *   Downloads/IkhlasApp/config/manual_split.txt
     *
     * Diagnostic:
     *   Downloads/SmartReframe/analysis_*.json
     *   Downloads/SmartReframe/trajectory_*.json
     */

    private static File getConfigFile() {
        File downloads = Environment
                .getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);

        return new File(
                new File(new File(downloads, "IkhlasApp"), "config"),
                "manual_split.txt");
    }

    private static class Point {
        float x;
        float y;
        float size;

        Point(float x, float y, float size) {
            this.x = x;
            this.y = y;
            this.size = size;
        }
    }

    private static class FrameSample {
        long timeMs;
        float[] brightness;
        float[] texture;

        FrameSample(
                long timeMs,
                float[] brightness,
                float[] texture) {

            this.timeMs = timeMs;
            this.brightness = brightness;
            this.texture = texture;
        }
    }

    private static class Shot {
        int shotId;
        long startMs;
        String layout;

        float topX = 0.25f;
        float topY = 0.50f;
        float bottomX = 0.75f;
        float bottomY = 0.50f;

        final List<FrameSample> samples =
                new ArrayList<>();
    }

    public static void optimize(
            File analysisFile,
            File trajectoryFile)
            throws Exception {

        if (analysisFile == null ||
                !analysisFile.exists()) {

            throw new Exception(
                    "analysis.json tidak ditemukan");
        }

        Log.i(
                TAG,
                "PASS2 START: analysis -> trajectory");

        String content =
                new String(
                        Files.readAllBytes(
                                analysisFile.toPath()),
                        StandardCharsets.UTF_8);

        JSONObject root =
                new JSONObject(content);

        JSONArray shotsJson =
                root.optJSONArray("shots");

        if (shotsJson == null ||
                shotsJson.length() == 0) {

            throw new Exception(
                    "analysis.json tidak memiliki shots");
        }

        List<Shot> shots =
                readShots(shotsJson);

        if (shots.isEmpty()) {
            throw new Exception(
                    "Tidak ada shot valid dari Pass 1");
        }

        /*
         * manual_split.txt adalah INPUT konfigurasi.
         * Tidak pernah ditulis oleh Pass 2.
         */
        File configFile =
                getConfigFile();

        Log.i(
                TAG,
                "Config: " +
                configFile.getAbsolutePath());

        for (Shot shot : shots) {

            applyManualConfig(
                    shot,
                    configFile);

            if ("split".equals(shot.layout)) {
                continue;
            }

            if (detectAutoSplit(shot)) {
                Log.i(TAG, "Shot " + shot.shotId + " -> AUTO SPLIT");
                continue;
            }

            /*
             * Pass 1 memberikan spatial grid.
             * Pass 2 mengubahnya menjadi satu
             * titik subject/crop yang stabil.
             */
            Point point =
                    estimateSubject(
                            shot.samples);

            writeSingleTrajectory(
                    shot,
                    point);
        }

        writeTrajectory(
                shots,
                trajectoryFile);

        if (!trajectoryFile.exists() ||
                trajectoryFile.length() == 0) {

            throw new Exception(
                    "trajectory.json gagal dibuat");
        }

        Log.i(
                TAG,
                "PASS2 DONE shots=" +
                shots.size());
    }

    private static List<Shot> readShots(
            JSONArray array)
            throws Exception {

        List<Shot> result =
                new ArrayList<>();

        for (int i = 0;
             i < array.length();
             i++) {

            JSONObject obj =
                    array.getJSONObject(i);

            Shot shot =
                    new Shot();

            shot.shotId =
                    obj.optInt(
                            "shotId",
                            i);

            shot.startMs =
                    obj.optLong(
                            "startMs",
                            0);

            /*
             * Pass 1 sekarang hanya menghasilkan
             * candidate layout "single".
             * Split ditentukan manual di Pass 2.
             */
            shot.layout =
                obj.optString(
                        "layout",
                        "single");

            JSONArray samples =
                    obj.optJSONArray(
                            "samples");

            if (samples != null) {

                for (int j = 0;
                     j < samples.length();
                     j++) {

                    JSONObject sample =
                            samples.getJSONObject(j);

                    JSONArray b =
                            sample.optJSONArray(
                                    "brightness");

                    JSONArray t =
                            sample.optJSONArray(
                                    "texture");

                    if (b == null ||
                            t == null) {
                        continue;
                    }

                    int n =
                            Math.min(
                                    b.length(),
                                    t.length());

                    if (n == 0) {
                        continue;
                    }

                    float[] brightness =
                            new float[n];

                    float[] texture =
                            new float[n];

                    for (int k = 0;
                         k < n;
                         k++) {

                        brightness[k] =
                                (float) b.optDouble(
                                        k,
                                        0);

                        texture[k] =
                                (float) t.optDouble(
                                        k,
                                        0);
                    }

                    shot.samples.add(
                            new FrameSample(
                                    sample.optLong(
                                            "t",
                                            shot.startMs),
                                    brightness,
                                    texture));
                }
            }

            result.add(shot);
        }

        return result;
    }

    private static void applyManualConfig(
            Shot shot,
            File configFile) {

        if (configFile == null || !configFile.exists()) {
            return;
        }

        try (BufferedReader reader =
                     new BufferedReader(
                             new FileReader(configFile))) {

            String line;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                String[] parts = line.split(",");

                // Format resmi: 0,2,5 = daftar Shot ID yang menjadi split.
                if (parts.length >= 2) {
                    for (String part : parts) {
                        try {
                            int id = Integer.parseInt(part.trim());

                            if (id == shot.shotId) {
                                shot.layout = "split";

                                Log.i(
                                        TAG,
                                        "Shot " + shot.shotId +
                                        " -> MANUAL SPLIT");

                                return;
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }

        } catch (Exception e) {
            Log.e(
                    TAG,
                    "Gagal membaca manual_split.txt",
                    e);
        }
    }

    private static boolean detectAutoSplit(Shot shot) {
        if (shot == null || shot.samples.size() < 3) return false;

        final int GRID_X = 12;
        final int GRID_Y = 8;

        double[] col = new double[GRID_X];
        int frames = 0;

        for (FrameSample sample : shot.samples) {
            if (sample.texture == null || sample.brightness == null) continue;

            int n = Math.min(
                    GRID_X * GRID_Y,
                    Math.min(sample.texture.length, sample.brightness.length));

            for (int i = 0; i < n; i++) {
                int x = i % GRID_X;
                int y = i / GRID_X;

                if (y == 0 || y == GRID_Y - 1) continue;

                double score =
                        sample.texture[i] * 0.75 +
                        sample.brightness[i] * 0.25;

                col[x] += Math.max(0, score);
            }

            frames++;
        }

        if (frames == 0) return false;

        for (int x = 0; x < GRID_X; x++) {
            col[x] /= frames;
        }

        double left = 0;
        double right = 0;
        double center = 0;

        for (int x = 0; x < GRID_X; x++) {
            if (x < 4) left += col[x];
            else if (x >= 8) right += col[x];
            else center += col[x];
        }

        double leftPeak = 0;
        double rightPeak = 0;
        int leftIndex = 0;
        int rightIndex = 0;

        for (int x = 1; x < 5; x++) {
            if (col[x] > leftPeak) {
                leftPeak = col[x];
                leftIndex = x;
            }
        }

        for (int x = 7; x < 11; x++) {
            if (col[x] > rightPeak) {
                rightPeak = col[x];
                rightIndex = x;
            }
        }

        if (leftPeak <= 0 || rightPeak <= 0) return false;

        double balance =
                Math.min(leftPeak, rightPeak) /
                Math.max(leftPeak, rightPeak);

        double leftPos =
                ((leftIndex - 1) * col[leftIndex - 1]
                + leftIndex * col[leftIndex]
                + (leftIndex + 1) * col[leftIndex + 1])
                / (col[leftIndex - 1]
                + col[leftIndex]
                + col[leftIndex + 1]);

        double rightPos =
                ((rightIndex - 1) * col[rightIndex - 1]
                + rightIndex * col[rightIndex]
                + (rightIndex + 1) * col[rightIndex + 1])
                / (col[rightIndex - 1]
                + col[rightIndex]
                + col[rightIndex + 1]);

        double separation =
                (rightPos - leftPos) / (double) GRID_X;

        double sideStrength =
                (left + right) /
                Math.max(0.0001, left + right + center);

        Log.i(TAG,
                "AUTO_SPLIT_DIAG shot=" + shot.shotId
                + " leftIndex=" + leftIndex
                + " rightIndex=" + rightIndex
                + " leftPeak=" + leftPeak
                + " rightPeak=" + rightPeak
                + " balance=" + balance
                + " separation=" + separation
                + " sideStrength=" + sideStrength
                + " col=" + java.util.Arrays.toString(col));

        if (balance < 0.55) return false;
        if (separation < 0.35) return false;
        if (sideStrength < 0.48) return false;

        shot.layout = "split";

        shot.topX =
                clamp(
                        (leftIndex + 0.5f) / GRID_X,
                        0.10f,
                        0.45f);

        shot.bottomX =
                clamp(
                        (rightIndex + 0.5f) / GRID_X,
                        0.55f,
                        0.90f);

        shot.topY = DEFAULT_Y;
        shot.bottomY = DEFAULT_Y;

        return true;
    }

    private static Point estimateSubject(
            List<FrameSample> samples) {

        if (samples.isEmpty()) {
            return new Point(
                    DEFAULT_X,
                    DEFAULT_Y,
                    DEFAULT_SIZE);
        }

        /*
         * Cari area paling aktif secara spasial.
         * Texture menjadi sinyal utama.
         * Brightness hanya pendukung.
         *
         * Grid Pass1 = 12 x 8.
         */
        final int gridX = 12;
        final int gridY = 8;

        double[] score =
                new double[gridX * gridY];

        int validFrames = 0;

        for (FrameSample sample : samples) {

            if (sample.texture == null ||
                    sample.brightness == null) {
                continue;
            }

            int n =
                    Math.min(
                            score.length,
                            Math.min(
                                    sample.texture.length,
                                    sample.brightness.length));

            for (int i = 0; i < n; i++) {

                /*
                 * Texture lebih dominan.
                 * Brightness mencegah area yang
                 * benar-benar datar dipilih.
                 */
                score[i] +=
                        sample.texture[i] * 0.75 +
                        sample.brightness[i] * 0.25;
            }

            validFrames++;
        }

        if (validFrames == 0) {
            return new Point(
                    DEFAULT_X,
                    DEFAULT_Y,
                    DEFAULT_SIZE);
        }

        /*
         * Hitung centroid berbobot, bukan sekadar
         * mengambil satu cell tertinggi.
         *
         * Ini mengurangi kecenderungan memilih
         * satu titik noise.
         */
        double totalWeight = 0;
        double weightedX = 0;
        double weightedY = 0;

        for (int y = 1; y < gridY - 1; y++) {

            for (int x = 0; x < gridX; x++) {

                int index =
                        y * gridX + x;

                double value =
                        score[index] /
                        validFrames;

                /*
                 * Kurangi kontribusi background
                 * yang sangat lemah.
                 */
                value =
                        Math.max(
                                0,
                                value);

                totalWeight += value;

                float cx =
                        (x + 0.5f) /
                        gridX;

                float cy =
                        (y + 0.5f) /
                        gridY;

                weightedX +=
                        cx * value;

                weightedY +=
                        cy * value;
            }
        }

        if (totalWeight <= 0.00001) {
            return new Point(
                    DEFAULT_X,
                    DEFAULT_Y,
                    DEFAULT_SIZE);
        }

        float centerX =
                (float)
                (weightedX / totalWeight);

        float centerY =
                (float)
                (weightedY / totalWeight);

        /*
         * Subject center guard:
         * hindari centroid jatuh terlalu dekat
         * edge akibat noise.
         */
        centerX =
                clamp(
                        centerX,
                        0.12f,
                        0.88f);

        centerY =
                clamp(
                        centerY,
                        0.15f,
                        0.85f);

        return new Point(
                centerX,
                centerY,
                DEFAULT_SIZE);
    }

    private static void writeSingleTrajectory(
            Shot shot,
            Point point) {

        /*
         * Point disimpan sementara sebagai
         * satu trajectory point per sample.
         *
         * Untuk setiap timestamp Pass3 akan
         * melakukan interpolasi.
         */
        shot.topX = point.x;
        shot.topY = point.y;
        shot.bottomX = point.x;
        shot.bottomY = point.y;

        /*
         * Simpan hasil subject pada samples
         * melalui nilai yang tidak mengubah
         * data asli Pass1.
         *
         * Layout tetap single.
         */
    }

    private static void writeTrajectory(
            List<Shot> shots,
            File outputFile)
            throws Exception {

        JSONObject root =
                new JSONObject();

        JSONArray shotsJson =
                new JSONArray();

        for (Shot shot : shots) {

            JSONObject shotObj =
                    new JSONObject();

            shotObj.put(
                    "shotId",
                    shot.shotId);

            shotObj.put(
                    "startMs",
                    shot.startMs);

            shotObj.put(
                    "layout",
                    shot.layout);

            if ("split".equals(
                    shot.layout)) {

                shotObj.put(
                        "topX",
                        shot.topX);

                shotObj.put(
                        "topY",
                        shot.topY);

                shotObj.put(
                        "bottomX",
                        shot.bottomX);

                shotObj.put(
                        "bottomY",
                        shot.bottomY);

            } else {

                /*
                 * Recompute subject trajectory
                 * sebagai titik per sample.
                 */
                JSONArray track =
                        new JSONArray();

                for (FrameSample sample :
                        shot.samples) {

                    Point point =
                            estimateSingleFrame(
                                    sample);

                    JSONObject p =
                            new JSONObject();

                    p.put(
                            "t",
                            sample.timeMs);

                    p.put(
                            "x",
                            point.x);

                    p.put(
                            "y",
                            point.y);

                    p.put(
                            "size",
                            point.size);

                    track.put(p);
                }

                if (track.length() == 0) {

                    JSONObject p =
                            new JSONObject();

                    p.put(
                            "t",
                            shot.startMs);

                    p.put(
                            "x",
                            DEFAULT_X);

                    p.put(
                            "y",
                            DEFAULT_Y);

                    p.put(
                            "size",
                            DEFAULT_SIZE);

                    track.put(p);
                }

                shotObj.put(
                        "track",
                        track);
            }

            shotsJson.put(
                    shotObj);
        }

        root.put(
                "version",
                2);

        root.put(
                "shots",
                shotsJson);

        File parent =
                outputFile.getParentFile();

        if (parent != null &&
                !parent.exists()) {

            parent.mkdirs();
        }

        try (FileWriter writer =
                     new FileWriter(
                             outputFile)) {

            writer.write(
                    root.toString(2));
        }

        Log.i(
                TAG,
                "Trajectory written: " +
                outputFile.getAbsolutePath());
    }

    private static Point estimateSingleFrame(
            FrameSample sample) {

        if (sample == null ||
                sample.texture == null ||
                sample.texture.length == 0) {

            return new Point(
                    DEFAULT_X,
                    DEFAULT_Y,
                    DEFAULT_SIZE);
        }

        int gridX = 12;
        int gridY = 8;

        double total = 0;
        double weightedX = 0;
        double weightedY = 0;

        int n =
                Math.min(
                        gridX * gridY,
                        Math.min(
                                sample.texture.length,
                                sample.brightness.length));

        for (int index = 0;
             index < n;
             index++) {

            int x =
                    index % gridX;

            int y =
                    index / gridX;

            if (y == 0 ||
                    y == gridY - 1) {
                continue;
            }

            float texture =
                    sample.texture[index];

            float brightness =
                    sample.brightness[index];

            double weight =
                    texture * 0.75 +
                    brightness * 0.25;

            if (weight <= 0) {
                continue;
            }

            float cx =
                    (x + 0.5f) /
                    gridX;

            float cy =
                    (y + 0.5f) /
                    gridY;

            total += weight;
            weightedX += cx * weight;
            weightedY += cy * weight;
        }

        if (total <= 0.00001) {

            return new Point(
                    DEFAULT_X,
                    DEFAULT_Y,
                    DEFAULT_SIZE);
        }

        float x =
                clamp(
                        (float)
                        (weightedX / total),
                        0.12f,
                        0.88f);

        float y =
                clamp(
                        (float)
                        (weightedY / total),
                        0.15f,
                        0.85f);

        return new Point(
                x,
                y,
                DEFAULT_SIZE);
    }

    private static float clamp(
            float value,
            float min,
            float max) {

        return Math.max(
                min,
                Math.min(
                        max,
                        value));
    }
}
