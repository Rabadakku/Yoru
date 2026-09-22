package dev.yoru.pages;

import java.util.*;
import java.util.regex.*;

/**
 * Obsidian-flavoured Markdown, read into blocks and inline spans that keep the
 * exact offsets they came from.
 *
 * Offsets are the point. The editor styles the text it holds by them, renaming
 * a page rewrites the links it finds by them, and backlinks and search jump to
 * them — so nothing here rebuilds the text or normalises it on the way through.
 * Every range is [start, end) into the page body exactly as written.
 *
 * It reads what people write in Obsidian rather than every corner of
 * CommonMark: ATX headings, fenced code, quotes and callouts, lists and
 * checkboxes, tables, rules, frontmatter, comments, maths and footnotes; and
 * inline code, emphasis, highlights, [[links]], ![[embeds]], Markdown links and
 * images, bare URLs, #tags and escapes. Anything it does not recognise is plain
 * text, never an error: a page always opens.
 */
public final class Markdown {
    private Markdown() { }

    public enum BlockKind {
        FRONTMATTER, HEADING, PARAGRAPH, LIST_ITEM, QUOTE, CALLOUT, CODE, MATH, COMMENT,
        TABLE, TABLE_ROW, TABLE_CELL, RULE, FOOTNOTE
    }

    public enum SpanKind {
        CODE, MATH, COMMENT, WIKILINK, EMBED, LINK, IMAGE, URL, TAG, FOOTNOTE_REF,
        BOLD, ITALIC, STRIKE, HIGHLIGHT, ESCAPE, BLOCK_ID
    }

    /**
     * One inline span. For emphasis and code, [contentStart, contentEnd) is the
     * text inside the markers. For links, target is the page or address, anchor
     * the "#heading" or "#^block" part without its sign, and alias the text to
     * show instead of the target.
     */
    public record Span(SpanKind kind, int start, int end, int contentStart, int contentEnd,
                       String target, String anchor, String alias) {
        public boolean isLink() { return kind == SpanKind.WIKILINK || kind == SpanKind.EMBED
            || kind == SpanKind.LINK || kind == SpanKind.IMAGE || kind == SpanKind.URL; }
    }

    /**
     * One block. level is a heading's level or a list item's indent in columns;
     * info is a code block's language, a callout's type, a list item's marker, a
     * table's column alignments or a footnote's label; check is a list item's
     * checkbox character, or 0 when it has none. A callout's fold is kept in
     * check too: '+' open, '-' folded, 0 fixed. blockId is the "^id" at the
     * end of the block, without the caret.
     */
    public record Block(BlockKind kind, int start, int end, int contentStart, int contentEnd,
                        int level, String info, char check, String blockId,
                        List<Span> spans, List<Block> children) {
        public Block {
            spans = List.copyOf(spans);
            children = List.copyOf(children);
        }
    }

    /** A parsed page: its blocks in order, and the text they index into. */
    public record Doc(String text, List<Block> blocks) {
        public Doc { blocks = List.copyOf(blocks); }

        /** Every block, children included, in the order they appear. */
        public List<Block> allBlocks() {
            var out = new ArrayList<Block>();
            walk(blocks, out);
            return out;
        }
        private static void walk(List<Block> blocks, List<Block> out) {
            for (var b : blocks) { out.add(b); walk(b.children(), out); }
        }

        /** Every inline span in every block, in the order they appear. */
        public List<Span> allSpans() {
            var out = new ArrayList<Span>();
            for (var b : allBlocks()) out.addAll(b.spans());
            out.sort(Comparator.comparingInt(Span::start));
            return out;
        }

        public List<Block> headings() {
            return allBlocks().stream().filter(b -> b.kind() == BlockKind.HEADING).toList();
        }

        /** A heading's text, without its markers or a trailing block id. */
        public String headingText(Block heading) {
            return text.substring(heading.contentStart(), heading.contentEnd()).strip();
        }
    }

    // ---------------------------------------------------------------- lines

    /** One line of the text: [start, end) without its line break. */
    private record Line(int start, int end) { }

