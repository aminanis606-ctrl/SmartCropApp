package com.example.smartcropapp.core;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;

public class Pass2Optimizer {
    private static final String TAG = "Pass2Optimizer";

    public static File optimize(File analysisJsonFile, File outputTrajectoryFile) {
        try {
            Log.i(TAG, "Memulai Pass 2 optimization dari: " + analysisJsonFile.getAbsolutePath());

            // Baca analysis.json untuk menyalin array faces ke trajectory
            String content = new String(Files.readAllBytes(analysisJsonFile.toPath()));
            JSONObject analysisRoot = new JSONObject(content);
            JSONArray faces = analysisRoot.optJSONArray("faces");

            if (faces == null) {
                faces = new JSONArray();
                JSONObject defaultPoint = new JSONObject();
                defaultPoint.put("t", 0);
                defaultPoint.put("x", 0.5);
                defaultPoint.put("y", 0.4);
                defaultPoint.put("size", 0.3);
                faces.put(defaultPoint);
            }

            JSONObject trajectoryResult = new JSONObject();
            trajectoryResult.put("smoothedTrajectory", faces);

            try (FileWriter fileWriter = new FileWriter(outputTrajectoryFile)) {
                fileWriter.write(trajectoryResult.toString(4));
                fileWriter.flush();
            }

            Log.i(TAG, "Pass 2 Selesai. Trajectory tersimpan di: " + outputTrajectoryFile.getAbsolutePath());
            return outputTrajectoryFile;

        } catch (Exception e) {
            Log.e(TAG, "Gagal menjalankan Pass 2 optimization", e);
            throw new RuntimeException("Pass 2 gagal: " + e.getMessage(), e);
        }
    }
}
