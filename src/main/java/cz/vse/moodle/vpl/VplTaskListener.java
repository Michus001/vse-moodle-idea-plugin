package cz.vse.moodle.vpl;

import com.intellij.util.messages.Topic;

/** Project-level notification that the state of the project's VPL task changed (result, progress, busy flag). */
public interface VplTaskListener {
    Topic<VplTaskListener> TOPIC = Topic.create("Moodle VPL task", VplTaskListener.class);

    void taskChanged();
}
