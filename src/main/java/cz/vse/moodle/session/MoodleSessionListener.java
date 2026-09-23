package cz.vse.moodle.session;

import com.intellij.util.messages.Topic;
import org.jetbrains.annotations.NotNull;

/** Application-level topic; always delivered on the EDT. */
public interface MoodleSessionListener {
    @Topic.AppLevel
    Topic<MoodleSessionListener> TOPIC = new Topic<>(MoodleSessionListener.class, Topic.BroadcastDirection.NONE);

    void sessionChanged(@NotNull MoodleSessionState state);
}
