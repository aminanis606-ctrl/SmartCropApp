package com.example.smartcropapp.nalaros;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Task {

    public enum State {
        IDLE,
        PREPARING,
        RUNNING,
        VALIDATING,
        COMPLETED,
        FAILED,
        RECOVERING
    }

    private final String id;
    private final Object input;
    private final Object configuration;
    private final List<Stage> stages;

    private State state = State.IDLE;
    private Artifact result;

    public Task(
            String id,
            Object input,
            Object configuration,
            List<Stage> stages) {

        this.id = id;
        this.input = input;
        this.configuration = configuration;
        this.stages = new ArrayList<>(stages);
    }

    public String getId() {
        return id;
    }

    public Object getInput() {
        return input;
    }

    public Object getConfiguration() {
        return configuration;
    }

    public List<Stage> getStages() {
        return Collections.unmodifiableList(stages);
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public Artifact getResult() {
        return result;
    }

    public void setResult(Artifact result) {
        this.result = result;
    }
}
