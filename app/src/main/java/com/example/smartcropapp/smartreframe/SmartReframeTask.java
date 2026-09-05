package com.example.smartcropapp.smartreframe;


import android.content.Context;
import android.net.Uri;

import com.example.smartcropapp.nalaros.Artifact;

public class SmartReframeTask {

    private final Context context;
    private final Uri sourceVideoUri;
    private final String videoId;
    private Artifact analysisArtifact;

    public SmartReframeTask(
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

    public Artifact getAnalysisArtifact() {
        return analysisArtifact;
    }

    public void setAnalysisArtifact(Artifact analysisArtifact) {
        this.analysisArtifact = analysisArtifact;
    }
}
