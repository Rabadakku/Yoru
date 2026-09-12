package dev.yoru.json;

import java.util.*;

/**
 * Bounded JSON codec. No reflection and no object deserialization: it reads to
 * maps, lists, strings, booleans and BigDecimal, and callers build their own
 * records from that.
 *
 * Its own leaf package because two unrelated callers need it and neither should
 * depend on the other: the OpenAI boundary in {@code ai}, and the portable
 * vault export in {@code persistence}.
 */
public final class Json {
    private Json() { }
    public static String write(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) {
            var out = new StringBuilder("\"");
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> { if (c < 32) out.append(String.format("\\u%04x", (int)c)); else out.append(c); }
                }
            }
            return out.append('"').toString();
        }
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof Map<?, ?> map) {
            var entries = new ArrayList<String>();
            map.forEach((k,v) -> entries.add(write(k.toString()) + ":" + write(v)));
            return "{" + String.join(",", entries) + "}";
        }
        if (value instanceof List<?> list) return "[" + String.join(",", list.stream().map(Json::write).toList()) + "]";
        throw new IllegalArgumentException("Unsupported JSON value.");
    }
    /** Default limit, sized for an API response rather than a whole vault. */
    public static Object read(String input) { return read(input, 2_000_000); }

    /** Indented output. An export nobody can read is not much of a debugging tool. */
    public static String pretty(Object value) { return pretty(value, 0); }

    private static String pretty(Object value, int depth) {
        String pad = "  ".repeat(depth + 1), close = "  ".repeat(depth);
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) return "{}";
            var entries = new ArrayList<String>();
            map.forEach((k, v) -> entries.add(pad + write(k.toString()) + ": " + pretty(v, depth + 1)));
            return "{\n" + String.join(",\n", entries) + "\n" + close + "}";
        }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) return "[]";
            var items = list.stream().map(item -> pad + pretty(item, depth + 1)).toList();
            return "[\n" + String.join(",\n", items) + "\n" + close + "]";
        }
        return write(value);
    }

    public static Object read(String input, int maxCharacters) {
        if (input.length() > maxCharacters) throw new IllegalArgumentException("JSON input too large.");
        var p = new Parser(input);
        Object value = p.value(0); p.space();
        if (p.i != input.length()) throw p.bad();
        return value;
    }
    public static Map<?, ?> object(Object x) { if (x instanceof Map<?, ?> m) return m; throw new IllegalArgumentException("Expected JSON object."); }
    public static List<?> array(Object x) { if (x instanceof List<?> a) return a; throw new IllegalArgumentException("Expected JSON array."); }
    public static String string(Object x) { if (x instanceof String s) return s; throw new IllegalArgumentException("Expected text."); }
    private static final class Parser {
        final String s; int i;
        Parser(String s) { this.s = s; }
        IllegalArgumentException bad() { return new IllegalArgumentException("Invalid JSON response."); }
        void space() { while (i < s.length() && " \n\r\t".indexOf(s.charAt(i)) >= 0) i++; }
        boolean take(char c) { space(); if (i < s.length() && s.charAt(i) == c) { i++; return true; } return false; }
        Object value(int depth) {
            if (depth > 32) throw bad(); space(); if (i >= s.length()) throw bad();
            char c = s.charAt(i);
            if (c == '"') return text();
            if (take('{')) {
                var map = new LinkedHashMap<String,Object>();
                if (take('}')) return map;
                do { space(); if (i >= s.length() || s.charAt(i) != '"') throw bad();
                    String key = text(); if (!take(':') || map.containsKey(key)) throw bad(); map.put(key, value(depth + 1));
                } while (take(','));
                if (!take('}')) throw bad(); return map;
            }
            if (take('[')) {
                var list = new ArrayList<>(); if (take(']')) return list;
                do { if (list.size() >= 100_000) throw bad(); list.add(value(depth + 1)); } while (take(','));
                if (!take(']')) throw bad(); return list;
            }
            for (String word : List.of("true", "false", "null")) if (s.startsWith(word, i)) {
                i += word.length(); return word.equals("null") ? null : Boolean.valueOf(word);
            }
            int start = i;
            while (i < s.length() && "-+0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String number = s.substring(start, i);
            if (!number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw bad();
            try { return new java.math.BigDecimal(number); } catch (NumberFormatException e) { throw bad(); }
        }
        String text() {
            i++; var out = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return out.toString();
                if (c < 32) throw bad();
                if (c != '\\') { out.append(c); continue; }
                if (i >= s.length()) throw bad();
                char escape = s.charAt(i++);
                switch (escape) {
                    case '"', '\\', '/' -> out.append(escape);
                    case 'b' -> out.append('\b'); case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n'); case 'r' -> out.append('\r'); case 't' -> out.append('\t');
                    case 'u' -> { if (i + 4 > s.length()) throw bad();
                        try { out.append((char)Integer.parseInt(s.substring(i, i + 4), 16)); } catch (NumberFormatException e) { throw bad(); } i += 4; }
                    default -> throw bad();
                }
            }
            throw bad();
        }
    }
}
