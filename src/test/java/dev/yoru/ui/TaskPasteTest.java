package dev.yoru.ui;

import dev.yoru.ai.OpenAiTasks;
import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.List;

public final class TaskPasteTest {
    static final String TASK="{\"title\":\"Read chapter 2\",\"notes\":\"Discuss figures\",\"due\":\"2026-09-12\",\"evidence\":\"Chapter 2, page 4\"}";
    static final String REPLY="{\"tasks\":["+TASK+"]}";
    private static int checks;
    private static void check(boolean value,String message) { checks++;if(!value)throw new AssertionError(message); }
    private static void rejects(String text,String expected)throws Exception {
        try { OpenAiTasks.parsePastedTasks(text,"Syllabus");throw new AssertionError("Accepted invalid reply: "+expected); }
        catch(IOException e) { check(e.getMessage().contains(expected),"Helpful refusal: "+e.getMessage()); }
    }
    private static Component find(Container root,String name) {
        for(var child:root.getComponents()) {
            if(name.equals(child.getName()))return child;
            if(child instanceof Container nested) {var found=find(nested,name);if(found!=null)return found;}
        }
        return null;
    }
    private static final class Memory implements Repository {
        State state=State.empty();boolean fail;int writes;
        public State load(){return state;}
        public void save(State state)throws IOException {if(fail)throw new IOException("disk full");this.state=state;writes++;}
        public void close(){}
    }
    public static void main(String[] args)throws Exception {
        var plain=OpenAiTasks.parsePastedTasks(REPLY,"  Biology syllabus  ").getFirst();
        check(plain.title().equals("Read chapter 2")&&plain.due().equals(LocalDate.of(2026,9,12)),"Title and date parsed");
        check(plain.source().equals("Biology syllabus")&&plain.notes().contains("Chapter 2, page 4"),"Source and evidence retained");
        check(plain.status()==TaskStatus.TODO&&plain.activityId()==null,"Proposals start unassigned and open");
        for(String fence:new String[]{"```json\n","```JSON\r\n","```\n"}) {
            check(OpenAiTasks.parsePastedTasks(" \n"+fence+REPLY+"\n```\n","").size()==1,"Fenced reply accepted");
        }
        check(OpenAiTasks.parsePastedTasks(REPLY,null).getFirst().source().equals("Pasted task proposals"),"Default source label");
        check(OpenAiTasks.parsePastedTasks(REPLY.replace("\"2026-09-12\"","null"),"").getFirst().due()==null,"Unknown deadline stays unknown");
        check(OpenAiTasks.parsePastedTasks("{\"tasks\":[]}","").isEmpty(),"Empty proposals supported");
        check(OpenAiTasks.parsePastedTasks(REPLY.replace("Read chapter 2","日本語 <html>study</html>"),"").getFirst().title().contains("<html>"),"Content stays text");
        check(OpenAiTasks.parsePastedTasks("{\"tasks\":["+String.join(",",Collections.nCopies(50,TASK))+"]}","").size()==50,"Fifty task boundary accepted");
        rejects("{\"tasks\":["+String.join(",",Collections.nCopies(51,TASK))+"]}","50");
        rejects(" ","Paste");rejects(null,"Paste");
        rejects("x".repeat(OpenAiTasks.MAX_PASTE_CHARS+1),"too large");
        rejects("Here are your tasks:\n"+REPLY,"complete reply");
        rejects(REPLY+REPLY,"complete reply");
        rejects("```json\n"+REPLY,"closing brace");
        rejects("```python\n"+REPLY+"\n```","closing brace");
        rejects("{\"tasks\":[],\"tasks\":[]}","complete reply");
        rejects("{\"task\":[]}","tasks list");
        rejects(REPLY.replace("\"due\":","\"deadline\":"),"Task 1");
        rejects(REPLY.replace("2026-09-12","2026-02-29"),"real calendar date");
        rejects(REPLY.replace("2026-09-12","2026-9-12"),"YYYY-MM-DD");
        rejects(REPLY.replace("2026-09-12",""),"YYYY-MM-DD");
        rejects(REPLY.replace("Read chapter 2"," "),"Task 1");
        rejects(REPLY.replace("Read chapter 2","x".repeat(161)),"Task 1");
        rejects(REPLY.replace("Discuss figures","x".repeat(2001)),"2000");
        rejects(REPLY.replace("Chapter 2, page 4","x".repeat(501)),"500");
        rejects("{\"tasks\":["+TASK+","+TASK.replace("2026-09-12","bad")+"]}","Task 2");
        rejects(REPLY.replace("\"Discuss figures\"","17"),"Task 1");
        String escaped=REPLY.replace("\\","\\\\").replace("\"","\\\"");
        String api="{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\""+escaped+"\"}]}]}";
        var apiTask=OpenAiTasks.parseResponse(api,"Biology syllabus").getFirst();
        check(apiTask.title().equals(plain.title())&&apiTask.notes().equals(plain.notes())&&apiTask.due().equals(plain.due()),"API and paste share task interpretation");
        SwingUtilities.invokeAndWait(()->{
            try {
                for(var theme:ThemeId.values()) {
                    Theme.apply(theme);
                    var form=new TaskPastePanel();
                    var reply=(JTextArea)find(form,"paste.reply");var source=(JTextField)find(form,"paste.source");
                    var status=(JTextArea)find(form,"paste.status");
                    check(reply!=null&&source!=null,"Paste and source fields available");
                    reply.setText("invalid");
                    try {form.proposals();throw new AssertionError("Invalid form accepted");}
                    catch(IOException e){form.showError(e.getMessage());}
                    check(reply.getText().equals("invalid")&&status.getForeground().equals(Theme.DANGER),"Error retains reply for correction");
                    reply.setText(REPLY);source.setText("Class handout");
                    var repo=new Memory();var tracker=new Tracker(repo,Clock.systemUTC());var before=tracker.state();
                    var proposals=form.proposals();
                    check(repo.writes==0&&tracker.state().equals(before),"Parsing does not save tasks");
                    check(proposals.getFirst().source().equals("Class handout"),"Form applies source label");
                    repo.fail=true;
                    try {tracker.addTasks(proposals);throw new AssertionError("Failed save accepted");}catch(IOException expected){}
                    check(tracker.state().equals(before),"Failed save leaves vault state unchanged");
                    repo.fail=false;check(tracker.addTasks(proposals)==1,"Accepted proposal can be saved");
                    check(tracker.addTasks(form.proposals())==0,"Pasting same reply again respects deduplication");
                    source.setText("x".repeat(161));
                    check(source.getText().length()<=160&&status.getText().contains("160"),"Source input bounded with visible feedback");
                    reply.setText("x".repeat(OpenAiTasks.MAX_PASTE_CHARS+1));
                    check(reply.getText().length()<=OpenAiTasks.MAX_PASTE_CHARS&&status.getText().contains("200000"),"Oversize paste refused without truncation");
                }
            }catch(Exception e){throw new RuntimeException(e);}
        });
        System.out.println("PASS: "+checks+" paste import checks (parser, shared API format, form, validation, atomic saving)");
    }
}
