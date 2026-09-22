package dev.yoru.ui;

import dev.yoru.pages.Markdown;
import dev.yoru.pages.Markdown.*;
import java.awt.Color;
import java.awt.Font;
import java.util.function.Predicate;
import javax.swing.text.*;
import static dev.yoru.ui.Theme.*;

/**
 * Styles the editor's Markdown as it is written: the text stays exactly as
 * typed, and only how each range is drawn changes.
 *
 * Headings grow with their level, emphasis and code look like themselves, and
 * the characters that make them — the hashes, asterisks and brackets — are
 * dimmed so the words stand out, as Obsidian's source mode does. A link whose
 * page exists is drawn in the accent; one that leads nowhere yet is drawn
 * muted, so a dangling link is visible at a glance.
 */
final class MarkdownStyler {
    private MarkdownStyler() { }

    /** Marks a range as a link, for the editor's Cmd/Ctrl-click to follow. */
    static final Object LINK_TARGET = new Object() { public String toString() { return "yoru.link"; } };

    static MutableAttributeSet base(Font font) {
        var a = new SimpleAttributeSet();
        StyleConstants.setFontFamily(a, font.getFamily());
        StyleConstants.setFontSize(a, font.getSize());
        StyleConstants.setForeground(a, TEXT);
        StyleConstants.setBold(a, false);
        StyleConstants.setItalic(a, false);
        StyleConstants.setUnderline(a, false);
        StyleConstants.setStrikeThrough(a, false);
        return a;
    }

    /**
     * Restyles the whole document from a fresh parse. resolves says whether a
     * link's target is a page that exists, so the two kinds can differ.
     */
    static void apply(StyledDocument document, Markdown.Doc doc, Font font, Predicate<String> resolves) {
        int length = document.getLength();
        if (length == 0) return;
        document.setCharacterAttributes(0, length, base(font), true);
        var paragraph = new SimpleAttributeSet();
        StyleConstants.setLineSpacing(paragraph, 0.25f);
        document.setParagraphAttributes(0, length, paragraph, false);
        for (var block : doc.allBlocks()) block(document, doc, block, font, length);
        for (var span : doc.allSpans()) span(document, span, font, length, resolves);
    }

    private static void block(StyledDocument d, Markdown.Doc doc, Block b, Font font, int length) {
        switch (b.kind()) {
            case HEADING -> {
                var a = new SimpleAttributeSet();
                StyleConstants.setBold(a, true);
                StyleConstants.setFontSize(a, Math.round(font.getSize() * switch (b.level()) {
                    case 1 -> 1.75f; case 2 -> 1.45f; case 3 -> 1.25f; case 4 -> 1.1f; default -> 1f; }));
                set(d, b.start(), b.end(), a, length);
                muted(d, b.start(), b.contentStart(), length);
            }
            case CODE, MATH, FRONTMATTER -> {
                var a = new SimpleAttributeSet();
                StyleConstants.setFontFamily(a, mono(TYPE_PROSE).getFamily());
                StyleConstants.setForeground(a, b.kind() == BlockKind.FRONTMATTER ? MUTED : TEXT);
                StyleConstants.setBackground(a, codeFill());
                set(d, b.start(), b.end(), a, length);
                if (b.kind() != BlockKind.FRONTMATTER) {
                    muted(d, b.start(), b.contentStart(), length);
                    muted(d, b.contentEnd(), b.end(), length);
                }
            }
            case COMMENT -> {
                var a = new SimpleAttributeSet();
                StyleConstants.setForeground(a, MUTED);
                StyleConstants.setItalic(a, true);
                set(d, b.start(), b.end(), a, length);
            }
            case QUOTE, CALLOUT -> {
                // The quote markers are dimmed; a callout's title stands out.
                String text = doc.text();
                for (int at = b.start(); at < b.end(); ) {
                    int nl = text.indexOf('\n', at);
                    int lineEnd = nl < 0 || nl > b.end() ? b.end() : nl;
                    int marker = at;
                    while (marker < lineEnd && (text.charAt(marker) == '>' || text.charAt(marker) == ' ')) marker++;
                    muted(d, at, marker, length);
                    at = lineEnd + 1;
                }
                if (b.kind() == BlockKind.CALLOUT) {
                    var a = new SimpleAttributeSet();
                    StyleConstants.setBold(a, true);
                    StyleConstants.setForeground(a, ACCENT_TEXT);
                    set(d, b.contentStart(), b.contentEnd(), a, length);
                    int open = text.indexOf("[!", b.start());
                    if (open >= 0 && open < b.contentStart()) muted(d, open, b.contentStart(), length);
                }
            }
            case LIST_ITEM -> {
                var marker = new SimpleAttributeSet();
                StyleConstants.setForeground(marker, ACCENT_TEXT);
                set(d, b.start(), b.contentStart(), marker, length);
                if (b.check() == 'x' || b.check() == 'X') {
                    var done = new SimpleAttributeSet();
                    StyleConstants.setForeground(done, MUTED);
                    StyleConstants.setStrikeThrough(done, true);
                    set(d, b.contentStart(), b.contentEnd(), done, length);
                }
            }
            case RULE, FOOTNOTE -> muted(d, b.start(), b.kind() == BlockKind.RULE ? b.end() : b.contentStart(), length);
            case TABLE -> {
                String text = doc.text();
                for (int i = b.start(); i < b.end(); i++) if (text.charAt(i) == '|') muted(d, i, i + 1, length);
            }
            default -> { }
        }
    }

