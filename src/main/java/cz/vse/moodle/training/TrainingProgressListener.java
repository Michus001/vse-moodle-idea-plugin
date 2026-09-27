package cz.vse.moodle.training;

import com.intellij.util.messages.Topic;

/** Application-level notification that the recorded progress of some training task changed. */
public interface TrainingProgressListener {
    Topic<TrainingProgressListener> TOPIC = Topic.create("Moodle VŠE training progress", TrainingProgressListener.class);

    void progressChanged();
}
