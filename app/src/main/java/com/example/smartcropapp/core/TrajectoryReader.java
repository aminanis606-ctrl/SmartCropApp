package com.example.smartcropapp.core;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class TrajectoryReader {

    public static class Point {
        public final long timestampMs;
        public final float x, y, size;
        Point(long t, float x, float y, float size) {
            this.timestampMs = t; this.x = x; this.y = y; this.size = size;
        }
    }

    private final List<Point> points = new ArrayList<>();

    public static TrajectoryReader load(File trajectoryFile) throws Exception {
        TrajectoryReader reader = new TrajectoryReader();
        String content = new String(Files.readAllBytes(trajectoryFile.toPath()));
        JSONObject root = new JSONObject(content);
        
        // Mengakomodasi struktur dari Pass 2 optimization atau faces mentah Pass 1
        JSONArray arr = root.has("smoothedTrajectory") ? 
                        root.getJSONArray("smoothedTrajectory") : 
                        root.optJSONArray("faces");
                        
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                reader.points.add(new Point(
                        o.optLong("t", 0),
                        (float) o.optDouble("x", o.optDouble("cropX", 0.5)),
                        (float) o.optDouble("y", o.optDouble("cropY", 0.4)),
                        (float) o.optDouble("size", o.optDouble("width", 0.3))
                ));
            }
        }
        
        if (reader.points.isEmpty()) {
            reader.points.add(new Point(0, 0.5f, 0.4f, 0.3f));
        }
        return reader;
    }

    public Point getPositionAt(long timeUs) {
        long timeMs = timeUs / 1000;
        if (points.size() == 1) return points.get(0);

        Point prev = points.get(0);
        for (Point p : points) {
            if (p.timestampMs >= timeMs) {
                if (p == prev) return p;
                long span = p.timestampMs - prev.timestampMs;
                float t = span <= 0 ? 0 : (float) (timeMs - prev.timestampMs) / span;
                return new Point(
                        timeMs,
                        prev.x + (p.x - prev.x) * t,
                        prev.y + (p.y - prev.y) * t,
                        prev.size + (p.size - prev.size) * t
                );
            }
            prev = p;
        }
        return points.get(points.size() - 1);
    }
}
