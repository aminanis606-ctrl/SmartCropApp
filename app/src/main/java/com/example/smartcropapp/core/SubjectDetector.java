package com.example.smartcropapp.core;

import android.graphics.Bitmap;
import android.graphics.PointF;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseDetection;
import com.google.mlkit.vision.pose.PoseDetector;
import com.google.mlkit.vision.pose.PoseLandmark;
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class SubjectDetector {

    public static final class Subject {
        public final float x;
        public final float y;
        public final float width;
        public final float height;
        public final float areaScore;
        public final int trackingId;

        public Subject(
                float x,
                float y,
                float width,
                float height,
                float areaScore,
                int trackingId) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.areaScore = areaScore;
            this.trackingId = trackingId;
        }
    }

    private final PoseDetector detector;

    public SubjectDetector() {
        PoseDetectorOptions options =
                new PoseDetectorOptions.Builder()
                        .setDetectorMode(
                                PoseDetectorOptions.STREAM_MODE)
                        .build();

        detector = PoseDetection.getClient(options);
    }

    public List<Subject> detect(Bitmap bitmap) {
        List<Subject> result = new ArrayList<>();

        if (bitmap == null) {
            return result;
        }

        try {
            InputImage image =
                    InputImage.fromBitmap(bitmap, 0);

            Pose pose =
                    Tasks.await(
                            detector.process(image),
                            2,
                            TimeUnit.SECONDS);

            List<PointF> torsoPoints = new ArrayList<>();

            addLandmark(
                    pose,
                    PoseLandmark.LEFT_SHOULDER,
                    torsoPoints);

            addLandmark(
                    pose,
                    PoseLandmark.RIGHT_SHOULDER,
                    torsoPoints);

            addLandmark(
                    pose,
                    PoseLandmark.LEFT_HIP,
                    torsoPoints);

            addLandmark(
                    pose,
                    PoseLandmark.RIGHT_HIP,
                    torsoPoints);

            if (torsoPoints.isEmpty()) {
                return result;
            }

            float minX = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;

            float centerX = 0f;
            float centerY = 0f;

            for (PointF point : torsoPoints) {
                centerX += point.x;
                centerY += point.y;

                minX = Math.min(minX, point.x);
                maxX = Math.max(maxX, point.x);
                minY = Math.min(minY, point.y);
                maxY = Math.max(maxY, point.y);
            }

            centerX /= torsoPoints.size();
            centerY /= torsoPoints.size();

            float imageWidth = bitmap.getWidth();
            float imageHeight = bitmap.getHeight();

            float x = centerX / imageWidth;
            float y = centerY / imageHeight;
            float width = (maxX - minX) / imageWidth;
            float height = (maxY - minY) / imageHeight;

            result.add(
                    new Subject(
                            clamp01(x),
                            clamp01(y),
                            clamp01(width),
                            clamp01(height),
                            width * height,
                            -1));

        } catch (Exception ignored) {
            // Detector failure must not break Pass1.
        }

        return result;
    }

    private static void addLandmark(
            Pose pose,
            int type,
            List<PointF> points) {

        PoseLandmark landmark =
                pose.getPoseLandmark(type);

        if (landmark == null) {
            return;
        }

        float confidence =
                landmark.getInFrameLikelihood();

        if (confidence < 0.30f) {
            return;
        }

        points.add(landmark.getPosition());
    }

    public void close() {
        detector.close();
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
