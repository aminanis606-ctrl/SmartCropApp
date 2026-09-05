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

        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalStateException(
                    "Pass2 membutuhkan artifact Pass1.");
        }

        Artifact analysis =
                inputs.get(inputs.size() - 1);

        File analysisFile =
                new File(analysis.getLocation());

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
