package dev.yoru.pages;

import dev.yoru.domain.Model.*;
import java.util.*;

/**
 * What the Pages screen asks of every page at once: what links to a page,
 * which links lead nowhere yet, and which pages hold some words.
 *
 * Each live page is read once and its links kept by name; which page a name
 * means is decided at question time by a Resolver over the current tree. So
 * editing a page re-reads that page alone, and only a change to the tree —
 * a rename, a move, a new or trashed page — needs the Resolver built again.
 */
public final class PageIndex {
    /** A link from one page, with its line of text for showing in context. */
    public record Mention(UUID source, int start, int end, int lineStart, String line) { }

    /** A search hit: the page, and the lines that matched with their offsets. */
    public record Hit(Page page, List<Mention> lines) { }

    private Notes notes;
    private Links.Resolver resolver;
    private final Map<UUID, List<Links.Ref>> refs = new HashMap<>();
    private final Map<UUID, String> lower = new HashMap<>();

    public PageIndex(Notes notes) { rebuild(notes); }

    /** Reads every live page again: after a rename, a move, a new page or anything trashed. */
    public void rebuild(Notes notes) {
        this.notes = notes;
        resolver = new Links.Resolver(notes);
        refs.clear();
        lower.clear();
        for (var p : notes.pages()) if (!p.trashed()) read(p);
    }

    /**
     * Keeps up with one change. A page whose text alone changed is re-read by
     * itself; any change to titles, folders or the trash rebuilds.
     */
    public void update(Notes next) {
        var before = notes;
        boolean sameTree = before.folders().equals(next.folders()) && before.pages().size() == next.pages().size();
        if (sameTree) {
            var old = new HashMap<UUID, Page>();
            for (var p : before.pages()) old.put(p.id(), p);
            for (var p : next.pages()) {
                var o = old.get(p.id());
                if (o == null || !Objects.equals(o.title(), p.title()) || !Objects.equals(o.folderId(), p.folderId())
                    || o.trashed() != p.trashed()) { sameTree = false; break; }
            }
        }
        if (!sameTree) { rebuild(next); return; }
        var old = new HashMap<UUID, Page>();
        for (var p : notes.pages()) old.put(p.id(), p);
        notes = next;
        resolver = new Links.Resolver(next);
        for (var p : next.pages()) if (!p.trashed() && !p.body().equals(old.get(p.id()).body())) read(p);
    }

    private void read(Page p) {
        refs.put(p.id(), Links.refs(p.body()));
        lower.put(p.id(), p.body().toLowerCase(Locale.ROOT));
    }

    public Links.Resolver resolver() { return resolver; }

    /** Every link to a page, grouped by the page it is in, in tree order. */
    public List<Mention> backlinks(UUID target) {
        var out = new ArrayList<Mention>();
        for (var p : notes.pages()) {
            if (p.trashed()) continue;
            for (var ref : refs.getOrDefault(p.id(), List.of()))
                if (resolver.resolve(ref.target(), p).map(t -> t.id().equals(target)).orElse(false))
                    out.add(mention(p, ref.start(), ref.end()));
        }
        return out;
    }

    /** Link names that lead to no page yet, each with where it is written. */
    public Map<String, List<Mention>> unresolved() {
        var out = new TreeMap<String, List<Mention>>(String.CASE_INSENSITIVE_ORDER);
        for (var p : notes.pages()) {
            if (p.trashed()) continue;
            for (var ref : refs.getOrDefault(p.id(), List.of()))
                if (!ref.target().isEmpty() && resolver.resolve(ref.target(), p).isEmpty())
                    out.computeIfAbsent(ref.target(), k -> new ArrayList<>()).add(mention(p, ref.start(), ref.end()));
        }
        return out;
    }

    /**
     * Pages holding every word of the query, ignoring case; "quoted words"
     * must appear together, and a title match counts as well as a body match.
     * Pages whose title matches come first.
     */
    public List<Hit> search(String query) {
        var terms = terms(query);
        if (terms.isEmpty()) return List.of();
        var titled = new ArrayList<Hit>();
        var bodied = new ArrayList<Hit>();
        for (var p : notes.pages()) {
            if (p.trashed()) continue;
            String body = lower.getOrDefault(p.id(), "");
            String title = p.title().toLowerCase(Locale.ROOT);
            boolean all = true, inTitle = true;
            for (var t : terms) {
                boolean b = body.contains(t), ti = title.contains(t);
                if (!b && !ti) { all = false; break; }
                inTitle &= ti;
            }
            if (!all) continue;
            var lines = new ArrayList<Mention>();
            for (var t : terms) {
                int at = body.indexOf(t);
                while (at >= 0 && lines.size() < 5) {
                    var m = mention(p, at, at + t.length());
                    if (lines.stream().noneMatch(l -> l.lineStart() == m.lineStart())) lines.add(m);
                    at = body.indexOf(t, at + t.length());
                }
            }
            lines.sort(Comparator.comparingInt(Mention::start));
            (inTitle ? titled : bodied).add(new Hit(p, lines));
        }
        titled.addAll(bodied);
        return titled;
    }

    static List<String> terms(String query) {
        var out = new ArrayList<String>();
        var q = query.toLowerCase(Locale.ROOT).strip();
        int i = 0;
        while (i < q.length()) {
            if (Character.isWhitespace(q.charAt(i))) { i++; continue; }
            if (q.charAt(i) == '"') {
                int close = q.indexOf('"', i + 1);
                String phrase = close < 0 ? q.substring(i + 1) : q.substring(i + 1, close);
                if (!phrase.isBlank()) out.add(phrase.strip());
                i = close < 0 ? q.length() : close + 1;
            } else {
                int end = i;
                while (end < q.length() && !Character.isWhitespace(q.charAt(end))) end++;
                out.add(q.substring(i, end));
                i = end;
            }
        }
        return out;
    }

    private static Mention mention(Page p, int start, int end) {
        String body = p.body();
        int ls = body.lastIndexOf('\n', Math.max(0, start - 1)) + 1;
        if (start == 0) ls = 0;
        int le = body.indexOf('\n', end);
        if (le < 0) le = body.length();
        return new Mention(p.id(), start, end, ls, body.substring(ls, le));
    }
}
