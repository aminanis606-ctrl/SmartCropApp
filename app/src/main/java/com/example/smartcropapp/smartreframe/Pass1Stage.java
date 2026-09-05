package com.example.smartcropapp.smartreframe;

import android.content.Context;
import android.net.Uri;

import com.example.smartcropapp.core.Pass1Extractor;
import com.example.smartcropapp.nalaros.Artifact;
import com.example.smartcropapp.nalaros.Stage;
import com.example.smartcropapp.nalaros.Task;

import java.io.File;
import java.util.List;

public class Pass1Stage implements Stage {

    private final File outputFile;

    public Pass1Stage(File outputFile) {
        this.outputFile = outputFile;
    }

    @Override
    public String getId() {
        return "smartreframe.pass1";
    }

    @Override
    public String getVersion() {
        return "1";
    }

    @Override
    public Artifact execute(
            Task task,
            List<Artifact> inputs) throws Exception {

        SmartReframeTask smartReframeTask = (SmartReframeTask) task.getConfiguration();
        Context context = smartReframeTask.getContext();
        Uri sourceVideoUri = (Uri) task.getInput();

        File result =
                Pass1Extractor.extract(
                        context,
                        sourceVideoUri,
                        outputFile);

        return new Artifact(
                "analysis",
                result.getAbsolutePath(),
                result.length(),
                "",
                getId(),
                true);
    }

    @Override
    public boolean validate(Artifact artifact) throws Exception {
        if (artifact == null) return false;

        File file = new File(artifact.getLocation());

        return file.exists() && file.length() > 0;
    }
}
