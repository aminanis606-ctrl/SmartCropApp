package com.example.smartcropapp.nalaros;

import java.util.List;

public interface Stage {

    String getId();

    String getVersion();

    Artifact execute(
            Task task,
            List<Artifact> inputs) throws Exception;

    boolean validate(Artifact artifact) throws Exception;
}