    private static void span(StyledDocument d, Span s, Font font, int length, Predicate<String> resolves) {
        switch (s.kind()) {
            case BOLD -> { var a = new SimpleAttributeSet(); StyleConstants.setBold(a, true); inner(d, s, a, length); }
            case ITALIC -> { var a = new SimpleAttributeSet(); StyleConstants.setItalic(a, true); inner(d, s, a, length); }
            case STRIKE -> { var a = new SimpleAttributeSet(); StyleConstants.setStrikeThrough(a, true); inner(d, s, a, length); }
            case HIGHLIGHT -> { var a = new SimpleAttributeSet(); StyleConstants.setBackground(a, highlightFill()); inner(d, s, a, length); }
            case CODE -> {
                var a = new SimpleAttributeSet();
                StyleConstants.setFontFamily(a, mono(TYPE_PROSE).getFamily());
                StyleConstants.setBackground(a, codeFill());
                inner(d, s, a, length);
            }
            case MATH -> { var a = new SimpleAttributeSet(); StyleConstants.setFontFamily(a, mono(TYPE_PROSE).getFamily()); inner(d, s, a, length); }
            case COMMENT -> { var a = new SimpleAttributeSet(); StyleConstants.setForeground(a, MUTED); StyleConstants.setItalic(a, true); set(d, s.start(), s.end(), a, length); }
            case WIKILINK, EMBED -> {
                boolean known = resolves.test(s.target());
                var a = new SimpleAttributeSet();
                StyleConstants.setForeground(a, known ? ACCENT_TEXT : MUTED);
                StyleConstants.setUnderline(a, !known);
                a.addAttribute(LINK_TARGET, s);
                set(d, s.contentStart(), s.contentEnd(), a, length);
                var brackets = new SimpleAttributeSet();
                StyleConstants.setForeground(brackets, MUTED);
                brackets.addAttribute(LINK_TARGET, s);
                set(d, s.start(), s.contentStart(), brackets, length);
                set(d, s.contentEnd(), s.end(), brackets, length);
            }
            case LINK, IMAGE, URL -> {
                var a = new SimpleAttributeSet();
                StyleConstants.setForeground(a, ACCENT_TEXT);
                StyleConstants.setUnderline(a, s.kind() == SpanKind.URL);
                a.addAttribute(LINK_TARGET, s);
                set(d, s.start(), s.end(), a, length);
                if (s.kind() != SpanKind.URL) muted(d, s.contentEnd(), s.end(), length);
            }
            case TAG -> { var a = new SimpleAttributeSet(); StyleConstants.setForeground(a, GOLD_TEXT); set(d, s.start(), s.end(), a, length); }
            case ESCAPE, BLOCK_ID, FOOTNOTE_REF -> muted(d, s.start(), s.kind() == SpanKind.ESCAPE ? s.contentStart() : s.end(), length);
        }
    }

    /** A span's content styled, its markers dimmed. */
    private static void inner(StyledDocument d, Span s, AttributeSet a, int length) {
        set(d, s.contentStart(), s.contentEnd(), a, length);
        muted(d, s.start(), s.contentStart(), length);
        muted(d, s.contentEnd(), s.end(), length);
    }

    private static void muted(StyledDocument d, int start, int end, int length) {
        var a = new SimpleAttributeSet();
        StyleConstants.setForeground(a, MUTED);
        set(d, start, end, a, length);
    }

    private static void set(StyledDocument d, int start, int end, AttributeSet a, int length) {
        start = Math.max(0, Math.min(start, length));
        end = Math.max(start, Math.min(end, length));
        if (end > start) d.setCharacterAttributes(start, end - start, a, false);
    }

    /** A fill a shade off the page for code, darker in light themes and lighter in dark ones. */
    static Color codeFill() { return shade(PANEL, DARK ? 10 : -10); }
    static Color highlightFill() {
        return new Color(GOLD.getRed(), GOLD.getGreen(), GOLD.getBlue(), DARK ? 70 : 90);
    }
}