    private static List<Line> lines(String text, int from, int to) {
        var out = new ArrayList<Line>();
        int at = from;
        while (at <= to) {
            int nl = text.indexOf('\n', at);
            if (nl < 0 || nl >= to) {
                if (at < to || out.isEmpty() || at == to && at > from && text.charAt(at - 1) == '\n') {
                    if (at < to) out.add(new Line(at, to));
                }
                break;
            }
            int end = nl > at && text.charAt(nl - 1) == '\r' ? nl - 1 : nl;
            out.add(new Line(at, end));
            at = nl + 1;
        }
        return out;
    }

    private static String line(String text, Line l) { return text.substring(l.start(), l.end()); }

    private static boolean blank(String text, Line l) { return line(text, l).isBlank(); }

    // ---------------------------------------------------------------- blocks

    private static final Pattern HEADING = Pattern.compile("^( {0,3})(#{1,6})(?:[ \\t]+(.*?))?(?:[ \\t]+#+)?[ \\t]*$");
    private static final Pattern FENCE = Pattern.compile("^( {0,3})(`{3,}|~{3,})\\s*([^`\\s]*)[^`]*$");
    private static final Pattern RULE = Pattern.compile("^ {0,3}([-*_])(?:[ \\t]*\\1){2,}[ \\t]*$");
    private static final Pattern LIST = Pattern.compile("^([ \\t]*)([-*+]|\\d{1,9}[.)])(?:([ \\t]+)(\\[(.)\\](?=[ \\t]|$)[ \\t]?)?|$)");
    private static final Pattern QUOTE = Pattern.compile("^ {0,3}> ?");
    private static final Pattern CALLOUT = Pattern.compile("^\\[!([A-Za-z][\\w-]*)\\]([+-]?)[ \\t]*(.*)$");
    private static final Pattern FOOTNOTE = Pattern.compile("^\\[\\^([^\\]\\s]+)\\]:[ \\t]?");
    private static final Pattern DELIMITER_ROW = Pattern.compile("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$");
    private static final Pattern BLOCK_ID = Pattern.compile("(?:^|[ \\t])\\^([A-Za-z0-9-]+)[ \\t]*$");

    public static Doc parse(String text) {
        Objects.requireNonNull(text);
        var lines = lines(text, 0, text.length());
        int first = 0;
        var blocks = new ArrayList<Block>();
        // Frontmatter only counts at the very top, as it does in Obsidian.
        if (!lines.isEmpty() && line(text, lines.get(0)).equals("---")) {
            for (int i = 1; i < lines.size(); i++) {
                var l = line(text, lines.get(i));
                if (l.equals("---") || l.equals("...")) {
                    int cs = i > 1 ? lines.get(1).start() : lines.get(0).end();
                    int ce = i > 1 ? lines.get(i - 1).end() : cs;
                    blocks.add(new Block(BlockKind.FRONTMATTER, lines.get(0).start(), lines.get(i).end(), cs, ce,
                        0, null, (char) 0, null, List.of(), List.of()));
                    first = i + 1;
                    break;
                }
            }
        }
        blocks.addAll(blocks(text, lines.subList(first, lines.size()), 0));
        return new Doc(text, blocks);
    }

