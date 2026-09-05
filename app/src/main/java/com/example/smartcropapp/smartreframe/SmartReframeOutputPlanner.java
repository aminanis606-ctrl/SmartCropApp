package com.example.smartcropapp.smartreframe;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import java.io.File;
import java.nio.file.Files;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONObject;

public class SmartReframeOutputPlanner {

    private final Context context;

    public SmartReframeOutputPlanner(Context context) {
        this.context = context;
    }

    public File plan(
            Uri sourceUri,
            File analysisFile,
            File outputDir) throws Exception {

        if (sourceUri == null) {
            throw new IllegalArgumentException("sourceUri tidak boleh null");
        }

        if (analysisFile == null ||
                !analysisFile.exists() ||
                analysisFile.length() <= 0) {
            throw new IllegalArgumentException(
                    "analysisFile tidak valid");
        }

        if (outputDir == null) {
            throw new IllegalArgumentException(
                    "outputDir tidak boleh null");
        }

        MediaMetadataRetriever mmr =
                new MediaMetadataRetriever();

        try {
            mmr.setDataSource(context, sourceUri);

            String durationStr =
                    mmr.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_DURATION);

            long durationMs =
                    durationStr == null
                            ? 0L
                            : Long.parseLong(durationStr);

            long totalSeconds =
                    Math.max(0L, durationMs / 1000L);

            long minutes = totalSeconds / 60L;
            long seconds = totalSeconds % 60L;

            String duration =
                    String.format(
                            Locale.US,
                            "%02d:%02d",
                            minutes,
                            seconds);

            JSONObject root =
                    new JSONObject(
                            new String(
                                    Files.readAllBytes(
                                            analysisFile.toPath()),
                                    java.nio.charset.StandardCharsets.UTF_8));

            JSONArray shots =
                    root.optJSONArray("shots");

            int shotCount =
                    shots == null ? 0 : shots.length();

            int splitCount = 0;

            if (shots != null) {
                for (int i = 0; i < shots.length(); i++) {
                    if ("split".equals(
                            shots.getJSONObject(i)
                                    .optString("layout"))) {
                        splitCount++;
                    }
                }
            }

            int singleCount =
                    Math.max(0, shotCount - splitCount);

            String sourceName = "video";

            String uriName =
                    sourceUri.getLastPathSegment();

            if (uriName != null && !uriName.isEmpty()) {
                int slash = uriName.lastIndexOf('/');

                if (slash >= 0) {
                    uriName =
                            uriName.substring(slash + 1);
                }

                int dot = uriName.lastIndexOf('.');

                if (dot > 0) {
                    uriName =
                            uriName.substring(0, dot);
                }

                if (!uriName.isEmpty()) {
                    sourceName = uriName;
                }
            }

            sourceName =
                    sourceName.replaceAll(
                            "[^A-Za-z0-9_-]",
                            "_");

            String base =
                    String.format(
                            Locale.US,
                            "%s_SHT%d_SPT%d_SGL%d_%s",
                            duration,
                            shotCount,
                            splitCount,
                            singleCount,
                            sourceName);

            File result =
                    new File(
                            outputDir,
                            base + ".mp4");

            int index = 1;

            while (result.exists()) {
                result =
                        new File(
                                outputDir,
                                String.format(
                                        Locale.US,
                                        "%s_%03d.mp4",
                                        base,
                                        index++));
            }

            return result;

        } finally {
            mmr.release();
        }
    }
}
