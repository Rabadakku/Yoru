package dev.yoru.pages;

import dev.yoru.domain.Model.*;
import dev.yoru.pages.Markdown.*;
import java.util.*;

/**
 * Which page a link means, how to write a link to a page, and how to keep every
 * link meaning the same page when pages are renamed or moved.
 *
 * Resolution follows Obsidian. [[Name]] means the page titled Name, ignoring
 * case; [[Folder/Name]] narrows it by path. When several live pages share a
 * title, the one in the linking page's own folder wins, then the one with the
 * shortest path, then the first by path — so the answer never depends on the
 * order pages happen to be stored in. Pages in the trash are never a link's
 * answer; a link to one reads as unresolved until it is restored.
 */
public final class Links {
    private Links() { }

    /**
     * A link in a page's text. [targetStart, targetEnd) is the part naming the
     * page, which is the only part a rename rewrites; markdown marks a
     * [label](address) link, whose address is URL-encoded.
     */
    public record Ref(int start, int end, int targetStart, int targetEnd, String target, String anchor,
                      String alias, boolean embed, boolean markdown) { }

    /** Every link in this text that could name a page: not web addresses, not attachments. */
    public static List<Ref> refs(String text) { return refs(Markdown.parse(text)); }

    public static List<Ref> refs(Doc doc) {
        var out = new ArrayList<Ref>();
        String text = doc.text();
        for (var s : doc.allSpans()) {
            if (s.kind() == SpanKind.WIKILINK || s.kind() == SpanKind.EMBED) {
                String inner = text.substring(s.contentStart(), s.contentEnd());
                int cut = inner.length();
                for (char stop : new char[]{'#', '|'}) { int k = inner.indexOf(stop); if (k >= 0) cut = Math.min(cut, k); }
                if (attachment(s.target())) continue;
                out.add(new Ref(s.start(), s.end(), s.contentStart(), s.contentStart() + cut, s.target(), s.anchor(),
                    s.alias(), s.kind() == SpanKind.EMBED, false));
            } else if (s.kind() == SpanKind.LINK || s.kind() == SpanKind.IMAGE) {
                String address = s.target();
                if (address.contains("://") || address.startsWith("mailto:") || attachment(decode(address))) continue;
                // The address sits after "](", possibly inside angle brackets.
                int open = text.indexOf("](", s.contentEnd()) + 2;
                while (open < s.end() && (text.charAt(open) == ' ' || text.charAt(open) == '<')) open++;
                int addrEnd = text.indexOf(address, open) >= 0 ? text.indexOf(address, open) + address.length() : open;
                String name = stripMd(decode(address));
                out.add(new Ref(s.start(), s.end(), open, addrEnd, name, s.anchor(), s.alias(), s.kind() == SpanKind.IMAGE, true));
            }
        }
        return out;
    }

    /**
     * The file types a link can name instead of a page. Only these: a title
     * may hold a dot ("Report v1.2"), and Obsidian likewise treats a name as a
     * file only when its extension is one it knows.
     */
    private static final Set<String> FILE_TYPES = Set.of(
        "png", "jpg", "jpeg", "gif", "bmp", "svg", "webp", "avif", "heic", "tif", "tiff", "ico",
        "pdf", "mp3", "wav", "m4a", "ogg", "flac", "3gp", "webm", "mp4", "mov", "mkv", "ogv",
        "csv", "txt", "json", "docx", "doc", "xlsx", "xls", "pptx", "ppt", "zip", "canvas", "base");

