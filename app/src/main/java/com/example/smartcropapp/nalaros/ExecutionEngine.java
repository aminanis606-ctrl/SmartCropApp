package com.example.smartcropapp.nalaros;

import java.util.ArrayList;
import java.util.List;

public class ExecutionEngine {

    public Artifact execute(Task task) throws Exception {

        task.setState(Task.State.PREPARING);

        List<Artifact> artifacts = new ArrayList<>();

        if (task.getInput() != null) {
            artifacts.add(
                    new Artifact(
                            "input",
                            String.valueOf(task.getInput()),
                            0,
                            "",
                            "task-input",
                            true));
        }

        try {
            task.setState(Task.State.RUNNING);

            for (Stage stage : task.getStages()) {

                Artifact output =
                        stage.execute(task, artifacts);

                if (output == null) {
                    throw new IllegalStateException(
                            "Stage menghasilkan artifact null: "
                                    + stage.getId());
                }

                task.setState(Task.State.VALIDATING);

                if (!stage.validate(output)) {
                    throw new IllegalStateException(
                            "Artifact tidak valid: "
                                    + stage.getId());
                }

                output.setValid(true);
                artifacts.add(output);

                task.setState(Task.State.RUNNING);
            }

            if (artifacts.isEmpty()) {
                throw new IllegalStateException(
                        "Pipeline tidak menghasilkan artifact.");
            }

            Artifact result =
                    artifacts.get(artifacts.size() - 1);

            task.setResult(result);
            task.setState(Task.State.COMPLETED);

            return result;

        } catch (Exception e) {

            task.setState(Task.State.FAILED);
            throw e;
        }
    }
}
