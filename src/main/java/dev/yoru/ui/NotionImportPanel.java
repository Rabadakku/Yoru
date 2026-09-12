package dev.yoru.ui;

import dev.yoru.application.Tracker;
import dev.yoru.importer.NotionImport;
import dev.yoru.importer.NotionImport.Batch;
import dev.yoru.importer.NotionImport.Candidate;
import dev.yoru.importer.NotionImport.Mapping;
import dev.yoru.importer.NotionImport.Sheet;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import static dev.yoru.ui.Theme.*;

/**
 * Reviews a Notion "Markdown & CSV" export before anything is written.
 *
 * The same shape as {@link TaskPastePanel}: this panel only proposes. It reads
 * the export, guesses the column mapping, and shows exactly what would be added
 * — title, deadline, status, tag and the page text that becomes notes. The
 * mapping stays adjustable here, and the task board owns the one write that
 * follows approval. Nothing on this panel touches a vault.
 */
final class NotionImportPanel extends JPanel {
    /** A column of the export the user is choosing for a role. */
    enum Role {
        TITLE("Title"), STATUS("Status or checkbox"), DUE("Due date"), TAGS("Class / tag");
        final String label;
        Role(String label) { this.label = label; }
    }

    private static final String NONE = "(not imported)";
    private static final String[] HEADINGS = {"Add","Title","Due","Status","Tag","Page text"};
    private static final int NOTES_COLUMN = 5;

