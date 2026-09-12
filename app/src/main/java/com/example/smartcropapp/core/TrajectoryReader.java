package com.example.smartcropapp.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class TrajectoryReader {

    public static class Point {
        public final float x;
        public final float y;
        public final float size;

        Point(float x, float y, float size) {
            this.x = x;
            this.y = y;
            this.size = size;
        }
    }

    public static class ShotResult {
        public final String layout;
        public final Point single;
        public final Point top;
        public final Point bottom;

        ShotResult(
                String layout,
                Point single,
                Point top,
                Point bottom) {

            this.layout = layout;
            this.single = single;
            this.top = top;
            this.bottom = bottom;
        }
    }

    private static class ShotData {

        long startMs;

        String layout;

        final List<Long> times =
                new ArrayList<>();

        final List<Point> points =
                new ArrayList<>();

        Point topPoint;
        Point bottomPoint;
    }

    private final List<ShotData> shots =
            new ArrayList<>();

    public static TrajectoryReader load(
            File trajectoryFile)
            throws Exception {

        TrajectoryReader reader =
                new TrajectoryReader();

        String content =
                new String(
                        Files.readAllBytes(
                                trajectoryFile.toPath()),
                        StandardCharsets.UTF_8);

        JSONObject root =
                new JSONObject(content);

        JSONArray shotsArr =
                root.optJSONArray("shots");

        if (shotsArr == null) {
            shotsArr = new JSONArray();
        }

        for (int i = 0;
             i < shotsArr.length();
             i++) {

            JSONObject shotObj =
                    shotsArr.getJSONObject(i);

            ShotData sd =
                    new ShotData();

            sd.startMs =
                    shotObj.optLong(
                            "startMs",
                            0);

            sd.layout =
                    normalizeLayout(
                            shotObj.optString(
                                    "layout",
                                    "single"));

            if ("split".equals(sd.layout)) {

                float topX =
                        clamp(
                                (float)
                                shotObj.optDouble(
                                        "topX",
                                        0.25),
                                0.05f,
                                0.95f);

                float topY =
                        clamp(
                                (float)
                                shotObj.optDouble(
                                        "topY",
                                        0.50),
                                0.05f,
                                0.95f);

                float bottomX =
                        clamp(
                                (float)
                                shotObj.optDouble(
                                        "bottomX",
                                        0.75),
                                0.05f,
                                0.95f);

                float bottomY =
                        clamp(
                                (float)
                                shotObj.optDouble(
                                        "bottomY",
                                        0.50),
                                0.05f,
                                0.95f);

                sd.topPoint =
                        new Point(
                                topX,
                                topY,
                                0.5f);

                sd.bottomPoint =
                        new Point(
                                bottomX,
                                bottomY,
                                0.5f);

            } else {

                JSONArray track =
                        shotObj.optJSONArray(
                                "track");

                if (track != null) {

                    for (int j = 0;
                         j < track.length();
                         j++) {

                        JSONObject p =
                                track.getJSONObject(j);

                        sd.times.add(
                                p.optLong(
                                        "t",
                                        0));

                        sd.points.add(
                                new Point(
                                        clamp(
                                                (float)
                                                p.optDouble(
                                                        "x",
                                                        0.5),
                                                0f,
                                                1f),

                                        clamp(
                                                (float)
                                                p.optDouble(
                                                        "y",
                                                        0.4),
                                                0f,
                                                1f),

                                        clamp(
                                                (float)
                                                p.optDouble(
                                                        "size",
                                                        0.3),
                                                0.05f,
                                                1f)));
                    }
                }

                if (sd.points.isEmpty()) {

                    sd.times.add(0L);

                    sd.points.add(
                            new Point(
                                    0.5f,
                                    0.4f,
                                    0.3f));
                }
            }

            reader.shots.add(sd);
        }

        if (reader.shots.isEmpty()) {

            ShotData fallback =
                    new ShotData();

            fallback.startMs = 0;
            fallback.layout =
                    "single";

            fallback.times.add(0L);

            fallback.points.add(
                    new Point(
                            0.5f,
                            0.4f,
                            0.3f));

            reader.shots.add(
                    fallback);
        }

        try {
            File diagDir = new File(
                    "/storage/emulated/0/Download/SmartReframe/diagnostics");
            diagDir.mkdirs();

            File diagFile = new File(
                    diagDir,
                    "trajectory_load_diag.txt");

            FileWriter fw = new FileWriter(diagFile, true);

            for (int i = 0; i < reader.shots.size(); i++) {
                ShotData sd = reader.shots.get(i);

                fw.write(
                        "SHOT id=" + i
                        + " startMs=" + sd.startMs
                        + " layout=" + sd.layout
                        + "\n");
            }

            fw.close();
        } catch (Exception ignored) {
        }

        return reader;
    }

    public ShotResult getShotAt(
            long timeUs) {

        long timeMs =
                timeUs / 1000L;

        ShotData active =
                shots.get(0);

        for (ShotData sd : shots) {

            if (sd.startMs <= timeMs) {
                active = sd;
            } else {
                break;
            }
        }

        if ("split".equals(
                active.layout)) {

            Point top =
                    active.topPoint != null
                            ? active.topPoint
                            : new Point(
                                    0.25f,
                                    0.5f,
                                    0.5f);

            Point bottom =
                    active.bottomPoint != null
                            ? active.bottomPoint
                            : new Point(
                                    0.75f,
                                    0.5f,
                                    0.5f);

            return new ShotResult(
                    "split",
                    null,
                    top,
                    bottom);
        }

        Point point =
                interpolate(
                        active,
                        timeMs);

        return new ShotResult(
                "single",
                point,
                null,
                null);
    }

    private Point interpolate(ShotData sd, long timeMs) {
        if (sd.points.isEmpty()) {
            return new Point(0.5f, 0.4f, 0.3f);
        }

        // Find two adjacent points: before and after timeMs
        Point pointBefore = sd.points.get(0);
        Point pointAfter = null;
        long timeBefore = sd.times.get(0);
        long timeAfter = -1L;

        for (int i = 0; i < sd.points.size(); i++) {
            if (sd.times.get(i) <= timeMs) {
                pointBefore = sd.points.get(i);
                timeBefore = sd.times.get(i);
            } else {
                pointAfter = sd.points.get(i);
                timeAfter = sd.times.get(i);
                break;
            }
        }

        // If no point after timeMs, return last point before
        if (pointAfter == null) {
            return pointBefore;
        }

        // Linear interpolation between pointBefore and pointAfter
        long deltaTime = timeAfter - timeBefore;
        if (deltaTime <= 0) {
            return pointBefore;
        }

        float alpha = (float)(timeMs - timeBefore) / deltaTime;
        alpha = Math.max(0f, Math.min(1f, alpha));

        float interpX = pointBefore.x + (pointAfter.x - pointBefore.x) * alpha;
        float interpY = pointBefore.y + (pointAfter.y - pointBefore.y) * alpha;
        float interpSize = pointBefore.size + (pointAfter.size - pointBefore.size) * alpha;

        return new Point(interpX, interpY, interpSize);
    }

    private static String normalizeLayout(
            String layout) {

        if ("split".equalsIgnoreCase(
                layout)) {

            return "split";
        }

        return "single";
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
