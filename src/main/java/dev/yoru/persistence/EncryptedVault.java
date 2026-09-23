package dev.yoru.persistence;
import dev.yoru.application.Repository;
import dev.yoru.domain.Model.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.util.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
public final class EncryptedVault implements Repository {
    private static final int MAGIC=0x594F5255, VERSION=1, SCHEMA=20, MAX=100_000;
    /**
     * The file's layout: a header of magic, version and salt, which is also the
     * cipher's associated data, then the nonce, then the ciphertext and its tag.
     * A file shorter than a header, a nonce and a tag is not a vault.
     */
    private static final int HEADER=24, NONCE_END=36, MIN_FILE=52;
    /** Larger than any vault {@link #save} writes, which caps its plaintext at 31 MB. */
    private static final long MAX_FILE=32_000_000;
    private final Path path;
    private final FileChannel channel;
    private final FileLock lock;
    private final byte[] salt=new byte[16];
    private byte[] key;
    private State initial;
    private boolean legacy;
    private int loadedSchema;
    private Path rescued;
    /**
     * Set once the key is wiped. A write after that would encrypt the vault
     * under a key of zeros — one nothing can open again — so every writer
     * refuses instead.
     */
    private boolean closed;
    public EncryptedVault(Path path,char[] password) throws IOException {
        this.path=path.toAbsolutePath();
        FileChannel ch=null;
        FileLock lk=null;
        try {
            if(password.length<12)throw new IOException("Use a password of at least 12 characters.");
            ch=FileChannel.open(lockPath(this.path),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            lk=ch.tryLock();
            if(lk==null)throw new IOException("This vault is already open.");
            byte[] file=null;
            if(Files.exists(this.path)) {
                if(Files.size(this.path)>MAX_FILE)throw new IOException("Vault exceeds size limit.");
                file=Files.readAllBytes(this.path);
                System.arraycopy(salt(file),0,salt,0,salt.length);
            }
            else new SecureRandom().nextBytes(salt);
            key=derive(password,salt);
            initial=file==null?State.empty():decode(decrypt(key,file));
            channel=ch;
            lock=lk;
            if(file==null)save(initial);
        }
        catch(Exception e) {
            if(key!=null)Arrays.fill(key,(byte)0);
            if(lk!=null)try {
                lk.release();
            }
            catch(IOException ignored) {
            }
            if(ch!=null)try {
                ch.close();
            }
            catch(IOException ignored) {
            }
            throw new IOException("Could not unlock vault. Check password, file, and whether it is already open.",e);
        }
        finally {
            Arrays.fill(password,'\0');
        }
    }
    /** How an unlock secret becomes a key: the salt is in the vault's own header. */
    private static byte[] derive(char[] secret,byte[] salt)throws GeneralSecurityException {
        var spec=new PBEKeySpec(secret,salt,600_000,256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        }
        finally {
            spec.clearPassword();
        }
    }

    /**
     * Whether an unlock secret is the one a vault was encrypted with (#41).
     *
     * Only the sealed envelope is tested, never the contents: a vault whose
     * schema Yoru could not read is still recognised rather than mistaken for one
     * this secret does not open. That matters to recovery, where the answer
     * decides whether a lost unlock key is given back to the vault it belongs to.
     * A file that cannot be read is a no here; {@link #decrypts} tells the two apart.
     */
    static boolean opens(Path vault,char[] secret) {
        try {
            return decrypts(vault,secret);
        }
        catch(IOException|RuntimeException e) {
            return false;
        }
    }

    /**
     * Whether an unlock secret opens a vault, and an IOException when that could
     * not be found out — the file could not be read, or the cipher could not be
     * set up. A caller about to discard a key on a "no" must never do it because
     * the disk was unreadable for a moment.
     *
     * A file larger than any vault is a no without being read: the constructor
     * refuses to open one, and reading a stray multi-gigabyte file whole would
     * end the app rather than the check.
     */
    static boolean decrypts(Path vault,char[] secret) throws IOException {
        if(Files.size(vault)>MAX_FILE)return false;
        byte[] file=Files.readAllBytes(vault);
        try {
            byte[] salt;
            try {
                salt=salt(file);
            }
            catch(IOException notAVault) {
                return false;
            }
            byte[] derived=null;
            try {
                derived=derive(secret,salt);
                Arrays.fill(decrypt(derived,file),(byte)0);
                return true;
            }
            catch(AEADBadTagException wrongSecret) {
                return false;
            }
            catch(GeneralSecurityException e) {
                throw new IOException("Could not test the unlock key.",e);
            }
            finally {
                if(derived!=null)Arrays.fill(derived,(byte)0);
            }
        }
        finally {
            Arrays.fill(file,(byte)0);
        }
    }

    /** The salt in a vault file's header, once the file is shown to be a vault this build reads. */
    private static byte[] salt(byte[] file) throws IOException {
        if(file.length<MIN_FILE)throw new IOException("Invalid vault.");
        ByteBuffer header=ByteBuffer.wrap(file);
        if(header.getInt()!=MAGIC||header.getInt()!=VERSION)throw new IOException("Unsupported vault format.");
        byte[] salt=new byte[16];
        header.get(salt);
        return salt;
    }

    private static byte[] decrypt(byte[] key,byte[] file)throws GeneralSecurityException {
        var cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),
            new GCMParameterSpec(128,Arrays.copyOfRange(file,HEADER,NONCE_END)));
        cipher.updateAAD(Arrays.copyOf(file,HEADER));
        return cipher.doFinal(file,NONCE_END,file.length-NONCE_END);
    }

    /** The lock an open vault holds: one file beside it, whatever else is done to the vault. */
    static Path lockPath(Path vault) {
        return Path.of(vault.toAbsolutePath()+".lock");
    }

    private void requireOpen() throws IOException {
        if(closed)throw new IOException("This vault is closed.");
    }
    public State load() {
        return initial;
    }

    /**
     * The name this vault is filed under in managed storage (#41). Everything
     * above this interface talks about vaults by name, so no page has to know —
     * or show — where one lives.
     */
    public String name() {
        return VaultStore.nameOf(path);
    }

    /**
     * A buffer that can be cleared. It holds the whole vault as plaintext, game
     * save included, and would otherwise sit on the heap after every save until
     * something happened to reuse the memory — the same reason decode wipes
     * what it read.
     */
    private static final class Plaintext extends ByteArrayOutputStream {
        byte[] buffer() { return buf; }
        void wipe() { Arrays.fill(buf,(byte)0); count=0; }
    }

    /**
     * Schema {@value #SCHEMA} field order. Positional, so every field is written
     * in exactly the order decode reads it; the older layouts are read by
     * decode's schema checks and upgraded on the next save.
     */
    public void save(State state)throws IOException {
        requireOpen();
        Path temp=null;
        var bytes=new Plaintext();
        try {
            try(var out=new DataOutputStream(bytes)) {
                out.writeInt(SCHEMA);
                out.writeInt(state.activities().size());
                for(var a:state.activities()) { uuid(out,a.id()); out.writeUTF(a.name()); out.writeInt(a.targetMinutes()); }
                out.writeInt(state.sessions().size());
                for(var s:state.sessions()) {
                    uuid(out,s.id()); uuid(out,s.activityId()); instant(out,s.start());
                    out.writeBoolean(s.end()!=null);
                    if(s.end()!=null)instant(out,s.end());
                }
                out.writeInt(state.blocks().size());
                for(var b:state.blocks()) { uuid(out,b.id()); uuid(out,b.activityId()); instant(out,b.start()); instant(out,b.end()); }
                out.writeInt(state.recurring().size());
                for(var r:state.recurring()) {
                    uuid(out,r.id()); uuid(out,r.activityId()); out.writeUTF(r.dayOfWeek().name());
                    // Second-of-day, not an Instant: the whole point is that a
                    // 09:00 class stays at 09:00 across a daylight-saving change.
                    out.writeInt(r.startTime().toSecondOfDay()); out.writeInt(r.endTime().toSecondOfDay());
                }
                out.writeInt(state.tasks().size());
                for (var task : state.tasks()) {
                    uuid(out, task.id()); out.writeBoolean(task.activityId() != null);
                    if (task.activityId() != null) uuid(out, task.activityId());
                    out.writeUTF(task.title()); out.writeUTF(task.notes());
                    out.writeBoolean(task.due() != null);
                    if (task.due() != null) out.writeLong(task.due().toEpochDay());
                    out.writeUTF(task.status().name()); out.writeUTF(task.source());
                    // Schema 18: every tag a task carries, where one had room for one (#66).
                    out.writeInt(task.tagIds().size());
                    for (var tag : task.tagIds()) uuid(out, tag);
                    instant(out, task.createdAt()); out.writeInt(task.order());
                    out.writeBoolean(task.plannedFor() != null);
                    if (task.plannedFor() != null) out.writeLong(task.plannedFor().toEpochDay());
                    out.writeInt(task.pageIds().size());
                    for (var page : task.pageIds()) uuid(out, page);
                    // Schema 19: the list the task is filed in, or none for the Inbox (#56).
                    out.writeBoolean(task.listId() != null);
                    if (task.listId() != null) uuid(out, task.listId());
                    // Schema 20: how it repeats, and the occurrences behind it (#57).
                    out.writeBoolean(task.repeat() != null);
                    if (task.repeat() != null) repeat(out, task.repeat());
                    out.writeInt(task.history().size());
                    for (var o : task.history()) { out.writeLong(o.due().toEpochDay()); instant(out, o.at()); out.writeBoolean(o.skipped()); }
                }
                out.writeInt(state.habits().size());
                for(var h:state.habits()) {
                    uuid(out,h.id()); out.writeUTF(h.name()); out.writeUTF(h.kind().name()); out.writeUTF(h.zone());
                    out.writeInt(h.checkIns().size()); for(var day:new TreeSet<>(h.checkIns())) out.writeLong(day.toEpochDay());
                    out.writeInt(h.starts().size()); for(var start:h.starts()) instant(out,start);
                    // Schema 17: the day the habit began, which consistency counts from (#55).
                    out.writeLong(h.since().toEpochDay());
                }
                out.writeInt(state.tags().size());
                for (var tag : state.tags()) { uuid(out,tag.id()); out.writeUTF(tag.name()); out.writeInt(tag.colour()); }
                var settings = state.settings();
                out.writeUTF(settings.theme().name());
                out.writeInt(settings.dailyGoalHours()); out.writeInt(settings.minSessionSeconds());
                out.writeUTF(settings.weekStartsOn().name());
                // Schema 14: the Pages tree, after everything older schemas held.
                out.writeInt(state.notes().folders().size());
                for (var f : state.notes().folders()) {
                    uuid(out, f.id()); out.writeBoolean(f.parentId() != null);
                    if (f.parentId() != null) uuid(out, f.parentId());
                    out.writeUTF(f.name()); instant(out, f.createdAt());
                    out.writeBoolean(f.deletedAt() != null);
                    if (f.deletedAt() != null) instant(out, f.deletedAt());
                }
                out.writeInt(state.notes().pages().size());
                for (var page : state.notes().pages()) {
                    uuid(out, page.id()); out.writeBoolean(page.folderId() != null);
                    if (page.folderId() != null) uuid(out, page.folderId());
                    out.writeUTF(page.title()); instant(out, page.createdAt()); instant(out, page.updatedAt());
                    out.writeBoolean(page.deletedAt() != null);
                    if (page.deletedAt() != null) instant(out, page.deletedAt());
                    // Length and bytes rather than writeUTF, which stops at 64 KB:
                    // a long page is an ordinary page.
                    byte[] body = page.body().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    out.writeInt(body.length); out.write(body);
                }
                // Schema 16: the Anki integration. Counts and times, and the key
                // the owner entered in Settings, which is a secret and so lives
                // in the encrypted vault rather than in this computer's
                // preferences (#85).
                var anki = state.anki();
                out.writeBoolean(anki.enabled());
                out.writeUTF(anki.key());
                out.writeBoolean(anki.addsTime());
                out.writeInt(anki.refreshMinutes());
                out.writeBoolean(anki.last() != null);
                if (anki.last() != null) {
                    out.writeUTF(anki.last().profile());
                    out.writeLong(anki.last().today());
                    out.writeInt(anki.last().days().size());
                    for (var day : anki.last().days().entrySet()) {
                        out.writeLong(day.getKey().toEpochDay());
                        out.writeLong(day.getValue());
                    }
                    instant(out, anki.last().fetchedAt());
                }
                // Schema 19: the task lists, after everything older schemas held (#56).
                out.writeInt(state.lists().size());
                for (var list : state.lists()) {
                    uuid(out, list.id()); out.writeUTF(list.name()); out.writeInt(list.colour()); out.writeInt(list.order());
                }
            }
            if(bytes.size()>31_000_000)throw new IOException("Vault is too large.");
            byte[] header=ByteBuffer.allocate(24).putInt(MAGIC).putInt(VERSION).put(salt).array(),nonce=new byte[12];
            new SecureRandom().nextBytes(nonce);
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            cipher.updateAAD(header);
            // Straight from the buffer: a toByteArray() copy would be one more
            // plaintext to wipe.
            byte[] encrypted=cipher.doFinal(bytes.buffer(),0,bytes.size());
            temp=Files.createTempFile(path.getParent(),".yoru-",".tmp");
            try {
                Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString("rw-------"));
            }
            catch(UnsupportedOperationException ignored) {
            }
            try(var out=FileChannel.open(temp,StandardOpenOption.WRITE)) {
                var data=ByteBuffer.allocate(36+encrypted.length).put(header).put(nonce).put(encrypted);
                data.flip();
                while(data.hasRemaining())out.write(data);
                out.force(true);
            }
            if (legacy) {
                // Preserve the old encrypted bytes before the first schema upgrade.
                // The copy is written to a temporary file in the same folder,
                // flushed, and only then moved into place: a copy interrupted by
                // a power cut or a full disk would otherwise leave a truncated
                // backup that every later save refuses to work around, locking
                // the vault out of ever being saved again.
                Path backup = Path.of(path + ".v" + loadedSchema + ".bak");
                if (Files.exists(backup) && Files.mismatch(path, backup) != -1) {
                    // A backup that is not this vault's is not a reason to refuse
                    // to save either. It is kept under a name that says what it
                    // is, and this vault's own bytes go beside it, so the older
                    // file can still be recovered by hand.
                    Path stale = Path.of(path + ".v" + loadedSchema + ".stale-"
                        + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".bak");
                    try {
                        Files.move(backup, stale, StandardCopyOption.ATOMIC_MOVE);
                    }
                    catch (AtomicMoveNotSupportedException e) {
                        Files.move(backup, stale);
                    }
                }
                if (!Files.exists(backup)) copyAside(backup);
            }
            Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            // The temporary file is the vault now. Nothing below may fail the
            // save: a caller told it failed would go on as if the old bytes were
            // still on the disk, and a re-encryption would put back the old key.
            temp=null;
            // The rename publishes the new vault, and a rename lives in the
            // directory's own entries: without flushing it, a power cut can
            // leave the old name pointing at the old bytes — or at nothing.
            // VaultStore forces the folder for its deletions for the same reason.
            VaultStore.forceDir(path.getParent());
            legacy=false;
            initial=state;
        }
        catch(GeneralSecurityException e) {
            throw new IOException("Encryption failed.",e);
        }
        catch(FileSystemException e) {
            // The file system's own message is a path on this machine.
            throw new IOException("The vault could not be saved. "+VaultStore.reason(e),e);
        }
        finally {
            bytes.wipe();
            if(temp!=null)Files.deleteIfExists(temp);
        }
    }

    /**
     * Copies this vault to a new name through a flushed temporary file in the
     * same folder, so the copy is complete before it has a backup's name: a copy
     * cut short by a power cut or a full disk would otherwise sit there looking
     * like a backup, and one written only to the page cache is no backup when
     * the machine stops.
     */
    private void copyAside(Path backup) throws IOException {
        Path copy = Files.createTempFile(path.getParent(), ".yoru-backup-", ".tmp");
        try {
            Files.copy(path, copy, StandardCopyOption.REPLACE_EXISTING);
            try (var out = FileChannel.open(copy, StandardOpenOption.WRITE)) {
                out.force(true);
            }
            try {
                Files.move(copy, backup, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException e) {
                Files.move(copy, backup);
            }
        }
        finally {
            Files.deleteIfExists(copy);
        }
        try { Files.setPosixFilePermissions(backup, PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException ignored) { }
        VaultStore.forceDir(path.getParent());
    }

    /**
     * Reads any schema from 1 to 20.
     *
     * Every field older vaults lack arrives as a sensible empty, and everything
     * they hold that Yoru no longer keeps — the collection schemas 2 to 10 kept
     * after the tasks, the practice-gym records after the tags, and the game's
     * campaign, rewards and save — is read past so the records after it land at
     * the right offset.
     */
    /** Emerald writes 128 KiB; anything past a megabyte was never a save of its. */
    private static final int MAX_GAME_SAVE = 1_048_576;

    /**
     * The collection a vault of schema 2 to 10 kept: caught records, a buddy,
     * the encounter counters, a party and boxes. All of it belongs to the game
     * Yoru no longer has, so it is read past and dropped (#58).
     */
    private static void skipLegacyCollection(DataInputStream in, int schema) throws IOException {
        for (int n = count(in); n > 0; n--) {
            in.readLong(); in.readLong(); in.readInt(); in.readLong(); in.readInt();
            if (schema >= 4) { in.readBoolean(); in.readLong(); }
            if (schema >= 5 && in.readBoolean()) in.readUTF();
            if (schema >= 9) in.readInt();
        }
        if (in.readBoolean()) { in.readLong(); in.readLong(); }
        in.readLong(); in.readLong();
        if (schema >= 5) for (int n = count(in); n > 0; n--) { in.readLong(); in.readLong(); }
        if (schema >= 9) for (int n = count(in); n > 0; n--) { in.readUTF(); in.readInt(); }
    }

    /**
     * Writes a game save an older vault was holding to a file beside the vault,
     * so removing the game costs nobody their save (#58).
     *
     * Written once: a file already there is left alone, and a failure to write
     * is not a reason to refuse to open the vault, so it is reported through
     * {@link #rescuedSave()} rather than thrown.
     */
    private void rescueGameSave(byte[] save) {
        try {
            String name = path.getFileName().toString().replaceFirst("\\.[^.]*$", "");
            var beside = path.resolveSibling(name + "-game-save.sav");
            if (!Files.exists(beside)) Files.write(beside, save);
            rescued = beside;
        } catch (IOException ignored) {
            // The vault still opens; the save simply could not be put beside it.
        } finally {
            Arrays.fill(save, (byte) 0);
        }
    }

    /** Where an older vault's game save was written, or null. */
    public Path rescuedSave() { return rescued; }

    private State decode(byte[] bytes)throws IOException {
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))) {
            int schema = in.readInt();
            if (schema < 1 || schema > SCHEMA) throw new IOException("Unsupported schema.");
            loadedSchema = schema;
            legacy = schema < SCHEMA;
            var activities=new ArrayList<Activity>();
            for(int n=count(in);n>0;n--)activities.add(new Activity(uuid(in),in.readUTF(),in.readInt()));
            var sessions=new ArrayList<Session>();
            for(int n=count(in);n>0;n--) {
                var id=uuid(in);var activity=uuid(in);var start=instant(in);var end=in.readBoolean()?instant(in):null;
                // Schemas 4 to 10 credited each session to a study buddy.
                if(schema>=4 && schema<=10 && in.readBoolean()) uuid(in);
                sessions.add(new Session(id,activity,start,end));
            }
            var blocks=new ArrayList<ScheduleBlock>();
            for(int n=count(in);n>0;n--)blocks.add(new ScheduleBlock(uuid(in),uuid(in),instant(in),instant(in)));
            // Schema 5 and earlier had no weekly template; those vaults load with an
            // empty one and keep every dated block they already had.
            var recurring=new ArrayList<RecurringBlock>();
            if(schema>=6) for(int n=count(in);n>0;n--)
                recurring.add(new RecurringBlock(uuid(in),uuid(in),java.time.DayOfWeek.valueOf(in.readUTF()),
                    java.time.LocalTime.ofSecondOfDay(in.readInt()),java.time.LocalTime.ofSecondOfDay(in.readInt())));
            var tasks = new ArrayList<Task>();
            if (schema >= 2) {
                for (int n = count(in); n > 0; n--) {
                    var id=uuid(in); var activity=in.readBoolean()?uuid(in):null;
                    var title=in.readUTF(); var notes=in.readUTF();
                    var due=in.readBoolean()?LocalDate.ofEpochDay(in.readLong()):null;
                    // Before schema 5 this was a done flag rather than a status.
                    var status=schema>=5?TaskStatus.valueOf(in.readUTF())
                        :in.readBoolean()?TaskStatus.DONE:TaskStatus.TODO;
                    var source=in.readUTF();
                    // Schema 18 gave a task a list of tags; from 5 to 17 it had room for one.
                    var tags=new ArrayList<UUID>();
                    if(schema>=18) for(int t=count(in);t>0;t--) tags.add(uuid(in));
                    else if(schema>=5&&in.readBoolean()) tags.add(uuid(in));
                    var created=schema>=5?instant(in):Instant.EPOCH.plusSeconds(86400);
                    int order=schema>=5?in.readInt():0;
                    // Schema 8 split the deadline from the day you plan to work on
                    // it. Older vaults planned nothing, which is exactly null.
                    var planned=schema>=8&&in.readBoolean()?LocalDate.ofEpochDay(in.readLong()):null;
                    // Schema 14 linked tasks to pages; before it, none were.
                    var pages=new ArrayList<UUID>();
                    if(schema>=14) for(int p=count(in);p>0;p--) pages.add(uuid(in));
                    // Schema 19 filed tasks in lists; before it every task was in the Inbox.
                    var list=schema>=19&&in.readBoolean()?uuid(in):null;
                    // Schema 20 let a task repeat; before it none did.
                    var rule=schema>=20&&in.readBoolean()?repeat(in):null;
                    var history=new ArrayList<Occurrence>();
                    if(schema>=20) for(int h=count(in);h>0;h--)
                        history.add(new Occurrence(LocalDate.ofEpochDay(in.readLong()),instant(in),in.readBoolean()));
                    tasks.add(new Task(id,activity,tags,title,notes,due,status,source,created,order,planned,pages,list,rule,history));
                }
                if (schema <= 10) skipLegacyCollection(in, schema);
            }
            var habits=new ArrayList<Habit>();
            if(schema>=3) for(int n=count(in);n>0;n--) {
                var id=uuid(in); var name=in.readUTF(); var kind=HabitKind.valueOf(in.readUTF()); var zone=in.readUTF();
                var dates=new HashSet<LocalDate>();
                for(int d=count(in);d>0;d--) dates.add(LocalDate.ofEpochDay(in.readLong()));
                var starts=new ArrayList<Instant>(); for(int d=count(in);d>0;d--) starts.add(instant(in));
                // Before schema 17 a habit had no beginning; the record works one
                // out from the earliest thing it records.
                habits.add(schema>=17 ? new Habit(id,name,kind,zone,dates,starts,LocalDate.ofEpochDay(in.readLong()))
                    : new Habit(id,name,kind,zone,dates,starts));
            }
            var tags=new ArrayList<Tag>();
            var settings=Settings.defaults();
            if(schema>=5) {
                for(int n=count(in);n>0;n--) tags.add(new Tag(uuid(in),in.readUTF(),in.readInt()));
                if(schema<=10) for(int n=count(in);n>0;n--) { uuid(in); in.readInt(); instant(in); }   // practice-gym records
                var theme=ThemeId.known(in.readUTF());
                // Schemas up to 14 chose a trainer for the game's scene.
                if(schema<=14) in.readUTF();
                int goal=in.readInt(),floor=in.readInt();
                // Schema 6 and earlier had no week-start preference; those vaults
                // keep the Monday the app has always assumed.
                var weekStart=schema>=7?java.time.DayOfWeek.valueOf(in.readUTF()):java.time.DayOfWeek.MONDAY;
                // Schema 12 alone carried a companion-portrait choice. The
                // feature is gone, so its bytes are read and dropped; without that
                // every record after them would be read at the wrong offset.
                if(schema==12&&in.readBoolean()) in.readUTF();
                settings=new Settings(theme,goal,floor,weekStart);
            }
            // The game was removed in schema 15 (#58): its campaign, its rewards
            // and its save are read past so that everything after them lands at
            // the right offset, and the save itself is written out beside the
            // vault rather than dropped, since it is the owner's game.
            if(schema>=11&&schema<=14) { in.readLong(); in.readLong(); in.readLong(); }
            if(schema>=10&&schema<=14) for(int n=count(in);n>0;n--) {
                uuid(in); in.readInt(); in.readInt(); instant(in);
                if(in.readBoolean()) instant(in);
            }
            if(schema>=11&&schema<=14&&in.readBoolean()) {
                instant(in);
                int length=in.readInt();
                if(length<=0||length>MAX_GAME_SAVE) throw new IOException("Invalid game save.");
                rescueGameSave(in.readNBytes(length));
            }
            var folders=new ArrayList<Folder>();
            var pages=new ArrayList<Page>();
            if(schema>=14) {
                for(int n=count(in);n>0;n--) {
                    var id=uuid(in); var parent=in.readBoolean()?uuid(in):null; var name=in.readUTF();
                    var created=instant(in); var deleted=in.readBoolean()?instant(in):null;
                    folders.add(new Folder(id,parent,name,created,deleted));
                }
                for(int n=count(in);n>0;n--) {
                    var id=uuid(in); var folder=in.readBoolean()?uuid(in):null; var title=in.readUTF();
                    var created=instant(in); var updated=instant(in); var deleted=in.readBoolean()?instant(in):null;
                    int length=in.readInt();
                    // Four bytes a character at most: anything longer is not a page.
                    if(length<0||length>Page.MAX_BODY*4)throw new IOException("Invalid page.");
                    var body=new String(in.readNBytes(length),java.nio.charset.StandardCharsets.UTF_8);
                    pages.add(new Page(id,folder,title,body,created,updated,deleted));
                }
            }
            // Schema 16: the Anki integration, its key and the counts it last saw.
            var anki=Anki.off();
            if(schema>=16) {
                boolean on=in.readBoolean();
                String key=in.readUTF();
                boolean addsTime=in.readBoolean();
                int refresh=in.readInt();
                AnkiSnapshot last=null;
                if(in.readBoolean()) {
                    String profile=in.readUTF();
                    long todayCount=in.readLong();
                    var days=new java.util.TreeMap<LocalDate,Long>();
                    for(int n=count(in);n>0;n--) days.put(LocalDate.ofEpochDay(in.readLong()),in.readLong());
                    last=new AnkiSnapshot(profile,todayCount,days,instant(in));
                }
                anki=new Anki(on,key,addsTime,refresh,last);
            }
            // Schema 19: the task lists (#56).
            var lists=new ArrayList<TaskList>();
            if(schema>=19) for(int n=count(in);n>0;n--) lists.add(new TaskList(uuid(in),in.readUTF(),in.readInt(),in.readInt()));
            if(in.available()!=0)throw new IOException("Unexpected vault content.");
            return new State(activities,sessions,blocks,recurring,tasks,habits,tags,settings,new Notes(folders,pages),anki,lists);
        }
        catch(RuntimeException e) {
            throw new IOException("Invalid vault data.",e);
        }
        finally {
            Arrays.fill(bytes,(byte)0);
        }
    }
    /** A repeat rule, field by field; weekdays as one bit each. */
    private static void repeat(DataOutputStream out,Repeat r)throws IOException {
        out.writeUTF(r.unit().name()); out.writeInt(r.every());
        int days=0; for(var day:r.days()) days|=1<<day.ordinal();
        out.writeInt(days); out.writeInt(r.monthDay()); out.writeInt(r.weekOfMonth());
        out.writeLong(r.start().toEpochDay()); out.writeBoolean(r.afterDone());
        out.writeBoolean(r.until()!=null); if(r.until()!=null) out.writeLong(r.until().toEpochDay());
        out.writeInt(r.times());
    }
    private static Repeat repeat(DataInputStream in)throws IOException {
        var unit=RepeatUnit.valueOf(in.readUTF()); int every=in.readInt(); int mask=in.readInt();
        var days=EnumSet.noneOf(java.time.DayOfWeek.class);
        for(var day:java.time.DayOfWeek.values()) if((mask&(1<<day.ordinal()))!=0) days.add(day);
        int monthDay=in.readInt(), week=in.readInt();
        var start=LocalDate.ofEpochDay(in.readLong()); boolean afterDone=in.readBoolean();
        var until=in.readBoolean()?LocalDate.ofEpochDay(in.readLong()):null;
        return new Repeat(unit,every,days,monthDay,week,start,afterDone,until,in.readInt());
    }
    private static int count(DataInputStream in)throws IOException {
        int n=in.readInt();
        if(n<0||n>MAX)throw new IOException("Invalid record count.");
        return n;
    }
    private static void uuid(DataOutputStream out,UUID id)throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }
    private static UUID uuid(DataInputStream in)throws IOException {
        return new UUID(in.readLong(),in.readLong());
    }
    private static void instant(DataOutputStream out,Instant i)throws IOException {
        out.writeLong(i.getEpochSecond());
        out.writeInt(i.getNano());
    }
    private static Instant instant(DataInputStream in)throws IOException {
        return Instant.ofEpochSecond(in.readLong(),in.readInt());
    }
    /** The newest backups, kept however old: enough to undo a run of recent edits. */
    static final int RECENT_BACKUPS = 10;
    /** Days back that keep their first backup, so a whole day's edits can be undone. */
    static final int DAILY_BACKUPS = 30;

    /**
     * Copies the vault aside before a change that deletes or replaces something.
     *
     * Every copy used to be kept, so a backup before each small deletion would
     * have grown the folder without bound (#7). Each new backup now prunes the
     * older ones by {@link #expired}. A prune that fails is left for the next
     * backup: the copy that was asked for has already been written — and
     * flushed, since the older copies it may replace are on the disk.
     */
    public void backup() throws IOException {
        requireOpen();
        Path backup=Path.of(path+".reset-"+System.currentTimeMillis()+"-"+UUID.randomUUID()+".bak");
        try {
            copyAside(backup);
        }
        catch(FileSystemException e) {
            throw new IOException("The vault could not be backed up. "+VaultStore.reason(e),e);
        }
        for (Path old : expired(resetBackups(path), Instant.now(), ZoneId.systemDefault())) {
            try { Files.deleteIfExists(old); }
            catch (IOException ignored) { }
        }
    }

    /**
     * This vault's reset backups, by the moment each was taken, read from its
     * name rather than the file's clock. Migration backups and anything whose
     * name does not parse are not listed, so they are never pruned.
     */
    static Map<Path, Instant> resetBackups(Path vault) throws IOException {
        String prefix = vault.getFileName() + ".reset-";
        var found = new HashMap<Path, Instant>();
        try (var files = Files.list(vault.toAbsolutePath().getParent())) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String name = file.getFileName().toString();
                if (!name.startsWith(prefix) || !name.endsWith(".bak") || !Files.isRegularFile(file)) continue;
                String rest = name.substring(prefix.length());
                int dash = rest.indexOf('-');
                if (dash <= 0) continue;
                try { found.put(file, Instant.ofEpochMilli(Long.parseLong(rest.substring(0, dash)))); }
                catch (NumberFormatException ignored) { }
            }
        }
        return found;
    }

    /**
     * The backups a retention pass removes: all but the {@link #RECENT_BACKUPS}
     * newest, the first backup of each of the last {@link #DAILY_BACKUPS} days,
     * and any dated after {@code now}, since a clock that moved backwards is no
     * reason to lose one.
     *
     * The first of a day rather than the last, because it holds the vault as
     * the day began: an import followed by a run of deletions can still be
     * undone to before the import once the deletions have pushed it out of the
     * newest ten.
     */
    static List<Path> expired(Map<Path, Instant> taken, Instant now, ZoneId zone) {
        var newestFirst = new ArrayList<>(taken.keySet());
        newestFirst.sort(Comparator.comparing((Path p) -> taken.get(p)).reversed().thenComparing(Path::toString));
        var keep = new HashSet<Path>(newestFirst.subList(0, Math.min(RECENT_BACKUPS, newestFirst.size())));
        LocalDate firstDay = now.atZone(zone).toLocalDate().minusDays(DAILY_BACKUPS - 1);
        var firstOfDay = new HashMap<LocalDate, Path>();
        for (Path p : newestFirst) {
            Instant when = taken.get(p);
            if (when.isAfter(now)) { keep.add(p); continue; }
            LocalDate day = when.atZone(zone).toLocalDate();
            // Newest first, so the last one put for a day is its earliest.
            if (!day.isBefore(firstDay)) firstOfDay.put(day, p);
        }
        keep.addAll(firstOfDay.values());
        return newestFirst.stream().filter(p -> !keep.contains(p)).toList();
    }
    /**
     * Re-encrypts this vault under a new unlock secret, keeping everything in it.
     *
     * The salt is replaced along with the key rather than reused: a fresh one
     * costs a single write and leaves nothing of the old secret's derivation
     * behind. The state is handed in rather than taken from {@link #load()},
     * so a change made while the app is running writes what is on screen and
     * not what was last read off the disk.
     *
     * A failure puts the old key and salt back and leaves the file alone —
     * {@link #save} writes through a temporary file and an atomic move, so the
     * vault on disk is either the old one or the new one, never half of each.
     */
    public void changeSecret(char[] secret,State state)throws IOException {
        try {
            requireOpen();
            if(secret.length<12)throw new IOException("Use an unlock secret of at least 12 characters.");
            byte[] previousKey=key,previousSalt=salt.clone(),fresh=new byte[salt.length];
            new SecureRandom().nextBytes(fresh);
            System.arraycopy(fresh,0,salt,0,salt.length);
            try {
                key=derive(secret,salt);
            }
            catch(GeneralSecurityException e) {
                System.arraycopy(previousSalt,0,salt,0,salt.length);
                key=previousKey;
                throw new IOException("Could not change how this vault is unlocked.",e);
            }
            try {
                save(state);
            }
            catch(IOException|RuntimeException e) {
                Arrays.fill(key,(byte)0);
                System.arraycopy(previousSalt,0,salt,0,salt.length);
                key=previousKey;
                throw e;
            }
            Arrays.fill(previousKey,(byte)0);
            Arrays.fill(previousSalt,(byte)0);
        }
        finally {
            Arrays.fill(secret,'\0');
        }
    }

    /**
     * Wipes the key and gives up the lock. A second call does nothing, and the
     * channel is closed even when releasing the lock fails, since closing it
     * releases the lock anyway.
     */
    public void close()throws IOException {
        if(closed)return;
        closed=true;
        try {
            Arrays.fill(key,(byte)0);
            lock.release();
        }
        finally {
            channel.close();
        }
    }
}