    /**
     * The blocks in these lines. strip is how many characters of quote
     * prefix each line carries, for blocks read from inside a quote; the lines
     * passed in have already had it removed from their start.
     */
    private static List<Block> blocks(String text, List<Line> lines, int depth) {
        var out = new ArrayList<Block>();
        int i = 0;
        while (i < lines.size()) {
            var l = lines.get(i);
            String s = line(text, l);
            if (s.isBlank()) { i++; continue; }

            Matcher m;
            if ((m = FENCE.matcher(s)).matches()) {
                String fence = m.group(2);
                int j = i + 1;
                while (j < lines.size()) {
                    String t = line(text, lines.get(j)).stripLeading();
                    if (t.startsWith(fence.substring(0, 3)) && t.chars().allMatch(c -> c == fence.charAt(0) || Character.isWhitespace(c))
                        && t.strip().length() >= fence.length()) break;
                    j++;
                }
                int cs = i + 1 < lines.size() ? lines.get(Math.min(i + 1, lines.size() - 1)).start() : l.end();
                int ce = j - 1 > i ? lines.get(j - 1).end() : cs;
                if (j == i + 1) ce = cs = Math.min(cs, j < lines.size() ? lines.get(j).start() : l.end());
                int end = j < lines.size() ? lines.get(j).end() : lines.get(lines.size() - 1).end();
                out.add(new Block(BlockKind.CODE, l.start(), end, Math.min(cs, end), Math.min(Math.max(ce, cs), end),
                    0, m.group(3).isEmpty() ? null : m.group(3), (char) 0, null, List.of(), List.of()));
                i = j + 1;
                continue;
            }
            if (s.strip().equals("$$") || s.strip().startsWith("$$") && !s.strip().substring(2).contains("$$")) {
                int j = i + 1;
                while (j < lines.size() && !line(text, lines.get(j)).strip().endsWith("$$")) j++;
                int end = j < lines.size() ? lines.get(j).end() : lines.get(lines.size() - 1).end();
                out.add(new Block(BlockKind.MATH, l.start(), end, l.start() + s.indexOf("$$") + 2,
                    j < lines.size() ? lines.get(j).start() + line(text, lines.get(j)).lastIndexOf("$$") : end,
                    0, null, (char) 0, null, List.of(), List.of()));
                i = j + 1;
                continue;
            }
            if (s.stripLeading().startsWith("%%") && !s.stripLeading().substring(2).contains("%%")) {
                int j = i + 1;
                while (j < lines.size() && !line(text, lines.get(j)).contains("%%")) j++;
                int end = j < lines.size() ? lines.get(j).end() : lines.get(lines.size() - 1).end();
                out.add(new Block(BlockKind.COMMENT, l.start(), end, l.start(), end, 0, null, (char) 0, null, List.of(), List.of()));
                i = j + 1;
                continue;
            }
            if ((m = HEADING.matcher(s)).matches()) {
                int level = m.group(2).length();
                int cs = m.group(3) == null ? l.end() : l.start() + m.start(3);
                int ce = m.group(3) == null ? l.end() : l.start() + m.end(3);
                String id = null;
                var idm = BLOCK_ID.matcher(text.substring(cs, ce));
                if (idm.find()) { id = idm.group(1); }
                out.add(new Block(BlockKind.HEADING, l.start(), l.end(), cs, ce, level, null, (char) 0, id,
                    inline(text, cs, ce), List.of()));
                i++;
                continue;
            }
            if (RULE.matcher(s).matches()) {
                out.add(new Block(BlockKind.RULE, l.start(), l.end(), l.start(), l.end(), 0, null, (char) 0, null, List.of(), List.of()));
                i++;
                continue;
            }
            if ((m = QUOTE.matcher(s)).lookingAt()) {
                int j = i;
                var inner = new ArrayList<Line>();
                while (j < lines.size()) {
                    String t = line(text, lines.get(j));
                    var qm = QUOTE.matcher(t);
                    if (!qm.lookingAt()) break;
                    inner.add(new Line(lines.get(j).start() + qm.end(), lines.get(j).end()));
                    j++;
                }
                int end = lines.get(j - 1).end();
                var cm = CALLOUT.matcher(line(text, inner.get(0)));
                if (cm.matches()) {
                    var head = inner.get(0);
                    int ts = head.start() + cm.start(3), te = head.start() + cm.end(3);
                    char fold = cm.group(2).isEmpty() ? 0 : cm.group(2).charAt(0);
                    out.add(new Block(BlockKind.CALLOUT, l.start(), end, ts, te, depth, cm.group(1).toLowerCase(Locale.ROOT),
                        fold, null, inline(text, ts, te), blocks(text, inner.subList(1, inner.size()), depth + 1)));
                } else {
                    out.add(new Block(BlockKind.QUOTE, l.start(), end, inner.get(0).start(), end, depth, null, (char) 0, null,
                        List.of(), blocks(text, inner, depth + 1)));
                }
                i = j;
                continue;
            }
            if ((m = LIST.matcher(s)).lookingAt()) {
                int indent = columns(m.group(1));
                char check = m.group(5) == null ? 0 : m.group(5).charAt(0);
                int cs = l.start() + m.end();
                // Continuation lines: indented further than the marker, up to a
                // blank line or the next item.
                int j = i + 1;
                while (j < lines.size()) {
                    String t = line(text, lines.get(j));
                    if (t.isBlank() || LIST.matcher(t).lookingAt() || HEADING.matcher(t).matches()
                        || QUOTE.matcher(t).lookingAt() || FENCE.matcher(t).matches()) break;
                    if (columns(t.substring(0, t.length() - t.stripLeading().length())) <= indent) break;
                    j++;
                }
                int end = lines.get(j - 1).end();
                int ce = end;
                String id = null;
                var idm = BLOCK_ID.matcher(text.substring(cs, ce));
                if (idm.find()) id = idm.group(1);
                out.add(new Block(BlockKind.LIST_ITEM, l.start(), end, cs, Math.max(cs, ce), indent, m.group(2), check, id,
                    inline(text, cs, Math.max(cs, ce)), List.of()));
                i = j;
                continue;
            }
            if ((m = FOOTNOTE.matcher(s)).lookingAt()) {
                int cs = l.start() + m.end();
                int j = i + 1;
                while (j < lines.size() && !blank(text, lines.get(j)) && line(text, lines.get(j)).startsWith("    ")) j++;
                int end = lines.get(j - 1).end();
                out.add(new Block(BlockKind.FOOTNOTE, l.start(), end, cs, end, 0, m.group(1), (char) 0, null,
                    inline(text, cs, end), List.of()));
                i = j;
                continue;
            }
            if (s.contains("|") && i + 1 < lines.size() && DELIMITER_ROW.matcher(line(text, lines.get(i + 1))).matches()
                && line(text, lines.get(i + 1)).contains("-")) {
                var aligns = new ArrayList<String>();
                for (var cell : cells(line(text, lines.get(i + 1)), 0)) {
                    String c = line(text, lines.get(i + 1)).substring(cell[0], cell[1]).strip();
                    aligns.add(c.startsWith(":") && c.endsWith(":") ? "c" : c.endsWith(":") ? "r" : "l");
                }
                var rows = new ArrayList<Block>();
                int j = i;
                while (j < lines.size() && !blank(text, lines.get(j)) && line(text, lines.get(j)).contains("|")) {
                    if (j != i + 1) rows.add(row(text, lines.get(j)));
                    j++;
                }
                out.add(new Block(BlockKind.TABLE, l.start(), lines.get(j - 1).end(), l.start(), lines.get(j - 1).end(),
                    0, String.join(",", aligns), (char) 0, null, List.of(), rows));
                i = j;
                continue;
            }
            // A paragraph: up to a blank line or the start of another block.
            int j = i + 1;
            while (j < lines.size()) {
                String t = line(text, lines.get(j));
                if (t.isBlank() || HEADING.matcher(t).matches() || FENCE.matcher(t).matches() || QUOTE.matcher(t).lookingAt()
                    || LIST.matcher(t).lookingAt() || RULE.matcher(t).matches()) break;
                j++;
            }
            int end = lines.get(j - 1).end();
            String id = null;
            var last = lines.get(j - 1);
            var idm = BLOCK_ID.matcher(line(text, last));
            if (idm.find()) id = idm.group(1);
            out.add(new Block(BlockKind.PARAGRAPH, l.start(), end, l.start(), end, 0, null, (char) 0, id,
                inline(text, l.start(), end), List.of()));
            i = j;
        }
        return out;
    }

