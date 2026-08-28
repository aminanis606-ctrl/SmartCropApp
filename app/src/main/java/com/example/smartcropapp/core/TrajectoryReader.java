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

        Point(
                float x,
                float y,
                float size) {

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

        List<Long> times =
                new ArrayList<>();

        List<Point> points =
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

            JSONObject obj =
                    shotsArr.getJSONObject(i);

            ShotData sd =
                    new ShotData();

            sd.startMs =
                    obj.optLong(
                            "startMs",
                            0);

            sd.layout =
                    obj.optString(
                            "layout",
                            "single");

            if ("split".equals(
                    sd.layout)) {

                float topX =
                        (float) obj.optDouble(
                                "topX",
                                0.25);

                float topY =
                        (float) obj.optDouble(
                                "topY",
                                0.50);

                float bottomX =
                        (float) obj.optDouble(
                                "bottomX",
                                0.75);

                float bottomY =
                        (float) obj.optDouble(
                                "bottomY",
                                0.50);

                sd.topPoint =
                        new Point(
                                topX,
                                topY,
                                0.50f);

                sd.bottomPoint =
                        new Point(
                                bottomX,
                                bottomY,
                                0.50f);

            } else {

                JSONArray track =
                        obj.optJSONArray(
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
                                        (float) p.optDouble(
                                                "x",
                                                0.5),

                                        (float) p.optDouble(
                                                "y",
                                                0.4),

                                        (float) p.optDouble(
                                                "size",
                                                0.3)));
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
            fallback.layout = "single";

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

            return new ShotResult(
                    "split",
                    null,
                    active.topPoint,
                    active.bottomPoint);
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

    private Point interpolate(
            ShotData sd,
            long timeMs) {

        if (sd.points.size() == 1) {
            return sd.points.get(0);
        }

        Point previous =
                sd.points.get(0);

        long previousTime =
                sd.times.get(0);

        for (int i = 0;
             i < sd.points.size();
             i++) {

            long currentTime =
                    sd.times.get(i);

            Point current =
                    sd.points.get(i);

            if (currentTime >= timeMs) {

                if (i == 0) {
                    return current;
                }

                long span =
                        currentTime
                                - previousTime;

                float ratio =
                        span <= 0
                                ? 0f
                                : (float)
                                (timeMs
                                        - previousTime)
                                / span;

                ratio =
                        Math.max(
                                0f,
                                Math.min(
                                        1f,
                                        ratio));

                return new Point(

                        previous.x
                                + (current.x
                                - previous.x)
                                * ratio,

                        previous.y
                                + (current.y
                                - previous.y)
                                * ratio,

                        previous.size
                                + (current.size
                                - previous.size)
                                * ratio);
            }

            previous =
                    current;

            previousTime =
                    currentTime;
        }

        return sd.points.get(
                sd.points.size() - 1);
    }
}
