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
        float[] contrast;
        float[] edge;
        float[] verticalEdge;
        float[] horizontalEdge;

        FrameSample(
                long timeMs,
                float[] brightness,
                float[] texture,
                float[] contrast,
                float[] edge,
                float[] verticalEdge,
                float[] horizontalEdge) {
            this.timeMs = timeMs;
            this.brightness = brightness;
            this.texture = texture;
            this.contrast = contrast;
            this.edge = edge;
            this.verticalEdge = verticalEdge;
            this.horizontalEdge = horizontalEdge;
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
            File trajectoryFile,
            String videoId)
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
                    configFile,
                    videoId);

            if ("split".equals(shot.layout)) {
                estimateSplitSubjects(shot);
                continue;
            }

            if (detectAutoSplit(shot)) {
                Log.i(TAG, "Shot " + shot.shotId + " -> AUTO SPLIT");
                estimateSplitSubjects(shot);
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

                    JSONArray c =
                            sample.optJSONArray(
                                    "contrast");

                    JSONArray e =
                            sample.optJSONArray(
                                    "edge");

                    JSONArray ve =
                            sample.optJSONArray(
                                    "verticalEdge");

                    JSONArray he =
                            sample.optJSONArray(
                                    "horizontalEdge");

                    if (b == null || t == null) {
                        continue;
                    }

                    int n = Math.min(
                            b.length(),
                            t.length());

                    if (n == 0) {
                        continue;
                    }

                    float[] brightness =
                            new float[n];

                    float[] texture =
                            new float[n];

                    float[] contrast =
                            new float[n];

                    float[] edge =
                            new float[n];

                    float[] verticalEdge =
                            new float[n];

                    float[] horizontalEdge =
                            new float[n];

                    for (int k = 0;
                         k < n;
                         k++) {

                        brightness[k] =
                                (float) b.optDouble(k, 0);

                        texture[k] =
                                (float) t.optDouble(k, 0);

                        contrast[k] =
                                c == null
                                        ? 0f
                                        : (float) c.optDouble(k, 0);

                        edge[k] =
                                e == null
                                        ? 0f
                                        : (float) e.optDouble(k, 0);

                        verticalEdge[k] =
                                ve == null
                                        ? 0f
                                        : (float) ve.optDouble(k, 0);

                        horizontalEdge[k] =
                                he == null
                                        ? 0f
                                        : (float) he.optDouble(k, 0);
                    }

                    shot.samples.add(
                            new FrameSample(
                                    sample.optLong(
                                            "t",
                                            shot.startMs),
                                    brightness,
                                    texture,
                                    contrast,
                                    edge,
                                    verticalEdge,
                                    horizontalEdge));
                }
            }

            result.add(shot);
        }

        return result;
    }

    private static void applyManualConfig(
            Shot shot,
            File configFile,
            String videoId) {

        if (configFile == null || !configFile.exists()
                || videoId == null) return;

        try (BufferedReader reader = new BufferedReader(
                new FileReader(configFile))) {

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.isEmpty() || line.startsWith("#")
                        || !line.contains("=")) continue;

                String[] cfg = line.split("=", 2);
                if (!videoId.equals(cfg[0].trim())) continue;

                String[] ids = cfg[1].split(",");

                for (String value : ids) {
                    if (Integer.parseInt(value.trim()) == shot.shotId) {
                        shot.layout = "split";
                        Log.i(TAG, "Shot " + shot.shotId
                                + " -> MANUAL SPLIT video=" + videoId);
                        return;
                    }
                }

                return;
            }
        } catch (Exception e) {
            Log.e(TAG, "Gagal membaca manual_split.txt", e);
        }
    }

    private static boolean detectAutoSplit(Shot shot) {
        if (shot == null || shot.samples.size() < 3) return false;

        final int GRID_X = 12;
        final int GRID_Y = 8;

        double[] col = new double[GRID_X];
        int frames = 0;
        int prevLeftPeak = -1;
        int prevRightPeak = -1;
        int leftMovingSteps = 0;
        int rightMovingSteps = 0;

        for (FrameSample sample : shot.samples) {
            if (sample.texture == null ||
                sample.brightness == null ||
                sample.contrast == null ||
                sample.verticalEdge == null ||
                sample.horizontalEdge == null) {
                continue;
            }

            int n = Math.min(
                    GRID_X * GRID_Y,
                    Math.min(
                            Math.min(sample.texture.length, sample.brightness.length),
                            Math.min(
                                    Math.min(sample.contrast.length, sample.verticalEdge.length),
                                    sample.horizontalEdge.length)));

            double[] frameCol = new double[GRID_X];

            for (int i = 0; i < n; i++) {
                int x = i % GRID_X;
                int y = i / GRID_X;

                if (y == 0 || y == GRID_Y - 1) continue;

                double score =
                        sample.texture[i] * 0.25 +
                        sample.verticalEdge[i] * 0.30 +
                        sample.horizontalEdge[i] * 0.15 +
                        sample.contrast[i] * 0.25 +
                        sample.brightness[i] * 0.05;

                score = Math.max(0, score);
                col[x] += score;
                frameCol[x] += score;
            }

            int leftPeakX = 1;
            int rightPeakX = 7;
            double leftPeak = 0.0;
            double rightPeak = 0.0;

            for (int x = 1; x <= 4; x++) {
                if (frameCol[x] > leftPeak) {
                    leftPeak = frameCol[x];
                    leftPeakX = x;
                }
            }

            for (int x = 7; x <= 10; x++) {
                if (frameCol[x] > rightPeak) {
                    rightPeak = frameCol[x];
                    rightPeakX = x;
                }
            }

            if (prevLeftPeak >= 0 && leftPeakX != prevLeftPeak) {
                leftMovingSteps++;
            }

            if (prevRightPeak >= 0 && rightPeakX != prevRightPeak) {
                rightMovingSteps++;
            }

            prevLeftPeak = leftPeakX;
            prevRightPeak = rightPeakX;

            frames++;
        }

        if (frames == 0) return false;

        Log.i(TAG,
                "AUTO_SPLIT_TEMPORAL_DIAG shot=" + shot.shotId
                        + " leftMovingSteps=" + leftMovingSteps
                        + " rightMovingSteps=" + rightMovingSteps
                        + " frames=" + frames);

        for (int x = 0; x < GRID_X; x++) {
            col[x] /= frames;
        }

        double leftPeak = 0;
        double rightPeak = 0;
        int leftIndex = 0;
        int rightIndex = 0;

        // Left candidate: genuinely left side.
        for (int x = 1; x <= 4; x++) {
            if (col[x] > leftPeak) {
                leftPeak = col[x];
                leftIndex = x;
            }
        }

        // Right candidate: genuinely right side.
        for (int x = 7; x <= 10; x++) {
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
                ((leftIndex - 1) * col[leftIndex - 1] +
                 leftIndex * col[leftIndex] +
                 (leftIndex + 1) * col[leftIndex + 1]) /
                Math.max(0.0001,
                        col[leftIndex - 1] +
                        col[leftIndex] +
                        col[leftIndex + 1]);

        double rightPos =
                ((rightIndex - 1) * col[rightIndex - 1] +
                 rightIndex * col[rightIndex] +
                 (rightIndex + 1) * col[rightIndex + 1]) /
                Math.max(0.0001,
                        col[rightIndex - 1] +
                        col[rightIndex] +
                        col[rightIndex + 1]);

        double separation =
                (rightPos - leftPos) / (double) GRID_X;

        double center = 0;
        for (int x = 4; x <= 7; x++) {
            center += col[x];
        }
        center /= 4.0;

        double peakVsCenter =
                Math.min(leftPeak, rightPeak) /
                Math.max(0.0001, center);

        // Require a genuine valley between the two candidates.
        double valley = Double.MAX_VALUE;
        for (int x = leftIndex + 1; x < rightIndex; x++) {
            valley = Math.min(valley, col[x]);
        }

        double valleyRatio =
                valley / Math.max(0.0001, Math.min(leftPeak, rightPeak));

        Log.i(TAG,
                "AUTO_SPLIT_DIAG shot=" + shot.shotId
                        + " leftIndex=" + leftIndex
                        + " rightIndex=" + rightIndex
                        + " leftPeak=" + leftPeak
                        + " rightPeak=" + rightPeak
                        + " balance=" + balance
                        + " separation=" + separation
                        + " peakVsCenter=" + peakVsCenter
                        + " valleyRatio=" + valleyRatio
                        + " col=" + java.util.Arrays.toString(col));

        // Both sides must contain comparable strong structures.
        if (balance < 0.55) return false;

        // Subjects must be spatially separated.
        if (separation < 0.35) return false;

        // Each side must beat the central region.
        if (peakVsCenter < 1.15) return false;

        // A single broad central subject should not become two subjects.
        if (valleyRatio > 0.92) return false;

        shot.layout = "split";

        shot.topX = clamp(
                (rightIndex + 0.5f) / GRID_X - 0.18f,
                0.20f,
                0.85f);

        shot.bottomX = clamp(
                (leftIndex + 0.5f) / GRID_X + 0.18f,
                0.15f,
                0.80f);

        shot.topY = 0.60f;
        shot.bottomY = 0.60f;

        return true;
    }

    /*
     * SPLIT subject localization:
     *
     * RIGHT half -> top panel
     * LEFT half  -> bottom panel
     *
     * Setiap sisi dihitung secara independen agar
     * dua subject tidak kembali menjadi satu centroid.
     */
    /*
     * SPLIT subject localization.
     *
     * LEFT  peak -> bottom panel
     * RIGHT peak -> top panel
     *
     * Localization hanya mengambil area lokal di sekitar
     * peak masing-masing sisi agar background tidak ikut
     * menarik centroid ke tengah.
     */
    /*
     * SPLIT subject localization.
     *
     * IMPORTANT:
     * LEFT dan RIGHT dianalisis sebagai dua ROI yang
     * benar-benar independen.
     *
     * LEFT  ROI  = columns 0..5 -> BOTTOM panel
     * RIGHT ROI  = columns 6..11 -> TOP panel
     *
     * Tidak ada centroid global.
     */
    private static void estimateSplitSubjects(
            Shot shot) {

        if (shot == null ||
                shot.samples == null ||
                shot.samples.isEmpty()) {
            return;
        }

        final int GRID_X = 12;
        final int GRID_Y = 8;

        /*
         * Dua score map terpisah.
         * leftScore tidak pernah menerima kolom kanan.
         * rightScore tidak pernah menerima kolom kiri.
         */
        double[] leftScore =
                new double[GRID_X * GRID_Y];

        double[] rightScore =
                new double[GRID_X * GRID_Y];

        int validFrames = 0;

        for (FrameSample sample : shot.samples) {

            if (sample == null ||
                    sample.edge == null ||
                    sample.contrast == null ||
                    sample.verticalEdge == null ||
                    sample.brightness == null) {
                continue;
            }

            int n = Math.min(
                    GRID_X * GRID_Y,
                    Math.min(
                            sample.edge.length,
                            Math.min(
                                    sample.contrast.length,
                                    Math.min(
                                            sample.verticalEdge.length,
                                            sample.brightness.length))));

            for (int i = 0; i < n; i++) {

                int x = i % GRID_X;
                int y = i / GRID_X;

                if (y == 0 ||
                        y == GRID_Y - 1) {
                    continue;
                }

                double value =
                        sample.edge[i] * 0.45 +
                        sample.verticalEdge[i] * 0.25 +
                        sample.contrast[i] * 0.25 +
                        sample.brightness[i] * 0.05;

                value = Math.max(0.0, value);

                if (x < 6) {
                    leftScore[i] += value;
                } else {
                    rightScore[i] += value;
                }
            }

            validFrames++;
        }

        if (validFrames == 0) {
            return;
        }

        /*
         * Normalisasi kedua ROI secara independen.
         */
        for (int i = 0; i < GRID_X * GRID_Y; i++) {
            leftScore[i] /= validFrames;
            rightScore[i] /= validFrames;
        }

        /*
         * Cari peak TERKUAT hanya di masing-masing ROI.
         */
        int leftPeakX = 0;
        int leftPeakY = 1;
        double leftPeak = 0.0;

        int rightPeakX = 6;
        int rightPeakY = 1;
        double rightPeak = 0.0;

        for (int y = 1; y < GRID_Y - 1; y++) {

            for (int x = 0; x < 6; x++) {

                int index = y * GRID_X + x;

                if (leftScore[index] > leftPeak) {
                    leftPeak = leftScore[index];
                    leftPeakX = x;
                    leftPeakY = y;
                }
            }

            for (int x = 6; x < GRID_X; x++) {

                int index = y * GRID_X + x;

                if (rightScore[index] > rightPeak) {
                    rightPeak = rightScore[index];
                    rightPeakX = x;
                    rightPeakY = y;
                }
            }
        }

        if (leftPeak <= 0.0 ||
                rightPeak <= 0.0) {
            return;
        }

        /*
         * Local centroid di sekitar peak.
         *
         * Radius 1 cell.
         *
         * LEFT tetap dibatasi x < 6.
         * RIGHT tetap dibatasi x >= 6.
         *
         * Jadi kedua analisis tidak mungkin
         * mengambil data dari ROI lawannya.
         */
        double leftWeight = 0.0;
        double leftX = 0.0;
        double leftY = 0.0;

        double rightWeight = 0.0;
        double rightX = 0.0;
        double rightY = 0.0;

        for (int y = 1; y < GRID_Y - 1; y++) {

            for (int x = 0; x < 6; x++) {

                if (Math.abs(x - leftPeakX) > 1 ||
                        Math.abs(y - leftPeakY) > 1) {
                    continue;
                }

                int index = y * GRID_X + x;
                double value = leftScore[index];

                if (value <= 0.0) continue;

                float cx =
                        (x + 0.5f) / GRID_X;

                float cy =
                        (y + 0.5f) / GRID_Y;

                leftWeight += value;
                leftX += cx * value;
                leftY += cy * value;
            }

            for (int x = 6; x < GRID_X; x++) {

                if (Math.abs(x - rightPeakX) > 1 ||
                        Math.abs(y - rightPeakY) > 1) {
                    continue;
                }

                int index = y * GRID_X + x;
                double value = rightScore[index];

                if (value <= 0.0) continue;

                float cx =
                        (x + 0.5f) / GRID_X;

                float cy =
                        (y + 0.5f) / GRID_Y;

                rightWeight += value;
                rightX += cx * value;
                rightY += cy * value;
            }
        }

        if (leftWeight <= 0.00001 ||
                rightWeight <= 0.00001) {
            return;
        }

        float leftCenterX =
                (float) (leftX / leftWeight);

        float leftCenterY =
                (float) (leftY / leftWeight);

        float rightCenterX =
                (float) (rightX / rightWeight);

        float rightCenterY =
                (float) (rightY / rightWeight);

        leftCenterX = clamp(
                leftCenterX,
                0.08f,
                0.49f);

        rightCenterX = clamp(
                rightCenterX,
                0.51f,
                0.92f);

        leftCenterY = clamp(
                leftCenterY,
                0.15f,
                0.85f);

        rightCenterY = clamp(
                rightCenterY,
                0.15f,
                0.85f);

        /*
         * RIGHT subject -> TOP panel.
         * LEFT subject  -> BOTTOM panel.
         *
         * Offset ke arah tengah frame:
         *
         * right -> crop center sedikit ke kiri
         * left  -> crop center sedikit ke kanan
         */
        shot.topX = clamp(
                rightCenterX,
                0.20f,
                0.85f);

        shot.topY = 0.60f;

        shot.bottomX = clamp(
                leftCenterX,
                0.15f,
                0.80f);

        shot.bottomY = 0.60f;

        Log.i(
                TAG,
                "SPLIT_ROI shot=" +
                shot.shotId +
                " LEFT_peak=(" +
                leftPeakX + "," +
                leftPeakY +
                ") LEFT_center=(" +
                leftCenterX + "," +
                leftCenterY +
                ") RIGHT_peak=(" +
                rightPeakX + "," +
                rightPeakY +
                ") RIGHT_center=(" +
                rightCenterX + "," +
                rightCenterY +
                ") topX=" +
                shot.topX +
                " bottomX=" +
                shot.bottomX);
    }

    private static Point estimateSubject(
            List<FrameSample> samples) {
        if (samples.isEmpty()) {
            return new Point(
                    DEFAULT_X,
                    DEFAULT_Y,
                    DEFAULT_SIZE);
        }

        final int gridX = 12;
        final int gridY = 8;

        double[] score = new double[gridX * gridY];
        int validFrames = 0;

        for (FrameSample sample : samples) {
            if (sample == null ||
                    sample.edge == null ||
                    sample.contrast == null ||
                    sample.verticalEdge == null) {
                continue;
            }

            int n = Math.min(
                    score.length,
                    Math.min(
                            sample.edge.length,
                            Math.min(
                                    sample.contrast.length,
                                    sample.verticalEdge.length)));

            for (int i = 0; i < n; i++) {
                /*
                 * V2-B:
                 * Edge = sinyal utama struktur.
                 * Vertical edge = pendukung bentuk tubuh.
                 * Contrast = pemisah subjek/background.
                 * Brightness hanya sedikit membantu.
                 *
                 * Tidak ada aturan khusus untuk neon.
                 */
                double value =
                        sample.edge[i] * 0.45 +
                        sample.verticalEdge[i] * 0.25 +
                        sample.contrast[i] * 0.25 +
                        sample.brightness[i] * 0.05;

                score[i] += Math.max(0.0, value);
            }

            validFrames++;
        }

        if (validFrames == 0) {
            return new Point(
                    DEFAULT_X,
                    DEFAULT_Y,
                    DEFAULT_SIZE);
        }

        double totalWeight = 0;
        double weightedX = 0;
        double weightedY = 0;

        for (int y = 1; y < gridY - 1; y++) {
            for (int x = 0; x < gridX; x++) {
                int index = y * gridX + x;

                double value = score[index] / validFrames;
                value = Math.max(0, value);

                totalWeight += value;

                float cx = (x + 0.5f) / gridX;
                float cy = (y + 0.5f) / gridY;

                weightedX += cx * value;
                weightedY += cy * value;
            }
        }

        if (totalWeight <= 0.00001) {
            return new Point(
                    DEFAULT_X,
                    DEFAULT_Y,
                    DEFAULT_SIZE);
        }

        float centerX =
                (float) (weightedX / totalWeight);
        float centerY =
                (float) (weightedY / totalWeight);

        /*
         * Subject center guard:
         * hindari centroid jatuh terlalu dekat
         * edge akibat noise.
         */
        centerX = clamp(
                centerX,
                0.12f,
                0.88f);

        // Recenter SINGLE subject toward the portrait frame center.
        centerX += (0.50f - centerX) * 0.55f;

        centerY = clamp(
                centerY,
                0.15f,
                0.85f);

        float x = centerX;
        float y = centerY;

        return new Point(
                x,
                y,
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
                            estimateSubject(
                                    shot.samples);

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
                sample.edge == null ||
                sample.edge.length == 0 ||
                sample.contrast == null ||
                sample.verticalEdge == null) {
            return new Point(
                    DEFAULT_X,
                    DEFAULT_Y,
                    DEFAULT_SIZE);
        }

        final int gridX = 12;
        final int gridY = 8;

        double total = 0;
        double weightedX = 0;
        double weightedY = 0;

        int n = Math.min(
                gridX * gridY,
                Math.min(
                        sample.edge.length,
                        Math.min(
                                sample.contrast.length,
                                sample.verticalEdge.length)));

        for (int index = 0; index < n; index++) {
            int x = index % gridX;
            int y = index / gridX;

            if (y == 0 || y == gridY - 1) {
                continue;
            }

            double weight =
                    sample.edge[index] * 0.45 +
                    sample.verticalEdge[index] * 0.25 +
                    sample.contrast[index] * 0.25 +
                    sample.brightness[index] * 0.05;

            if (weight <= 0) {
                continue;
            }

            float cx = (x + 0.5f) / gridX;
            float cy = (y + 0.5f) / gridY;

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

        float x = clamp(
                (float) (weightedX / total),
                0.12f,
                0.88f);

        float y = clamp(
                (float) (weightedY / total),
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
