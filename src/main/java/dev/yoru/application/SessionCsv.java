package dev.yoru.application;

import dev.yoru.domain.Model.Activity;
import dev.yoru.domain.Model.State;
import java.time.Duration;
import java.util.HashMap;
import java.util.UUID;

/**
 * Recorded sessions as CSV, for a spreadsheet (#1): one row per session, in UTC,
 * with its length in seconds.
 *
 * Every cell is quoted, and a cell a spreadsheet would read as a formula — one
 * starting with =, +, - or @, or a tab or line break — is written as text with
 * a leading apostrophe, so an activity called "=HYPERLINK(…)" stays a name.
 */
public final class SessionCsv {
    private SessionCsv() { }

    public static String write(State state) {
        var names = new HashMap<UUID, String>();
        for (Activity activity : state.activities()) names.put(activity.id(), activity.name());
        var out = new StringBuilder("activity,start_utc,end_utc,duration_seconds\n");
        for (var session : state.sessions()) {
            out.append(cell(names.getOrDefault(session.activityId(), "Activity"))).append(',')
                .append(cell(session.start().toString())).append(',')
                .append(cell(session.end() == null ? "" : session.end().toString())).append(',')
                .append(session.end() == null ? "" : Duration.between(session.start(), session.end()).getSeconds())
                .append('\n');
        }
        return out.toString();
    }

    /** One quoted cell, never read as a formula. */
    public static String cell(String value) {
        boolean formula = !value.isEmpty()
            && (!value.stripLeading().isEmpty() && "=+-@".indexOf(value.stripLeading().charAt(0)) >= 0
                || "\t\r\n".indexOf(value.charAt(0)) >= 0);
        if (formula) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