    private static int columns(String indent) {
        int n = 0;
        for (char c : indent.toCharArray()) n += c == '\t' ? 4 - n % 4 : 1;
        return n;
    }

    /** The [start, end) of each cell in a table line, relative to the line, pipes excluded. */
    private static List<int[]> cells(String line, int base) {
        var out = new ArrayList<int[]>();
        int from = 0, to = line.length();
        String t = line.strip();
        int lead = line.indexOf(t.isEmpty() ? " " : t.substring(0, 1));
        if (t.startsWith("|")) from = lead + 1;
        if (t.endsWith("|") && t.length() > 1) to = line.lastIndexOf('|');
        int cellStart = from;
        for (int k = from; k < to; k++) {
            char c = line.charAt(k);
            if (c == '\\') { k++; continue; }
            if (c == '|' && !insideCode(line, from, k)) { out.add(new int[]{base + cellStart, base + k}); cellStart = k + 1; }
        }
        out.add(new int[]{base + cellStart, base + to});
        return out;
    }

    private static boolean insideCode(String line, int from, int at) {
        int ticks = 0;
        for (int k = from; k < at; k++) if (line.charAt(k) == '`') ticks++;
        return ticks % 2 == 1;
    }

    private static Block row(String text, Line l) {
        var cells = new ArrayList<Block>();
        for (var c : cells(line(text, l), l.start())) {
            int cs = c[0], ce = c[1];
            while (cs < ce && Character.isWhitespace(text.charAt(cs))) cs++;
            while (ce > cs && Character.isWhitespace(text.charAt(ce - 1))) ce--;
            cells.add(new Block(BlockKind.TABLE_CELL, c[0], c[1], cs, ce, 0, null, (char) 0, null, inline(text, cs, ce), List.of()));
        }
        return new Block(BlockKind.TABLE_ROW, l.start(), l.end(), l.start(), l.end(), 0, null, (char) 0, null, List.of(), cells);
    }

