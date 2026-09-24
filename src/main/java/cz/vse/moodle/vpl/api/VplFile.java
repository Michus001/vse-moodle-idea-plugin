package cz.vse.moodle.vpl.api;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * File of a VPL submission. {@code name} is a relative path with {@code /} separators (e.g. {@code src/Main.java}).
 */
public record VplFile(@NotNull String name, byte @NotNull [] data) {

    public static @NotNull VplFile text(@NotNull String name, @NotNull String contents) {
        return new VplFile(name, contents.getBytes(StandardCharsets.UTF_8));
    }

    /** Text files are sent as-is, anything that isn't valid UTF-8 (or contains NUL) as Base64, like the VPL editor does. */
    public boolean isBinary() {
        for (byte b : data) {
            if (b == 0) return true;
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data));
            return false;
        }
        catch (CharacterCodingException e) {
            return true;
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VplFile other && name.equals(other.name) && Arrays.equals(data, other.data);
    }

    @Override
    public int hashCode() {
        return 31 * name.hashCode() + Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "VplFile{" + name + ", " + data.length + " B}";
    }
}
