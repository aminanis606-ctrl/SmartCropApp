package com.example.smartcropapp.core;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

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

    private final FaceDetector detector;

    public SubjectDetector() {
        FaceDetectorOptions options =
                new FaceDetectorOptions.Builder()
                        .setPerformanceMode(
                                FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                        .setLandmarkMode(
                                FaceDetectorOptions.LANDMARK_MODE_NONE)
                        .setClassificationMode(
                                FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                        .enableTracking()
                        .setMinFaceSize(0.08f)
                        .build();

        detector = FaceDetection.getClient(options);
    }

    public List<Subject> detect(Bitmap bitmap) {
        List<Subject> result = new ArrayList<>();

        if (bitmap == null) {
            return result;
        }

        try {
            InputImage image = InputImage.fromBitmap(bitmap, 0);

            List<Face> faces =
                    Tasks.await(
                            detector.process(image),
                            2,
                            TimeUnit.SECONDS);

            int imageWidth = bitmap.getWidth();
            int imageHeight = bitmap.getHeight();

            for (Face face : faces) {
                Rect box = face.getBoundingBox();

                float x =
                        box.centerX() / (float) imageWidth;

                float y =
                        box.centerY() / (float) imageHeight;

                float width =
                        box.width() / (float) imageWidth;

                float height =
                        box.height() / (float) imageHeight;

                Integer trackingId =
                        face.getTrackingId();

                result.add(
                        new Subject(
                                clamp01(x),
                                clamp01(y),
                                clamp01(width),
                                clamp01(height),
                                width * height,
                                trackingId == null
                                        ? -1
                                        : trackingId));
            }

        } catch (Exception ignored) {
            // Detector failure must not break Pass1.
        }

        return result;
    }

    public void close() {
        detector.close();
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