    // ---------------------------------------------------------------- inline

    private static final Pattern URL = Pattern.compile("(?:https?://|mailto:)[^\\s<>()\\[\\]]+[^\\s<>()\\[\\].,;:!?'\"*_~]");
    private static final String TAG_CHAR = "[\\p{L}\\p{N}_/-]";
    private static final Pattern TAG = Pattern.compile("#(" + TAG_CHAR + "*[\\p{L}_/-]" + TAG_CHAR + "*)");

    /** The inline spans in [from, to). Atomic spans first, then emphasis in the text between them. */
    public static List<Span> inline(String text, int from, int to) {
        var atoms = new ArrayList<Span>();
        int i = from;
        while (i < to) {
            char c = text.charAt(i);
            Span s = null;
            if (c == '\\' && i + 1 < to && "\\`*_{}[]()#+-.!|~=$%^<>".indexOf(text.charAt(i + 1)) >= 0) {
                s = new Span(SpanKind.ESCAPE, i, i + 2, i + 1, i + 2, null, null, null);
            } else if (c == '`') {
                int run = runOf(text, i, to, '`');
                int close = findRun(text, i + run, to, '`', run);
                if (close >= 0) s = new Span(SpanKind.CODE, i, close + run, i + run, close, null, null, null);
                else { i += run; continue; }
            } else if (c == '%' && at(text, i, to, "%%")) {
                int close = text.indexOf("%%", i + 2);
                if (close >= 0 && close + 2 <= to) s = new Span(SpanKind.COMMENT, i, close + 2, i + 2, close, null, null, null);
            } else if (c == '$' && i + 1 < to && text.charAt(i + 1) != '$' && !Character.isWhitespace(text.charAt(i + 1))) {
                int close = i + 1;
                while (close < to && (text.charAt(close) != '$' || text.charAt(close - 1) == '\\')) close++;
                if (close < to && !Character.isWhitespace(text.charAt(close - 1))
                    && (close + 1 >= to || !Character.isDigit(text.charAt(close + 1))))
                    s = new Span(SpanKind.MATH, i, close + 1, i + 1, close, null, null, null);
            } else if (c == '!' && at(text, i, to, "![[")) {
                s = wiki(text, i + 1, to, true);
            } else if (c == '[' && at(text, i, to, "[[")) {
                s = wiki(text, i, to, false);
            } else if (c == '[' && at(text, i, to, "[^")) {
                int close = text.indexOf(']', i + 2);
                if (close > i + 2 && close < to && text.substring(i + 2, close).chars().noneMatch(Character::isWhitespace))
                    s = new Span(SpanKind.FOOTNOTE_REF, i, close + 1, i + 2, close, text.substring(i + 2, close), null, null);
            } else if (c == '^' && at(text, i, to, "^[")) {
                int close = matching(text, i + 1, to, '[', ']');
                if (close > 0) s = new Span(SpanKind.FOOTNOTE_REF, i, close + 1, i + 2, close, null, null, null);
            } else if (c == '!' && at(text, i, to, "![")) {
                s = markdownLink(text, i + 1, to, true);
            } else if (c == '[') {
                s = markdownLink(text, i, to, false);
            } else if ((c == 'h' || c == 'm') && (i == from || !Character.isLetterOrDigit(text.charAt(i - 1)))) {
                var um = URL.matcher(text).region(i, to);
                if (um.lookingAt()) s = new Span(SpanKind.URL, i, um.end(), i, um.end(), text.substring(i, um.end()), null, null);
            } else if (c == '#' && (i == from || Character.isWhitespace(text.charAt(i - 1)) || text.charAt(i - 1) == '(')) {
                var tm = TAG.matcher(text).region(i, to);
                if (tm.lookingAt()) s = new Span(SpanKind.TAG, i, tm.end(), i + 1, tm.end(), tm.group(1), null, null);
            } else if (c == '^' && (i == from || text.charAt(i - 1) == ' ' || text.charAt(i - 1) == '\t')) {
                var bm = Pattern.compile("\\^([A-Za-z0-9-]+)[ \\t]*$").matcher(text).region(i, lineEnd(text, i, to));
                if (bm.lookingAt()) s = new Span(SpanKind.BLOCK_ID, i, i + 1 + bm.group(1).length(), i + 1,
                    i + 1 + bm.group(1).length(), bm.group(1), null, null);
            }
            if (s != null) { atoms.add(s); i = s.end(); }
            else i++;
        }
        var out = new ArrayList<Span>(atoms);
        // Emphasis lives in the text between atoms, and inside a link's label.
        int at = from;
        for (var a : atoms) {
            emphasis(text, at, a.start(), out);
            if (a.kind() == SpanKind.LINK) emphasis(text, a.contentStart(), a.contentEnd(), out);
            at = a.end();
        }
        emphasis(text, at, to, out);
        out.sort(Comparator.comparingInt(Span::start).thenComparing(Comparator.comparingInt(Span::end).reversed()));
        return out;
    }

