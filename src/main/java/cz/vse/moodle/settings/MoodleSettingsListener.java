package cz.vse.moodle.settings;

import com.intellij.util.messages.Topic;

/** Application-level notifications about settings changes that need a reload. */
public interface MoodleSettingsListener {
    Topic<MoodleSettingsListener> TOPIC = Topic.create("Moodle VŠE settings", MoodleSettingsListener.class);

    /** The list of courses with VPL assignments changed. */
    void coursesChanged();
}