    private final Tracker tracker;
    private final JTextField source = new JTextField(36);
    private final EnumMap<Role,JComboBox<Object>> columns = new EnumMap<>(Role.class);
    /** Ticks set by hand, by row number, so a remap never re-ticks a row the user left out. */
    private final Map<Integer,Boolean> choices = new HashMap<>();
    private boolean refreshing;
    private final DefaultTableModel model = new DefaultTableModel(0, HEADINGS.length) {
        @Override public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : String.class; }
        @Override public boolean isCellEditable(int row, int column) { return column == 0; }
    };
    private final JTable table = new JTable(model);
    private final JTextArea message = new JTextArea(3,58);
    private Sheet sheet;
    private List<Candidate> shown = List.of();
    private String defaultSource = "";
    private boolean loading;

    NotionImportPanel(Tracker tracker) {
        this.tracker = tracker;
        setLayout(new BorderLayout());
        setOpaque(false);
        var form = stack();
        form.add(label("Bring a Notion database into Yoru",TYPE_HEADING,TEXT)); gap(form,SPACE_MD);
        var instructions = new JTextArea("In Notion, export the database as Markdown & CSV, then choose the zip it "
            + "downloads or the CSV inside it.\nYoru reads it locally. Check the column mapping and the rows below; "
            + "nothing is saved until you approve the import.",3,58);
        instructions.setLineWrap(true); instructions.setWrapStyleWord(true); instructions.setEditable(false);
        instructions.setFocusable(false); instructions.setOpaque(false); instructions.setForeground(TEXT);
        instructions.setFont(proseFont()); form.add(instructions); gap(form,SPACE_MD);
        var actions = row();
        var choose = button("Choose export…",this::choose); choose.setName("notion.choose");
        actions.add(choose); form.add(actions); gap(form,SPACE_SM);
        form.add(bodyLabel("Source label (optional, shown on every imported task)"));
        styleInput(source); source.setName("notion.source");
        source.getAccessibleContext().setAccessibleName("Source label"); form.add(source); gap(form,SPACE_MD);
        form.add(label("COLUMNS",TYPE_CAPTION,MUTED)); gap(form,SPACE_SM);
        var grid = new JPanel(new GridLayout(0,2,SPACE_MD,SPACE_SM)); grid.setOpaque(false);
        for (var role : Role.values()) {
            grid.add(label(role.label,TYPE_BODY,MUTED));
            var combo = plainCombo(new JComboBox<Object>());
            combo.setName("notion."+role.name().toLowerCase(Locale.ROOT));
            combo.getAccessibleContext().setAccessibleName(role.label+" column");
            combo.setEnabled(false);
            combo.addActionListener(e -> refresh());
            columns.put(role,combo);
            grid.add(combo);
        }
        form.add(grid); gap(form,SPACE_MD);
        form.add(label("ROWS TO ADD",TYPE_CAPTION,MUTED)); gap(form,SPACE_SM);
        table.setName("notion.rows"); plainTable(table); table.setFont(bodyFont());
        table.setBackground(PANEL); table.setForeground(TEXT);
        // As on the task board: TEXT on LINE, because the accent on LINE is
        // under 3:1 on the light themes and a selected row must stay legible.
        table.setSelectionBackground(LINE); table.setSelectionForeground(TEXT);
        table.getColumnModel().getColumn(0).setMaxWidth(45);
        model.addTableModelListener(e -> {
            if (refreshing || e.getType() != javax.swing.event.TableModelEvent.UPDATE || e.getColumn() != 0) return;
            for (int i = Math.max(0,e.getFirstRow()); i <= e.getLastRow() && i < shown.size(); i++)
                choices.put(shown.get(i).row(),Boolean.TRUE.equals(model.getValueAt(i,0)));
            message.setForeground(MUTED);
            message.setText(summary());
        });
        var scroll = new JScrollPane(table); scroll.setPreferredSize(new Dimension(880,220)); form.add(scroll); gap(form,SPACE_MD);
        message.setLineWrap(true); message.setWrapStyleWord(true); message.setEditable(false); message.setFocusable(false);
        message.setOpaque(false); message.setFont(proseFont()); message.setForeground(MUTED);
        message.setName("notion.message");
        message.setText("Choose the export Notion downloaded. A task carries one tag; the first of a row's classes is used.");
        form.add(message);
        add(form,BorderLayout.CENTER);
    }

    /** Reads an export straight from memory, which is how the tests drive the mapping. */
    void load(byte[] export, String name) throws IOException {
        var parsed = NotionImport.read(export,name);
        var mapping = NotionImport.autoMap(parsed.headers());
        sheet = parsed;
        choices.clear();
        loading = true;
        try {
            for (var role : Role.values()) fill(role,parsed.headers(),headerFor(mapping,role));
        } finally { loading = false; }
        String suggested = name == null ? "" : name.replaceAll("(?i)\\.(csv|zip)$","").strip();
        if (!suggested.isEmpty() && (source.getText().isBlank() || source.getText().equals(defaultSource))) {
            defaultSource = suggested.length() > 160 ? suggested.substring(0,160) : suggested;
            source.setText(defaultSource);
        }
        refresh();
    }

    private void choose() {
        var file = Dialogs.chooseFile(this,"Choose the Notion export","Notion exports (zip, csv)","zip","csv");
        if (file == null) return;
        try {
            if (Files.size(file) > NotionImport.MAX_EXPORT_BYTES)
                throw new IOException("That export is larger than 16 MB. Export the database in smaller batches.");
            load(Files.readAllBytes(file),fileName(file));
        } catch (IOException e) { showError(e.getMessage()); }
    }

    private static String fileName(Path file) {
        var name = file.getFileName();
        return name == null ? "Notion export" : name.toString();
    }

    private void fill(Role role,List<String> headers,String selected) {
        var combo = columns.get(role);
        combo.removeAllItems();
        combo.addItem(NONE);
        for (String header : headers) combo.addItem(header);
        combo.setSelectedItem(selected == null ? NONE : selected);
        combo.setEnabled(true);
    }

    private static String headerFor(Mapping mapping,Role role) {
        return switch (role) {
            case TITLE -> mapping.title(); case STATUS -> mapping.status();
            case DUE -> mapping.due(); case TAGS -> mapping.tags();
        };
    }

    /** The mapping as the combos currently stand: what the next preview will be built from. */
    Mapping mapping() {
        return new Mapping(header(Role.TITLE),header(Role.STATUS),header(Role.DUE),header(Role.TAGS));
    }

    private String header(Role role) {
        Object value = columns.get(role).getSelectedItem();
        return value instanceof String name && !NONE.equals(name) ? name : null;
    }

    /** Re-reads the rows under the current mapping. Nothing is written, so this is safe on every keystroke. */
    private void refresh() {
        if (sheet == null || loading) return;
        try {
            shown = NotionImport.preview(sheet,mapping());
            refreshing = true;
            try {
                model.setRowCount(0);
                // A row keeps a tick set by hand; otherwise one already in Yoru starts unticked.
                for (var candidate : shown)
                    model.addRow(new Object[]{ choices.getOrDefault(candidate.row(),!tracker.alreadyHas(candidate.title(),candidate.due())),
                        candidate.title(), candidate.due() == null ? "" : candidate.due().toString(), candidate.status().name(),
                        tagLabel(candidate.tags()), firstLine(candidate.notes()) });
            } finally { refreshing = false; }
            message.setForeground(MUTED);
            message.setText(summary());
        } catch (IOException e) { showError(e.getMessage()); }
    }

    /** The rows as the table currently presents them, in export order. */
    List<Candidate> shown() { return shown; }

    /**
     * What the ticked rows would become, ready for one tracker write.
     *
     * Refuses rather than guessing, so pressing the button with nothing loaded or
     * nothing ticked leaves the vault untouched and says why.
     */
    Batch batch() throws IOException {
        if (sheet == null) throw new IOException("Choose the Notion export first. Nothing was saved.");
        // Re-read under the mapping as it now stands: a mapping that no longer
        // works must not import the rows the previous one happened to produce.
        var live = NotionImport.preview(sheet,mapping());
        var ticked = new HashSet<Integer>();
        for (int i = 0; i < shown.size(); i++)
            if (Boolean.TRUE.equals(model.getValueAt(i,0))) ticked.add(shown.get(i).row());
        var chosen = live.stream().filter(candidate -> ticked.contains(candidate.row())).toList();
        if (chosen.isEmpty()) throw new IOException("No rows are ticked, so there is nothing to import.");
        return NotionImport.prepare(chosen,tracker.state(),sourceLabel());
    }

    void showError(String text) {
        message.setForeground(DANGER);
        message.setText(text == null || text.isBlank() ? "That export could not be read. Nothing was saved." : text);
    }

    private String sourceLabel() {
        String label = source.getText().strip();
        if (label.isEmpty()) return "Notion export";
        return label.length() > 160 ? label.substring(0,160) : label;
    }

    private String summary() {
        int ready = 0;
        for (int i = 0; i < shown.size(); i++) if (Boolean.TRUE.equals(model.getValueAt(i,0))) ready++;
        var text = new StringBuilder(ready+" of "+shown.size()+" rows ready");
        int untitled = sheet.rows().size()-shown.size();
        if (untitled > 0) text.append(" · ").append(untitled).append(" without a title skipped");
        int duplicates = 0;
        for (var candidate : shown) if (tracker.alreadyHas(candidate.title(),candidate.due())) duplicates++;
        if (duplicates > 0) text.append(" · ").append(duplicates).append(" already in Yoru, unticked");
        int withNotes = 0;
        for (var candidate : shown) if (!candidate.notes().isBlank()) withNotes++;
        if (withNotes > 0) text.append(" · ").append(withNotes).append(" with page notes");
        var known = new HashSet<String>();
        for (var tag : tracker.state().tags()) known.add(tag.name().toLowerCase(Locale.ROOT));
        var fresh = new LinkedHashSet<String>();
        for (var candidate : shown) {
            if (candidate.tags().isEmpty()) continue;
            String name = candidate.tags().getFirst();
            if (!known.contains(name.toLowerCase(Locale.ROOT))) fresh.add(name.toLowerCase(Locale.ROOT));
        }
        text.append(" · ").append(fresh.size()).append(fresh.size()==1 ? " new tag" : " new tags");
        return text.toString();
    }

    /** Only the first class is imported, because that is all a Yoru task has room for. */
    private static String tagLabel(List<String> tags) {
        if (tags.isEmpty()) return "";
        return tags.size() == 1 ? tags.getFirst() : tags.getFirst()+" (+"+(tags.size()-1)+" more)";
    }

    private static String firstLine(String notes) {
        if (notes.isBlank()) return "";
        String line = notes.strip().split("\n",2)[0].strip();
        return line.length() > 70 ? line.substring(0,69)+"…" : line;
    }
}
