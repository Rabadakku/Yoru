package dev.yoru.update;

import java.util.regex.Pattern;

/** A release version, major.minor.patch, as the tags and jpackage write it. */
public record Version(int major, int minor, int patch) implements Comparable<Version> {
    private static final Pattern FORM = Pattern.compile("v?(\\d{1,4})\\.(\\d{1,4})\\.(\\d{1,4})");

    /** "1.0.5" or "v1.0.5". Pre-release tags are refused: an update only ever moves to a published release. */
    public static Version parse(String text) {
        var match = FORM.matcher(text == null ? "" : text.strip());
        if (!match.matches()) throw new IllegalArgumentException("\"" + text + "\" is not a release version like 1.0.5.");
        return new Version(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)), Integer.parseInt(match.group(3)));
    }

    /**
     * The installed version, or null for a copy built from source. jpackage's
     * launcher passes it in as {@code jpackage.app-version}; nothing else does.
     */
    public static Version running() {
        String given = System.getProperty("jpackage.app-version");
        if (given == null) return null;
        try {
            return parse(given);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public int compareTo(Version other) {
        if (major != other.major) return Integer.compare(major, other.major);
        if (minor != other.minor) return Integer.compare(minor, other.minor);
        return Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() { return major + "." + minor + "." + patch; }
}
