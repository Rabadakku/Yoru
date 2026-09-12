package dev.yoru.importer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * An invented Notion "Markdown & CSV" export, in the shape Notion produces.
 *
 * Notion zips a database as one CSV beside a folder of Markdown pages, one file
 * per row, each named for its row plus a hex hash. Everything here is written
 * from scratch — the same discipline {@code Gen3Fixture} follows for saves — so
 * the importer is exercised against the real shape without any real export,
 * class list or task title ever existing in this repository.
 */
public final class NotionFixture {
    private NotionFixture() { }

    public static final String DATABASE = "Study Tasks";

    /** The database as Notion writes it: quoted dates, a quoted multi-value tag. */
    public static final String CSV = String.join("\r\n",
        "Name,Status,Class,Due",
        "Read the first chapter,Not started,Biology,\"September 12, 2026\"",
        "Sketch the water cycle,In progress,Geography,\"September 15, 2026\"",
        "Solve problem set 3,Done,Mathematics,\"2026-09-20\"",
        "Plan the group presentation,Not started,\"Biology, Mathematics\",\"2026/09/22\"",
        "Revise the timeline,Not started,History,") + "\r\n";

    /** The same database with a checkbox property instead of a status. */
    public static final String CHECKBOX_CSV = String.join("\n",
        "Name,Done,Class,Due",
        "Read the first chapter,Yes,Biology,2026-09-12",
        "Sketch the water cycle,No,Geography,2026-09-15") + "\n";

    /** Notion appends a 32-character hash to every exported file name. */
    private static final String HASH_CHAPTER = "0f1e2d3c4b5a69788796a5b4c3d2e1f0";
    private static final String HASH_PROBLEMS = "1a2b3c4d5e6f708192a3b4c5d6e7f809";
    private static final String HASH_OTHER = "2b3c4d5e6f708192a3b4c5d6e7f80910";

    private static final String PAGE_CHAPTER = """
        # Read the first chapter

        Status: Not started
        Class: Biology
        Due: September 12, 2026

        Read pages 1-30 and write down the key terms.

        - [ ] Answer the review questions
        """;

    private static final String PAGE_PROBLEMS = """
        # Solve problem set 3

        Show your working for every step.
        """;

    /** Notion's own pages, including the macOS resource fork a Mac-written zip carries. */
    private static Map<String,String> pages() {
        var pages = new LinkedHashMap<String,String>();
        pages.put("Study Tasks/Read the first chapter " + HASH_CHAPTER + ".md", PAGE_CHAPTER);
        pages.put("Study Tasks/Solve problem set 3 " + HASH_PROBLEMS + ".md", PAGE_PROBLEMS);
        pages.put("Study Tasks/Unrelated page " + HASH_OTHER + ".md", "# Unrelated page\n\nThis page belongs to no row.");
        pages.put("__MACOSX/Study Tasks/._Read the first chapter.md", "resource fork");
        pages.put(".DS_Store", "not a page");
        return pages;
    }

    public static byte[] csv() { return CSV.getBytes(StandardCharsets.UTF_8); }

    public static byte[] checkboxCsv() { return CHECKBOX_CSV.getBytes(StandardCharsets.UTF_8); }

    /** The zip just as Notion downloads it. */
    public static byte[] zip() throws IOException { return zip(CSV, pages()); }

    public static byte[] checkboxZip() throws IOException { return zip(CHECKBOX_CSV, Map.of()); }

    private static byte[] zip(String csv, Map<String,String> pages) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            // Notion wraps the export in a folder of its own; the CSV sits at its top.
            String root = "ExportBlock-3f2b1c00-0000-4000-8000-000000000000/";
            entry(zip, root + DATABASE + ".csv", csv);
            for (var page : pages.entrySet()) entry(zip, root + page.getKey(), page.getValue());
        }
        return out.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
