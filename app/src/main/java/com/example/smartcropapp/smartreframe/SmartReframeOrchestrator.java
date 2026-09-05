package com.example.smartcropapp.smartreframe;

import android.content.Context;
import android.net.Uri;

import com.example.smartcropapp.nalaros.Artifact;
import com.example.smartcropapp.nalaros.ExecutionEngine;
import com.example.smartcropapp.nalaros.Task;

import java.io.File;
import java.util.Arrays;

public class SmartReframeOrchestrator {

    private final Context context;
    private final Uri sourceVideoUri;
    private final String videoId;
    private Artifact analysisArtifact;

    public SmartReframeOrchestrator(
            Context context,
            Uri sourceVideoUri,
            String videoId) {

        this.context = context;
        this.sourceVideoUri = sourceVideoUri;
        this.videoId = videoId;
    }

    public Context getContext() {
        return context;
    }

    public Uri getSourceVideoUri() {
        return sourceVideoUri;
    }

    public String getVideoId() {
        return videoId;
    }

    public Artifact runPass1(File analysisFile) throws Exception {
        SmartReframeTask configuration =
                new SmartReframeTask(
                        context,
                        sourceVideoUri,
                        videoId);

        Task task =
                new Task(
                        "smartreframe.pass1",
                        sourceVideoUri,
                        configuration,
                        java.util.Collections.singletonList(
                                new Pass1Stage(analysisFile)));

        analysisArtifact =
                new ExecutionEngine().execute(task);

        if (analysisArtifact == null ||
                !analysisArtifact.isValid()) {
            throw new IllegalStateException(
                    "Artifact analysis dari Pass1 tidak valid.");
        }

        return analysisArtifact;
    }

    public Artifact runPass2AndPass3(
            File trajectoryFile,
            File outputVideoFile) throws Exception {

        SmartReframeTask configuration =
                new SmartReframeTask(
                        context,
                        sourceVideoUri,
                        videoId);

        if (analysisArtifact == null ||
                !analysisArtifact.isValid()) {
            throw new IllegalStateException(
                    "Artifact analysis dari Pass1 tidak tersedia.");
        }

        configuration.setAnalysisArtifact(analysisArtifact);

        Task task =
                new Task(
                        "smartreframe.pass2-pass3",
                        sourceVideoUri,
                        configuration,
                        Arrays.asList(
                                new Pass2Stage(trajectoryFile),
                                new Pass3Stage(outputVideoFile)));

        return new ExecutionEngine().execute(task);
    }
}
