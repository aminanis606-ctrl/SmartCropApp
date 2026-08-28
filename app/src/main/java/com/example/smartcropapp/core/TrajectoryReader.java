package com.example.smartcropapp.core;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class TrajectoryReader {

    public static class Point {
        public final float x, y, size;
        Point(float x, float y, float size) { this.x = x; this.y = y; this.size = size; }
    }

    public static class ShotResult {
        public final String layout; // "single" atau "split"
        public final Point single;      // dipakai jika layout == "single"
        public final Point top;         // dipakai jika layout == "split"
        public final Point bottom;      // dipakai jika layout == "split"

        ShotResult(String layout, Point single, Point top, Point bottom) {
            this.layout = layout; this.single = single; this.top = top; this.bottom = bottom;
        }
    }

    private static class ShotData {
        long startMs;
        String layout;
        List<long[]> trackTimes = new ArrayList<>(); // unused placeholder
        List<Long> times = new ArrayList<>();
        List<Point> points = new ArrayList<>();
        Point topPoint;
        Point bottomPoint;
    }

    private final List<ShotData> shots = new ArrayList<>();

    public static TrajectoryReader load(File trajectoryFile) throws Exception {
        TrajectoryReader reader = new TrajectoryReader();
        String content = new String(Files.readAllBytes(trajectoryFile.toPath()));
        JSONObject root = new JSONObject(content);
        JSONArray shotsArr = root.getJSONArray("shots");

        for (int i = 0; i < shotsArr.length(); i++) {
            JSONObject shotObj = shotsArr.getJSONObject(i);
            ShotData sd = new ShotData();
            sd.startMs = shotObj.optLong("startMs", 0);
            sd.layout = shotObj.optString("layout", "single");

            if ("split".equals(sd.layout)) {
                sd.topPoint = new Point(
                        (float) shotObj.optDouble("topX", 0.25),
                        (float) shotObj.optDouble("topY", 0.5),
                        0.5f);
                sd.bottomPoint = new Point(
                        (float) shotObj.optDouble("bottomX", 0.75),
                        (float) shotObj.optDouble("bottomY", 0.5),
                        0.5f);
            } else {
                JSONArray track = shotObj.getJSONArray("track");
                for (int j = 0; j < track.length(); j++) {
                    JSONObject p = track.getJSONObject(j);
                    sd.times.add(p.optLong("t", 0));
                    sd.points.add(new Point(
                            (float) p.optDouble("x", 0.5),
                            (float) p.optDouble("y", 0.4),
                            (float) p.optDouble("size", 0.3)));
                }
                if (sd.points.isEmpty()) {
                    sd.times.add(0L);
                    sd.points.add(new Point(0.5f, 0.4f, 0.3f));
                }
            }
            reader.shots.add(sd);
        }

        if (reader.shots.isEmpty()) {
            ShotData fallback = new ShotData();
            fallback.startMs = 0;
            fallback.layout = "single";
            fallback.times.add(0L);
            fallback.points.add(new Point(0.5f, 0.4f, 0.3f));
            reader.shots.add(fallback);
        }

        return reader;
    }

    public ShotResult getShotAt(long timeUs) {
        long timeMs = timeUs / 1000;
        ShotData active = shots.get(0);
        for (ShotData sd : shots) {
            if (sd.startMs <= timeMs) active = sd;
            else break;
        }

        if ("split".equals(active.layout)) {
            return new ShotResult("split", null, active.topPoint, active.bottomPoint);
        } else {
            Point p = interpolate(active, timeMs);
            return new ShotResult("single", p, null, null);
        }
    }

    private Point interpolate(ShotData sd, long timeMs) {
        if (sd.points.size() == 1) return sd.points.get(0);

        Point prev = sd.points.get(0);
        long prevT = sd.times.get(0);
        for (int i = 0; i < sd.points.size(); i++) {
            long t = sd.times.get(i);
            Point p = sd.points.get(i);
            if (t >= timeMs) {
                if (i == 0) return p;
                long span = t - prevT;
                float ratio = span <= 0 ? 0 : (float) (timeMs - prevT) / span;
                return new Point(
                        prev.x + (p.x - prev.x) * ratio,
                        prev.y + (p.y - prev.y) * ratio,
                        prev.size + (p.size - prev.size) * ratio);
            }
            prev = p;
            prevT = t;
        }
        return sd.points.get(sd.points.size() - 1);
    }
}
