package dev.yoru;

import dev.yoru.ai.*;
import dev.yoru.application.*;
import dev.yoru.domain.Model;
import dev.yoru.domain.Model.*;
import dev.yoru.game.SpeciesIds;
import dev.yoru.game.SpeciesNames;
import dev.yoru.json.Json;
import dev.yoru.persistence.EncryptedVault;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

public final class ExpansionTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    interface Work{void run()throws Exception;}
    private static void rejects(Work work,String message)throws Exception{try{work.run();}catch(IOException|IllegalArgumentException e){checks++;return;}throw new AssertionError(message);}
    private static final class Memory implements Repository {
        State state=State.empty();boolean fail;
        public State load(){return state;}
        public void save(State next)throws IOException{if(fail)throw new IOException("failure");state=next;}
        public void close(){}
    }
    public static void main(String[] args)throws Exception {
        check(Model.SPECIES_COUNT==386&&SpeciesNames.COUNT==386,"complete roster");
        boolean distinct=true;
        for(int i=1;i<=386;i++) if(SpeciesIds.nationalOf(SpeciesIds.internalOf(i))!=i) distinct=false;
        check(distinct,"every species maps to itself and back");
        check(SpeciesNames.of(252).equals("Treecko")&&SpeciesNames.of(255).equals("Torchic")&&SpeciesNames.of(258).equals("Mudkip"),"Emerald starters");
        Instant now=Instant.parse("2026-09-09T14:00:00Z");
        var repo=new Memory();var t=new Tracker(repo,Clock.fixed(now,ZoneOffset.UTC));t.addActivity("Class",0);UUID activity=t.state().activities().getFirst().id();
        t.log(activity,now.minusSeconds(4000),now.minusSeconds(3400));check(Encounters.available(t.state())==0,"ten minutes not enough");
        t.log(activity,now.minusSeconds(3000),now.minusSeconds(1800));check(Encounters.available(t.state())==1,"short sessions accumulate to thirty minutes");
        UUID encounter=t.state().campaign().nextEncounter();
        check(encounter.equals(t.state().campaign().nextEncounter()),"retry does not reroll");
        t.catchEncounter(encounter,252,5);
        check(t.state().campaign().encountersUsed()==1&&Encounters.available(t.state())==0,"one catch consumes one encounter");
        rejects(()->t.catchEncounter(encounter,253,5),"the same encounter cannot be caught twice");
        rejects(()->t.catchEncounter(t.state().campaign().nextEncounter(),253,5),"cannot overspend");
        check(t.state().pendingRewards().size()==1,"the caught encounter waits for the game");
        var task=new Task(UUID.randomUUID(),activity,"Read chapter 2","Check figures",LocalDate.of(2026,9,12),false,"syllabus.pdf");
        check(t.addTasks(List.of(task))==1,"task added");check(t.addTasks(List.of(new Task(UUID.randomUUID(),activity,"READ CHAPTER 2","",task.due(),false,"copy.pdf")))==0,"deduplication");
        // A batch skips what it already holds; one task typed by hand is meant,
        // so it is added even when it matches. The dialog used to close silently.
        t.addTask(new Task(UUID.randomUUID(),activity,"READ CHAPTER 2","",task.due(),false,"typed by hand"));
        check(t.state().tasks().size()==2,"a task typed by hand is added even when it matches one already stored");
        t.deleteTask(t.state().tasks().get(1).id());
        t.updateTask(new Task(task.id(),activity,task.title(),task.notes(),task.due(),true,task.source()));check(t.state().tasks().getFirst().done(),"task completed");
        t.start(activity);check(t.state().tasks().size()==1&&t.state().rewards().size()==1,"tracking preserves tasks and rewards");
        check(Encounters.available(t.state())==0,"running time not banked");
        var saved=t.state();repo.fail=true;rejects(()->t.addTasks(List.of(new Task(UUID.randomUUID(),null,"New","",null,false,""))),"failed batch write");check(t.state().equals(saved),"batch is atomic");repo.fail=false;
        t.deleteSession(t.state().sessions().getFirst().id());check(t.state().rewards().size()==1,"deleting history does not delete rewards");check(Encounters.available(t.state())==0,"deleting history cannot mint credit");

        Path dir=Files.createTempDirectory("yoru-v2-test-"),vault=dir.resolve("local.vault");String password="local-test-password";
        try(var v=new EncryptedVault(vault,password.toCharArray())){v.save(t.state());}
        try(var v=new EncryptedVault(vault,password.toCharArray())){check(v.load().equals(t.state()),"full v2 state round trip");}
        Path legacy=dir.resolve("legacy.vault");writeLegacy(legacy,password,activity);byte[] old=Files.readAllBytes(legacy);
        try(var v=new EncryptedVault(legacy,password.toCharArray())){
            check(v.load().activities().getFirst().name().equals("Legacy Study"),"v1 loads");check(v.load().tasks().isEmpty(),"v1 empty task defaults");
            var migrated=new Tracker(v,Clock.fixed(now,ZoneOffset.UTC));migrated.addTasks(List.of(new Task(UUID.randomUUID(),activity,"Migrated task","",null,false,"")));
        }
        check(Arrays.equals(old,Files.readAllBytes(Path.of(legacy+".v1.bak"))),"v1 backup preserved byte for byte");
        try(var v=new EncryptedVault(legacy,password.toCharArray())){check(v.load().tasks().size()==1&&v.load().rewards().isEmpty(),"migration survives reopen");}

        // A damaged or foreign migration backup must not lock the vault out of
        // ever being saved again: it is kept aside and this vault's own bytes
        // take its place.
        Path damaged=dir.resolve("damaged.vault");writeLegacy(damaged,password,activity);
        byte[] beforeUpgrade=Files.readAllBytes(damaged);byte[] foreign={9,9,9};
        Files.write(Path.of(damaged+".v1.bak"),foreign);
        try(var v=new EncryptedVault(damaged,password.toCharArray())){
            var migrated=new Tracker(v,Clock.fixed(now,ZoneOffset.UTC));
            migrated.addTasks(List.of(new Task(UUID.randomUUID(),activity,"Migrated later","",null,false,"")));
        }
        check(Arrays.equals(beforeUpgrade,Files.readAllBytes(Path.of(damaged+".v1.bak"))),
            "a mismatching migration backup is replaced by this vault's own bytes");
        try(var files=Files.list(dir)){
            Path setAside=files.filter(p->p.getFileName().toString().contains(".stale-")).findFirst().orElseThrow();
            check(Arrays.equals(foreign,Files.readAllBytes(setAside)),"and what was there is kept aside, not discarded");
        }

        var payload=Map.of("tasks",List.of(Map.of("title","Lab report","notes","Read pages 2–4","due","2026-09-12","evidence","Page 2: report due Sept 12, 2026")));
        String response=Json.write(Map.of("status","completed","output",List.of(Map.of("type","message","content",List.of(Map.of("type","output_text","text",Json.write(payload)))))));
        var proposed=OpenAiTasks.parseResponse(response,"class.pdf");check(proposed.size()==1&&proposed.getFirst().due().equals(LocalDate.of(2026,9,12)),"structured extraction parsed");
        check(proposed.getFirst().notes().contains("Page 2"),"evidence retained");
        rejects(()->OpenAiTasks.parseResponse(response.replace("completed","incomplete"),"class.pdf"),"truncated response rejected");
        rejects(()->OpenAiTasks.parseResponse("{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"refusal\"}]}]}","class.pdf"),"refusal rejected");
        rejects(()->OpenAiTasks.parseResponse(response.replace("2026-09-12","2026-99-99"),"class.pdf"),"invalid due date rejected");
        String body=OpenAiTasks.requestBody(new OpenAiTasks.Attachment("class.pdf","application/pdf",new byte[]{1,2,3}),OpenAiTasks.DEFAULT_MODEL,LocalDate.of(2026,9,9));
        var request=Json.object(Json.read(body));check(Boolean.FALSE.equals(request.get("store")),"response storage disabled");
        check(body.contains("data:application/pdf;base64,AQID"),"PDF inline input");
        check(body.contains("untrusted data")&&!request.containsKey("tools"),"injection defense and no tool access");
        check(OpenAiTasks.requestBody(new OpenAiTasks.Attachment("photo.png","image/png",new byte[]{1}),OpenAiTasks.DEFAULT_MODEL,LocalDate.now()).contains("input_image"),"photo input");
        var roundTrip=Map.of("text","line\n\"quoted\"\t日本語", "items",List.of(true,1,"x"));check(Json.read(Json.write(roundTrip)) instanceof Map,"JSON roundtrip");
        rejects(()->Json.read("{\"a\":1,\"a\":2}"),"duplicate JSON keys rejected");rejects(()->Json.read("[1,]"),"malformed array rejected");
        rejects(()->Json.read("[".repeat(40)+"0"+"]".repeat(40)),"JSON depth bounded");
        Path unsupported=dir.resolve("unsafe.exe");Files.writeString(unsupported,"not an input");rejects(()->OpenAiTasks.Attachment.read(unsupported),"unsupported input rejected");
        try(var files=Files.list(dir)){for(Path p:files.toList())Files.delete(p);}Files.delete(dir);
        System.out.println("PASS: "+checks+" expansion checks (encounters, rewards, tasks, schema migration, API fixtures and JSON)");
    }
    private static void writeLegacy(Path path,String password,UUID id)throws Exception {
        var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){out.writeInt(1);out.writeInt(1);out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits());out.writeUTF("Legacy Study");out.writeInt(0);out.writeInt(0);out.writeInt(0);}
        byte[] salt=new byte[16],nonce=new byte[12];new java.security.SecureRandom().nextBytes(salt);new java.security.SecureRandom().nextBytes(nonce);
        var spec=new PBEKeySpec(password.toCharArray(),salt,600_000,256);byte[] key=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();spec.clearPassword();
        byte[] header=ByteBuffer.allocate(24).putInt(0x594F5255).putInt(1).put(salt).array();var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(header);byte[] encrypted=cipher.doFinal(bytes.toByteArray());Files.write(path,ByteBuffer.allocate(36+encrypted.length).put(header).put(nonce).put(encrypted).array());Arrays.fill(key,(byte)0);
    }
}