    private static int lineEnd(String text, int from, int to) {
        int nl = text.indexOf('\n', from);
        return nl < 0 || nl > to ? to : nl;
    }

    private static boolean at(String text, int i, int to, String token) {
        return i + token.length() <= to && text.startsWith(token, i);
    }

    private static int runOf(String text, int i, int to, char c) {
        int n = 0;
        while (i + n < to && text.charAt(i + n) == c) n++;
        return n;
    }

    private static int findRun(String text, int from, int to, char c, int run) {
        for (int k = from; k < to; k++) {
            if (text.charAt(k) != c) continue;
            int n = runOf(text, k, to, c);
            if (n == run) return k;
            k += n - 1;
        }
        return -1;
    }

    private static int matching(String text, int open, int to, char o, char cl) {
        int depth = 0;
        for (int k = open; k < to; k++) {
            char c = text.charAt(k);
            if (c == '\\') { k++; continue; }
            if (c == o) depth++;
            else if (c == cl && --depth == 0) return k;
            else if (c == '\n' && k + 1 < to && text.charAt(k + 1) == '\n') return -1;
        }
        return -1;
    }

    /** [[target#anchor|alias]], starting at the first bracket. */
    private static Span wiki(String text, int open, int to, boolean embed) {
        int close = text.indexOf("]]", open + 2);
        if (close < 0 || close + 2 > to) return null;
        String inner = text.substring(open + 2, close);
        if (inner.isBlank() || inner.contains("\n") || inner.contains("[[")) return null;
        String target = inner, alias = null, anchor = null;
        int bar = inner.indexOf('|');
        if (bar >= 0) { target = inner.substring(0, bar); alias = inner.substring(bar + 1); }
        int hash = target.indexOf('#');
        if (hash >= 0) { anchor = target.substring(hash + 1); target = target.substring(0, hash); }
        int start = embed ? open - 1 : open;
        return new Span(embed ? SpanKind.EMBED : SpanKind.WIKILINK, start, close + 2, open + 2, close,
            target.strip(), anchor == null ? null : anchor.strip(), alias == null ? null : alias.strip());
    }

