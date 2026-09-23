package dev.yoru.ui;

import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.time.LocalDate;
import java.util.List;
import javax.swing.*;
import javax.swing.text.JTextComponent;

/** Opt-in synthetic input window for manual desktop shortcut verification. Never opens a vault. */
public final class DesktopTextInputCheck {
    private static JFrame frame;
    private static PageEditor editor;

    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("A desktop is required");
        SwingUtilities.invokeAndWait(DesktopTextInputCheck::open);
    }

    private static void open() {
        Theme.install();
        frame = new JFrame("Yoru synthetic keyboard check");
        var content = new JPanel(new GridLayout(0, 2, 8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        add(content, "Task title", new JTextField(24));
        add(content, "Habit name", new JTextField(24));
        add(content, "Task search", new JTextField(24));
        add(content, "Vault password", new JPasswordField(24));
        add(content, "Formatted text", new JFormattedTextField());
        add(content, "Task notes", new JTextArea(2, 24));
        add(content, "Editor pane", new JEditorPane());
        var date = new DateField(LocalDate.of(2026, 9, 14), "Due", true);
        content.add(new JLabel("Date"));
        content.add(date);
        textIn(date).setName("Date");
        var spinner = new JSpinner(new SpinnerNumberModel(1, 0, 100, 1));
        content.add(new JLabel("Spinner editor"));
        content.add(spinner);
        ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setName("Spinner");
        var combo = new JComboBox<>(new String[]{"Synthetic"});
        combo.setEditable(true);
        content.add(new JLabel("Combo editor"));
        content.add(combo);
        combo.getEditor().getEditorComponent().setName("Combo");
        editor = new PageEditor(new PageEditor.Host() {
            public boolean resolves(String target) { return false; }
            public void follow(dev.yoru.pages.Markdown.Span link, boolean elsewhere) { }
            public List<PageEditor.Choice> linkChoices(String typed) { return List.of(); }
            public boolean save(String text) { return true; }
            public void parsed(dev.yoru.pages.Markdown.Doc doc) { }
        });
        content.add(new JLabel("Pages editor"));
        content.add(editor);
        var dialogButton = new JButton("Open dialog input");
        dialogButton.addActionListener(e -> JOptionPane.showConfirmDialog(frame, new JTextField(24),
            "Synthetic dialog input", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE));
        content.add(dialogButton);
        var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        var previous = clipboard.getContents(null);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                editor.stop();
                clipboard.setContents(previous == null ? new StringSelection("") : previous, null);
                frame.dispose();
                System.exit(0);
            }
        });
        frame.setContentPane(content);
        frame.setSize(680, 760);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        frame.toFront();
    }

    private static void add(JPanel content, String name, JTextComponent field) {
        Theme.styleInput(field);
        field.setName(name);
        content.add(new JLabel(name));
        content.add(field);
    }

    private static JTextComponent textIn(Container root) {
        for (var child : root.getComponents()) {
            if (child instanceof JTextComponent text) return text;
            if (child instanceof Container container) {
                var found = textIn(container);
                if (found != null) return found;
            }
        }
        return null;
    }

}