    /** A name ending in a known file type is a file, not a page. */
    static boolean attachment(String target) {
        String name = target.substring(target.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 && FILE_TYPES.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    static String stripMd(String name) {
        return name.regionMatches(true, Math.max(0, name.length() - 3), ".md", 0, 3) && name.length() > 3
            ? name.substring(0, name.length() - 3) : name;
    }

    /** %XX sequences to characters; a malformed one stays as written. */
    static String decode(String s) {
        if (s.indexOf('%') < 0) return s;
        var bytes = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                try { bytes.write(Integer.parseInt(s.substring(i + 1, i + 3), 16)); i += 2; continue; }
                catch (NumberFormatException ignored) { }
            }
            byte[] b = String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            bytes.write(b, 0, b.length);
        }
        return bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    static String encode(String s) { return s.replace("%", "%25").replace(" ", "%20").replace("(", "%28").replace(")", "%29"); }

    // ---------------------------------------------------------------- resolving

    /** The live tree, indexed for resolving links against it. */
    public static final class Resolver {
        private final Map<UUID, Folder> folders = new HashMap<>();
        private final Map<UUID, Page> pages = new LinkedHashMap<>();
        private final Map<String, List<Page>> byTitle = new HashMap<>();
        private final Map<UUID, String> paths = new HashMap<>();

        public Resolver(Notes notes) {
            for (var f : notes.folders()) if (!f.trashed()) folders.put(f.id(), f);
            for (var p : notes.pages()) {
                if (p.trashed()) continue;
                pages.put(p.id(), p);
                byTitle.computeIfAbsent(key(p.title()), k -> new ArrayList<>()).add(p);
            }
        }

        private static String key(String s) { return s.toLowerCase(Locale.ROOT); }

        /** "Folder/Sub/Title", from the top of the tree. */
        public String path(Page page) {
            return paths.computeIfAbsent(page.id(), id -> {
                var parts = new ArrayDeque<String>();
                parts.push(page.title());
                for (var f = folders.get(page.folderId()); f != null; f = folders.get(f.parentId())) parts.push(f.name());
                return String.join("/", parts);
            });
        }

        public Optional<Page> page(UUID id) { return Optional.ofNullable(pages.get(id)); }

        /** The page a link's target means, seen from the page it is written in. */
        public Optional<Page> resolve(String target, Page from) {
            String t = stripMd(target.strip());
            while (t.startsWith("/")) t = t.substring(1);
            if (t.isEmpty()) return Optional.ofNullable(from).filter(p -> pages.containsKey(p.id()));
            List<Page> candidates;
            int slash = t.lastIndexOf('/');
            if (slash < 0) candidates = byTitle.getOrDefault(key(t), List.of());
            else {
                String suffix = key(t);
                candidates = byTitle.getOrDefault(key(t.substring(slash + 1)), List.of()).stream()
                    .filter(p -> { String path = key(path(p)); return path.equals(suffix) || path.endsWith("/" + suffix); })
                    .toList();
            }
            if (candidates.isEmpty()) return Optional.empty();
            if (candidates.size() == 1) return Optional.of(candidates.getFirst());
            return candidates.stream().min(Comparator
                .comparing((Page p) -> from == null || !Objects.equals(p.folderId(), from.folderId()))
                .thenComparingInt(p -> path(p).split("/").length)
                .thenComparing(p -> key(path(p))));
        }

        /**
         * The shortest text that makes a link written in {@code from} mean
         * {@code target}: its title when that is enough, otherwise as much of
         * its path as it takes.
         */
        public String linkText(Page target, Page from) {
            String[] parts = path(target).split("/");
            for (int n = 1; n <= parts.length; n++) {
                String text = String.join("/", Arrays.copyOfRange(parts, parts.length - n, parts.length));
                if (resolve(text, from).map(p -> p.id().equals(target.id())).orElse(false)) return text;
            }
            return path(target);
        }

        /** Live pages, in stored order. */
        public Collection<Page> pages() { return pages.values(); }
    }

    // ---------------------------------------------------------------- rewriting

    /**
     * The new text of every page whose links would change meaning between
     * {@code before} and {@code after}, rewritten so they do not.
     *
     * A link that meant a page before keeps meaning that page: renaming "Draft"
     * rewrites [[Draft]], and creating a second "Notes" nearer to a link
     * rewrites that link to the path of the one it always meant. A link to a
     * page now in the trash is left alone, to mean it again once restored; an
     * unresolved link is left alone, so that creating its page resolves it.
     * Only [targetStart, targetEnd) of a link is touched: its heading, alias
     * and brackets stay as the person wrote them.
     */
    public static Map<UUID, String> keepMeaning(Notes before, Notes after) {
        var was = new Resolver(before);
        var now = new Resolver(after);
        var out = new LinkedHashMap<UUID, String>();
        var beforePages = new HashMap<UUID, Page>();
        for (var p : before.pages()) beforePages.put(p.id(), p);
        for (var page : after.pages()) {
            if (page.trashed()) continue;
            var old = beforePages.get(page.id());
            var edits = new ArrayList<int[]>();
            var texts = new ArrayList<String>();
            var body = page.body();
            for (var ref : refs(body)) {
                var meant = was.resolve(ref.target(), old != null && !old.trashed() ? old : page);
                if (meant.isEmpty()) continue;
                var means = now.resolve(ref.target(), page);
                if (means.isPresent() && means.get().id().equals(meant.get().id())) continue;
                var target = now.page(meant.get().id());
                if (target.isEmpty()) continue;
                String text = now.linkText(target.get(), page);
                if (ref.markdown()) text = encode(text) + ".md";
                edits.add(new int[]{ref.targetStart(), ref.targetEnd()});
                texts.add(text);
            }
            if (edits.isEmpty()) continue;
            var b = new StringBuilder(body);
            for (int i = edits.size() - 1; i >= 0; i--) b.replace(edits.get(i)[0], edits.get(i)[1], texts.get(i));
            if (!b.toString().equals(body)) out.put(page.id(), b.toString());
        }
        return out;
    }
}