    /** [label](address "title") or ![alt](address), starting at the bracket. */
    private static Span markdownLink(String text, int open, int to, boolean image) {
        int closeLabel = matching(text, open, to, '[', ']');
        if (closeLabel < 0 || closeLabel + 1 >= to || text.charAt(closeLabel + 1) != '(') return null;
        int closeUrl = matching(text, closeLabel + 1, to, '(', ')');
        if (closeUrl < 0) return null;
        String url = text.substring(closeLabel + 2, closeUrl).strip();
        if (url.startsWith("<") && url.contains(">")) url = url.substring(1, url.indexOf('>'));
        else {
            int space = url.indexOf(' ');
            if (space > 0) url = url.substring(0, space);
        }
        if (url.isEmpty() || url.contains("\n")) return null;
        String anchor = null;
        int hash = url.indexOf('#');
        if (hash >= 0 && !url.contains("://")) { anchor = url.substring(hash + 1); url = url.substring(0, hash); }
        int start = image ? open - 1 : open;
        return new Span(image ? SpanKind.IMAGE : SpanKind.LINK, start, closeUrl + 1, open + 1, closeLabel,
            url, anchor, text.substring(open + 1, closeLabel));
    }

    /**
     * Bold, italic, strikethrough and highlight in plain text. A delimiter opens
     * when the next character is not whitespace and closes when the previous one
     * is not; an underscore never opens or closes inside a word.
     */
    private static void emphasis(String text, int from, int to, List<Span> out) {
        record Open(String token, int at) { }
        var stack = new ArrayDeque<Open>();
        int i = from;
        while (i < to) {
            char c = text.charAt(i);
            if (c == '\\') { i += 2; continue; }
            String token = null;
            if (c == '*' || c == '_') token = at(text, i, to, "" + c + c) ? "" + c + c : "" + c;
            else if (c == '~' && at(text, i, to, "~~")) token = "~~";
            else if (c == '=' && at(text, i, to, "==")) token = "==";
            if (token == null) { i++; continue; }
            int after = i + token.length();
            boolean canOpen = after < to && !Character.isWhitespace(text.charAt(after));
            boolean canClose = i > from && !Character.isWhitespace(text.charAt(i - 1));
            if (c == '_') {
                boolean wordBefore = i > from && Character.isLetterOrDigit(text.charAt(i - 1));
                boolean wordAfter = after < to && Character.isLetterOrDigit(text.charAt(after));
                canOpen &= !wordBefore;
                canClose &= !wordAfter;
            }
            Open match = null;
            if (canClose) for (var o : stack) if (o.token().equals(token)) { match = o; break; }
            if (match != null && match.at() + token.length() < i) {
                while (stack.peek() != match) stack.pop();
                stack.pop();
                SpanKind kind = switch (token) {
                    case "**", "__" -> SpanKind.BOLD;
                    case "*", "_" -> SpanKind.ITALIC;
                    case "~~" -> SpanKind.STRIKE;
                    default -> SpanKind.HIGHLIGHT;
                };
                out.add(new Span(kind, match.at(), after, match.at() + token.length(), i, null, null, null));
                i = after;
            } else if (canOpen) {
                stack.push(new Open(token, i));
                i = after;
            } else i = after;
        }
    }
}
