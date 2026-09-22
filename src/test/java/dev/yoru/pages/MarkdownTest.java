package dev.yoru.pages;

import dev.yoru.pages.Markdown.*;
import java.util.*;

/**
 * The Obsidian-flavoured reader: every construct the Pages ticket lists, the
 * exact offsets each one reports, and that nothing any text can hold makes it
 * throw. Invented text only.
 */
public final class MarkdownTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static List<Span> spans(String text, SpanKind kind) {
        return Markdown.parse(text).allSpans().stream().filter(s -> s.kind() == kind).toList();
    }
    private static String cut(String text, Span s) { return text.substring(s.start(), s.end()); }
    private static String inner(String text, Span s) { return text.substring(s.contentStart(), s.contentEnd()); }
    private static List<Block> kinds(String text, BlockKind kind) {
        return Markdown.parse(text).allBlocks().stream().filter(b -> b.kind() == kind).toList();
    }

    private static void frontmatterAndHeadings() {
        var text = "---\ntags: [bio]\naliases: [Cell notes]\n---\n# Cells\n## Membranes ##\n#tag is not a heading\n####### seven";
        var doc = Markdown.parse(text);
        var fm = doc.blocks().getFirst();
        check(fm.kind() == BlockKind.FRONTMATTER, "Frontmatter at the top is read as frontmatter");
        check(text.substring(fm.contentStart(), fm.contentEnd()).equals("tags: [bio]\naliases: [Cell notes]"),
            "Its content is the lines between the fences");
        var headings = doc.headings();
        check(headings.size() == 2, "Two headings, and neither the tag line nor seven hashes is one");
        check(headings.get(0).level() == 1 && doc.headingText(headings.get(0)).equals("Cells"), "A level 1 heading and its text");
        check(headings.get(1).level() == 2 && doc.headingText(headings.get(1)).equals("Membranes"),
            "Closing hashes are not part of the heading text");
        check(Markdown.parse("Intro\n---\nnot frontmatter").blocks().stream().noneMatch(b -> b.kind() == BlockKind.FRONTMATTER),
            "Dashes below the top are a rule, not frontmatter");
        check(kinds("# Heading ^h1", BlockKind.HEADING).getFirst().blockId().equals("h1"), "A heading can carry a block id");
    }

    private static void emphasisAndCode() {
        var text = "Some **bold**, *italic*, _also_, ~~gone~~, ==marked== and `a [[not link]] *x*`.";
        check(inner(text, spans(text, SpanKind.BOLD).getFirst()).equals("bold"), "Bold and its content");
        var italics = spans(text, SpanKind.ITALIC);
        check(italics.size() == 2 && inner(text, italics.get(0)).equals("italic") && inner(text, italics.get(1)).equals("also"),
            "Italic with either marker");
        check(inner(text, spans(text, SpanKind.STRIKE).getFirst()).equals("gone"), "Strikethrough");
        check(inner(text, spans(text, SpanKind.HIGHLIGHT).getFirst()).equals("marked"), "Highlight");
        var code = spans(text, SpanKind.CODE).getFirst();
        check(inner(text, code).equals("a [[not link]] *x*"), "Inline code keeps its content raw");
        check(spans(text, SpanKind.WIKILINK).isEmpty(), "A link inside code is not a link");
        check(spans(text, SpanKind.ITALIC).stream().noneMatch(s -> s.start() > code.start()), "Nor is emphasis inside code");
        check(spans("snake_case_name", SpanKind.ITALIC).isEmpty(), "Underscores inside a word are not emphasis");
        check(spans("2 * 3 * 4", SpanKind.ITALIC).isEmpty(), "A spaced-out asterisk is not emphasis");
        check(spans("\\*not italic\\*", SpanKind.ITALIC).isEmpty() && spans("\\*not italic\\*", SpanKind.ESCAPE).size() == 2,
            "Escaped markers stay literal");
        check(inner("``code with ` inside``", spans("``code with ` inside``", SpanKind.CODE).getFirst()).equals("code with ` inside"),
            "A longer backtick run can hold a shorter one");
    }

    private static void links() {
        var text = "See [[Lecture 2]], [[Lecture 2|the next one]], [[Biology/Lecture 3#Membranes]], "
            + "[[Lecture 4#^key-point|that point]] and ![[diagram.png|300]] and ![[Summary#Results]].";
        var wiki = spans(text, SpanKind.WIKILINK);
        check(wiki.size() == 4, "Four links");
        check(wiki.get(0).target().equals("Lecture 2") && wiki.get(0).anchor() == null && wiki.get(0).alias() == null, "A plain link");
        check(cut(text, wiki.get(0)).equals("[[Lecture 2]]"), "A link's range is exactly its brackets");
        check(wiki.get(1).alias().equals("the next one"), "An aliased link");
        check(wiki.get(2).target().equals("Biology/Lecture 3") && wiki.get(2).anchor().equals("Membranes"), "A path and a heading");
        check(wiki.get(3).anchor().equals("^key-point") && wiki.get(3).alias().equals("that point"), "A block link with an alias");
        var embeds = spans(text, SpanKind.EMBED);
        check(embeds.size() == 2 && embeds.get(0).target().equals("diagram.png") && embeds.get(0).alias().equals("300"),
            "An embedded image with a width");
        check(cut(text, embeds.get(0)).equals("![[diagram.png|300]]"), "An embed's range includes the bang");
        check(embeds.get(1).anchor().equals("Results"), "An embedded section");

        var md = "A [label *em*](Some%20Page.md#Heading) link, ![alt](img/cat.png \"title\") and <https://x.org> "
            + "and https://example.org/path?q=1. and [broken](";
        var link = spans(md, SpanKind.LINK).getFirst();
        check(link.target().equals("Some%20Page.md") && link.anchor().equals("Heading") && link.alias().equals("label *em*"),
            "A Markdown link: address, heading and label");
        check(spans(md, SpanKind.ITALIC).size() == 1, "Emphasis inside a link's label");
        check(spans(md, SpanKind.IMAGE).getFirst().target().equals("img/cat.png"), "An image, title dropped");
        var urls = spans(md, SpanKind.URL);
        check(urls.stream().anyMatch(u -> u.target().equals("https://example.org/path?q=1")), "A bare URL, sentence full stop excluded");
        check(spans("[[ ]] and [[unclosed", SpanKind.WIKILINK).isEmpty(), "Empty or unclosed brackets are not links");
    }

    private static void tagsAndIds() {
        var text = "#study and #area/topic, but not #123, a#b or https://x.org/#frag. Also (#inparens).";
        var tags = spans(text, SpanKind.TAG).stream().map(Span::target).toList();
        check(tags.equals(List.of("study", "area/topic", "inparens")), "Tags, nested tags; not numbers, mid-word or URL fragments: " + tags);
        var para = kinds("A point worth linking to ^key-point", BlockKind.PARAGRAPH).getFirst();
        check("key-point".equals(para.blockId()), "A paragraph's block id");
        check(spans("A point ^key-point", SpanKind.BLOCK_ID).getFirst().target().equals("key-point"), "and its span, for styling");
    }

    private static void lists() {
        var text = "- one\n  continued\n- [ ] todo\n- [x] done\n  - [/] nested half\n1. first\n2) second\n* star";
        var items = kinds(text, BlockKind.LIST_ITEM);
        check(items.size() == 7, "Seven items, continuation folded in: " + items.size());
        check(text.substring(items.get(0).contentStart(), items.get(0).contentEnd()).equals("one\n  continued"),
            "A continuation line belongs to its item");
        check(items.get(1).check() == ' ' && items.get(2).check() == 'x' && items.get(3).check() == '/', "Checkbox states");
        check(text.substring(items.get(1).contentStart(), items.get(1).contentEnd()).equals("todo"), "A checkbox is not content");
        check(items.get(3).level() == 2, "Nesting is the indent");
        check(items.get(4).info().equals("1.") && items.get(5).info().equals("2)") && items.get(6).info().equals("*"), "Markers");
        check(items.get(0).check() == 0, "An item without a box has no check");
    }

    private static void quotesAndCallouts() {
        var text = "> [!warning]- Mind the gap\n> Body with [[Link]]\n> - item\n\n> plain quote\n> > nested";
        var callout = kinds(text, BlockKind.CALLOUT).getFirst();
        check(callout.info().equals("warning") && callout.check() == '-', "A folded warning callout");
        check(text.substring(callout.contentStart(), callout.contentEnd()).equals("Mind the gap"), "and its title");
        check(callout.children().stream().anyMatch(b -> b.kind() == BlockKind.PARAGRAPH)
            && callout.children().stream().anyMatch(b -> b.kind() == BlockKind.LIST_ITEM), "Its body is parsed as blocks");
        check(spans(text, SpanKind.WIKILINK).getFirst().target().equals("Link"), "A link inside a callout is found, offsets intact");
        check(cut(text, spans(text, SpanKind.WIKILINK).getFirst()).equals("[[Link]]"), "at the right place in the page");
        var quotes = kinds(text, BlockKind.QUOTE);
        check(quotes.size() == 2, "A quote, and a quote nested in it");
    }

    private static void codeMathCommentsTables() {
        var text = "```java\nvar x = \"[[not a link]]\";\n```\n$$\nE = mc^2\n$$\n%%\nhidden [[not]]\n%%\n"
            + "| Name | Size |\n|:-----|-----:|\n| [[Cell]] | `1 \\| 2` |\n| b | c |\n\n---\nPrice is $5 or $6, math is $x^2$.\n"
            + "Note[^1] and inline^[aside].\n\n[^1]: The footnote.";
        var code = kinds(text, BlockKind.CODE).getFirst();
        check("java".equals(code.info()), "A fenced code block keeps its language");
        check(text.substring(code.contentStart(), code.contentEnd()).equals("var x = \"[[not a link]]\";"), "and its body, raw");
        check(kinds(text, BlockKind.MATH).size() == 1 && kinds(text, BlockKind.COMMENT).size() == 1, "A maths block and a comment block");
        check(spans(text, SpanKind.WIKILINK).size() == 1 && spans(text, SpanKind.WIKILINK).getFirst().target().equals("Cell"),
            "Only the link in the table counts; code, maths and comments hide theirs");
        var table = kinds(text, BlockKind.TABLE).getFirst();
        check(table.info().equals("l,r"), "Column alignment");
        check(table.children().size() == 3 && table.children().get(1).children().size() == 2, "Header plus two rows of two cells");
        var cell = table.children().get(1).children().get(1);
        check(text.substring(cell.contentStart(), cell.contentEnd()).equals("`1 \\| 2`"), "A pipe inside code does not split a cell");
        check(kinds(text, BlockKind.RULE).size() == 1, "A rule");
        var math = spans(text, SpanKind.MATH);
        check(math.size() == 1 && inner(text, math.getFirst()).equals("x^2"), "Inline maths, but not prices");
        check(spans(text, SpanKind.FOOTNOTE_REF).size() == 2, "A footnote reference and an inline footnote");
        check(kinds(text, BlockKind.FOOTNOTE).getFirst().info().equals("1"), "and the footnote itself");
        check(spans("a %%quiet%% word", SpanKind.COMMENT).size() == 1, "An inline comment");
        var unclosed = Markdown.parse("```\nnever closed\n[[Still code]]");
        check(unclosed.allSpans().isEmpty() && unclosed.blocks().getFirst().kind() == BlockKind.CODE,
            "An unclosed fence runs to the end, as in Obsidian");
    }

    /** Random text built from the characters Markdown cares about: nothing may throw or point outside the text. */
    private static void fuzz() {
        var random = new Random(46);
        String[] pieces = {"#", "##", " ", "\n", "\n\n", "*", "**", "_", "~~", "==", "`", "```", "[", "]", "[[", "]]", "![[",
            "(", ")", "|", "-", "- [ ] ", "1. ", ">", "> [!note]", "$", "$$", "%%", "^", "^[", "[^1]", "\\", "---", "word",
            "Page", "http://a.b/c", "#tag", "\t", "日本", "\r\n", "  "};
        for (int round = 0; round < 3000; round++) {
            var b = new StringBuilder();
            int n = random.nextInt(40);
            for (int i = 0; i < n; i++) b.append(pieces[random.nextInt(pieces.length)]);
            String text = b.toString();
            Doc doc;
            try { doc = Markdown.parse(text); }
            catch (RuntimeException e) { throw new AssertionError("Parsing threw on: " + text.replace("\n", "\\n"), e); }
            for (var block : doc.allBlocks())
                if (block.start() < 0 || block.end() > text.length() || block.start() > block.end()
                    || block.contentStart() < block.start() || block.contentEnd() > block.end() || block.contentStart() > block.contentEnd())
                    throw new AssertionError("A block points outside the text: " + block + " in " + text.replace("\n", "\\n"));
            for (var span : doc.allSpans())
                if (span.start() < 0 || span.end() > text.length() || span.start() >= span.end()
                    || span.contentStart() < span.start() || span.contentEnd() > span.end() || span.contentStart() > span.contentEnd())
                    throw new AssertionError("A span points outside the text: " + span + " in " + text.replace("\n", "\\n"));
            checks++;
        }
    }

    public static void main(String[] args) {
        frontmatterAndHeadings();
        emphasisAndCode();
        links();
        tagsAndIds();
        lists();
        quotesAndCallouts();
        codeMathCommentsTables();
        fuzz();
        System.out.println("PASS: " + checks + " Markdown checks (Obsidian syntax, exact offsets, never throws)");
    }
}
