package dev.yoru.ui;

import dev.yoru.ai.OpenAiTasks;
import dev.yoru.domain.Model.Task;
import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.util.List;
import static dev.yoru.ui.Theme.*;

/** Offline paste form. It produces proposals; the existing review dialog owns saving. */
final class TaskPastePanel extends JPanel {
    private final JTextArea reply=new JTextArea(9,58);
    private final JTextField source=new JTextField(36);
    private final JTextArea status=new JTextArea(2,58);

    TaskPastePanel() {
        setLayout(new BorderLayout());setOpaque(false);
        var form=stack();
        form.add(label("Bring your task proposals into Yoru",TYPE_HEADING,TEXT));gap(form,SPACE_MD);
        var instructions=text("1. Copy this prompt into ChatGPT with your class material.\n"
            +"2. Paste its reply below.\n3. Review titles, dates and evidence before adding tasks.",3);
        form.add(instructions);gap(form,SPACE_SM);
        var prompt=text(OpenAiTasks.pastePrompt(),4);prompt.setFocusable(true);
        prompt.setName("paste.prompt");prompt.getAccessibleContext().setAccessibleName("Prompt to copy");
        var promptScroll=new JScrollPane(prompt);promptScroll.setBorder(controlBorder(LINE));promptScroll.setPreferredSize(new Dimension(590,90));form.add(promptScroll);
        var actions=row();
        actions.add(button("Copy prompt",()->{
            try { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(OpenAiTasks.pastePrompt()),null);
                status.setForeground(MUTED);status.setText("Prompt copied. Paste it into ChatGPT with your class material.");
            } catch (IllegalStateException | HeadlessException | SecurityException e) {
                showError("Clipboard unavailable. Select and copy the prompt above.");
            }
        }));form.add(actions);
        form.add(bodyLabel("Source label (optional, for example Biology syllabus)"));
        styleInput(source);source.setName("paste.source");source.getAccessibleContext().setAccessibleName("Source label");
        ((AbstractDocument)source.getDocument()).setDocumentFilter(limit(160));form.add(source);gap(form,SPACE_MD);
        form.add(label("Paste the complete reply",TYPE_BODY,TEXT));
        reply.setLineWrap(true);reply.setWrapStyleWord(true);reply.setFont(bodyFont());styleInput(reply);
        reply.setName("paste.reply");reply.getAccessibleContext().setAccessibleName("Task proposal reply");
        ((AbstractDocument)reply.getDocument()).setDocumentFilter(limit(OpenAiTasks.MAX_PASTE_CHARS));
        var replyScroll=new JScrollPane(reply);replyScroll.setBorder(controlBorder(LINE));replyScroll.setPreferredSize(new Dimension(590,185));form.add(replyScroll);gap(form,SPACE_MD);
        status.setLineWrap(true);status.setWrapStyleWord(true);status.setEditable(false);status.setFocusable(false);
        status.setOpaque(false);status.setFont(proseFont());status.setForeground(MUTED);
        status.setText("Yoru reads this reply locally. No API key or connection is needed.\nNothing is saved until you approve the review.");
        status.setName("paste.status");form.add(status);
        add(form,BorderLayout.CENTER);
    }

    private static JTextArea text(String value,int rows) {
        var area=new JTextArea(value,rows,58);area.setLineWrap(true);area.setWrapStyleWord(true);
        area.setEditable(false);area.setFocusable(false);area.setOpaque(false);area.setForeground(TEXT);area.setFont(proseFont());return area;
    }

    private DocumentFilter limit(int max) {
        return new DocumentFilter() {
            @Override public void insertString(FilterBypass fb,int offset,String value,AttributeSet attrs)throws BadLocationException {
                replace(fb,offset,0,value,attrs);
            }
            @Override public void replace(FilterBypass fb,int offset,int length,String value,AttributeSet attrs)throws BadLocationException {
                if(fb.getDocument().getLength()-length+(value==null?0:value.length())>max) {
                    showError("That text exceeds the "+max+" character limit. Shorten it and paste again.");return;
                }
                super.replace(fb,offset,length,value,attrs);
            }
        };
    }

    List<Task> proposals()throws IOException { return OpenAiTasks.parsePastedTasks(reply.getText(),source.getText()); }
    void showError(String message) { status.setForeground(DANGER);status.setText(message); }
}
