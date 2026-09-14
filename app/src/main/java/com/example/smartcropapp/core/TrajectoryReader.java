package com.example.smartcropapp.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
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
        List<Long> splitTimes = new ArrayList<>();
        List<Point> topPoints = new ArrayList<>();
        List<Point> bottomPoints = new ArrayList<>();
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

                JSONArray splitTrack =
                        shotObj.optJSONArray(
                                "track");

                if (splitTrack != null) {
                    for (int j = 0;
                         j < splitTrack.length();
                         j++) {

                        JSONObject frame =
                                splitTrack.optJSONObject(j);
                        if (frame == null) continue;

                        sd.splitTimes.add(
                                frame.optLong("t", 0));

                        sd.topPoints.add(
                                new Point(
                                        clamp(
                                                (float) frame.optDouble("topX", topX),
                                                0f,
                                                1f),
                                        clamp(
                                                (float) frame.optDouble("topY", topY),
                                                0f,
                                                1f),
                                        0.5f));

                        sd.bottomPoints.add(
                                new Point(
                                        clamp(
                                                (float) frame.optDouble("botX", bottomX),
                                                0f,
                                                1f),
                                        clamp(
                                                (float) frame.optDouble("botY", bottomY),
                                                0f,
                                                1f),
                                        0.5f));
                    }
                }

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

            Point top = interpolateSplit(
                    active,
                    timeMs,
                    true);

            Point bottom = interpolateSplit(
                    active,
                    timeMs,
                    false);

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

    private Point interpolateSplit(
            ShotData sd,
            long timeMs,
            boolean top) {

        List<Long> times = sd.splitTimes;
        List<Point> points =
                top ? sd.topPoints : sd.bottomPoints;

        if (times.isEmpty() || points.isEmpty()) {
            return top
                    ? (sd.topPoint != null
                        ? sd.topPoint
                        : new Point(0.25f, 0.5f, 0.5f))
                    : (sd.bottomPoint != null
                        ? sd.bottomPoint
                        : new Point(0.75f, 0.5f, 0.5f));
        }

        if (timeMs <= times.get(0)) {
            return points.get(0);
        }

        int last = times.size() - 1;

        if (timeMs >= times.get(last)) {
            return points.get(last);
        }

        for (int i = 1; i < times.size(); i++) {
            long t1 = times.get(i);

            if (timeMs <= t1) {
                long t0 = times.get(i - 1);

                Point p0 = points.get(i - 1);
                Point p1 = points.get(i);

                long deltaTime = t1 - t0;

                if (deltaTime <= 0) {
                    return p0;
                }

                float alpha =
                        (float)(timeMs - t0) / deltaTime;

                alpha =
                        Math.max(
                                0f,
                                Math.min(1f, alpha));

                return new Point(
                        p0.x + (p1.x - p0.x) * alpha,
                        p0.y + (p1.y - p0.y) * alpha,
                        p0.size
                                + (p1.size - p0.size) * alpha);
            }
        }

        return points.get(last);
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
