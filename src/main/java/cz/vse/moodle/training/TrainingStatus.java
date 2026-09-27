package cz.vse.moodle.training;

import org.jetbrains.annotations.NotNull;

/** Progress of the student on one training task, as recorded locally. */
public enum TrainingStatus {
    NEW("nová"),
    STARTED("rozpracovaná"),
    SOLVED("vyřešená");

    private final String label;

    TrainingStatus(@NotNull String label) {
        this.label = label;
    }

    public @NotNull String label() {
        return label;
    }
}
