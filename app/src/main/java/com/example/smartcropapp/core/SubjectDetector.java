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

    static final class Detection {
        final List<Subject> subjects;
        final float headTopY;

        Detection(List<Subject> subjects, float headTopY) {
            this.subjects = subjects;
            this.headTopY = headTopY;
        }
    }

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
        return detectInternal(bitmap).subjects;
    }

    Detection detectWithHead(Bitmap bitmap) {
        return detectInternal(bitmap);
    }

    private Detection detectInternal(Bitmap bitmap) {
        List<Subject> result = new ArrayList<>();

        if (bitmap == null) {
            return new Detection(result, Float.NaN);
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
            List<PointF> shoulderPoints = new ArrayList<>();

            addLandmark(
                    pose,
                    PoseLandmark.LEFT_SHOULDER,
                    torsoPoints);

            addLandmark(
                    pose,
                    PoseLandmark.LEFT_SHOULDER,
                    shoulderPoints);

            addLandmark(
                    pose,
                    PoseLandmark.RIGHT_SHOULDER,
                    torsoPoints);

            addLandmark(
                    pose,
                    PoseLandmark.RIGHT_SHOULDER,
                    shoulderPoints);

            addLandmark(
                    pose,
                    PoseLandmark.LEFT_HIP,
                    torsoPoints);

            addLandmark(
                    pose,
                    PoseLandmark.RIGHT_HIP,
                    torsoPoints);

            if (torsoPoints.isEmpty()) {
                return new Detection(result, Float.NaN);
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

            // Framing anchor: prefer the midpoint of both shoulders.
            // Torso bbox/width/height/areaScore tetap unchanged.
            if (shoulderPoints.size() == 2) {
                centerX =
                        (shoulderPoints.get(0).x +
                         shoulderPoints.get(1).x) * 0.5f;
                centerY =
                        (shoulderPoints.get(0).y +
                         shoulderPoints.get(1).y) * 0.5f;
            }

            float imageWidth = bitmap.getWidth();
            float imageHeight = bitmap.getHeight();

            float x = centerX / imageWidth;
            float y = centerY / imageHeight;
            float width = (maxX - minX) / imageWidth;
            float height = (maxY - minY) / imageHeight;

            float headTopY = findHeadTopY(pose, imageHeight);

            result.add(
                    new Subject(
                            clamp01(x),
                            clamp01(y),
                            clamp01(width),
                            clamp01(height),
                            width * height,
                            -1));

            return new Detection(result, headTopY);
        } catch (Exception ignored) {
            // Detector failure must not break Pass1.
            return new Detection(result, Float.NaN);
        }
    }

    private static float findHeadTopY(Pose pose, float imageHeight) {
        PointF nose = validLandmark(pose, PoseLandmark.NOSE);
        if (nose != null) return clamp01(nose.y / imageHeight);

        PointF leftEye = validLandmark(pose, PoseLandmark.LEFT_EYE);
        PointF rightEye = validLandmark(pose, PoseLandmark.RIGHT_EYE);
        if (leftEye != null && rightEye != null) {
            return clamp01((leftEye.y + rightEye.y) * 0.5f / imageHeight);
        }

        PointF leftEar = validLandmark(pose, PoseLandmark.LEFT_EAR);
        PointF rightEar = validLandmark(pose, PoseLandmark.RIGHT_EAR);
        if (leftEar != null && rightEar != null) {
            return clamp01((leftEar.y + rightEar.y) * 0.5f / imageHeight);
        }

        return Float.NaN;
    }

    private static PointF validLandmark(Pose pose, int type) {
        PoseLandmark landmark = pose.getPoseLandmark(type);
        if (landmark == null || landmark.getInFrameLikelihood() < 0.30f) {
            return null;
        }
        return landmark.getPosition();
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
