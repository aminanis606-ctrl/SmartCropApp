package com.example.smartcropapp.smartreframe;

import android.content.Context;
import android.net.Uri;

import com.example.smartcropapp.core.Pass3Renderer;
import com.example.smartcropapp.nalaros.Artifact;
import com.example.smartcropapp.nalaros.Stage;
import com.example.smartcropapp.nalaros.Task;

import java.io.File;
import java.util.List;

public class Pass3Stage implements Stage {

    private final File outputVideoFile;

    public Pass3Stage(File outputVideoFile) {
        this.outputVideoFile = outputVideoFile;
    }

    @Override
    public String getId() {
        return "smartreframe.pass3";
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
                    "Pass3 membutuhkan artifact Pass2.");
        }

        Artifact trajectory =
                inputs.get(inputs.size() - 1);

        SmartReframeTask smartReframeTask =
                (SmartReframeTask) task.getConfiguration();
        Context context = smartReframeTask.getContext();

        Uri sourceVideoUri =
                (Uri) task.getInput();

        File trajectoryFile =
                new File(trajectory.getLocation());

        File result =
                Pass3Renderer.render(
                        context,
                        sourceVideoUri,
                        trajectoryFile,
                        outputVideoFile);

        return new Artifact(
                "video",
                result.getAbsolutePath(),
                result.length(),
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
