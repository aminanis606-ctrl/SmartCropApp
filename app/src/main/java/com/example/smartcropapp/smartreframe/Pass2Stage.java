package com.example.smartcropapp.smartreframe;

import com.example.smartcropapp.core.Pass2Optimizer;
import com.example.smartcropapp.nalaros.Artifact;
import com.example.smartcropapp.nalaros.Stage;
import com.example.smartcropapp.nalaros.Task;

import java.io.File;
import java.util.List;

public class Pass2Stage implements Stage {

    private final File trajectoryFile;

    public Pass2Stage(File trajectoryFile) {
        this.trajectoryFile = trajectoryFile;
    }

    @Override
    public String getId() {
        return "smartreframe.pass2";
    }

    @Override
    public String getVersion() {
        return "1";
    }

    @Override
    public Artifact execute(
            Task task,
            List<Artifact> inputs) throws Exception {

        SmartReframeTask smartReframeTask =
                (SmartReframeTask) task.getConfiguration();

        Artifact analysis =
                smartReframeTask.getAnalysisArtifact();

        if (analysis == null || !analysis.isValid()) {
            throw new IllegalStateException(
                    "Pass2 membutuhkan artifact Pass1 yang valid.");
        }

        File analysisFile =
                new File(analysis.getLocation());

        if (!analysisFile.exists() || analysisFile.length() <= 0) {
            throw new IllegalStateException(
                    "File analysis Pass1 tidak ditemukan: "
                            + analysisFile.getAbsolutePath());
        }

        Pass2Optimizer.optimize(
                analysisFile,
                trajectoryFile,
                ((SmartReframeTask) task.getConfiguration()).getVideoId());

        return new Artifact(
                "trajectory",
                trajectoryFile.getAbsolutePath(),
                trajectoryFile.length(),
                "",
                getId(),
                true);
    }

    @Override
    public boolean validate(Artifact artifact) throws Exception {
        if (artifact == null) return false;

        File file =
                new File(artifact.getLocation());

        return file.exists() && file.length() > 0;
    }
}
