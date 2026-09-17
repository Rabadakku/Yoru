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
    private static final int MAGIC=0x594F5255, VERSION=1, SCHEMA=13, MAX=100_000;
    private final Path path;
    private final FileChannel channel;
    private final FileLock lock;
    private final byte[] salt=new byte[16];
    private byte[] key;
    private State initial;
    private boolean legacy;
    private int loadedSchema;
    public EncryptedVault(Path path,char[] password) throws IOException {
        this.path=path.toAbsolutePath();
        FileChannel ch=null;
        FileLock lk=null;
        try {
            if(password.length<12)throw new IOException("Use a password of at least 12 characters.");
            ch=FileChannel.open(Path.of(this.path+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            lk=ch.tryLock();
            if(lk==null)throw new IOException("This vault is already open.");
            byte[] file=null;
            if(Files.exists(this.path)) {
                if(Files.size(this.path)>32_000_000)throw new IOException("Vault exceeds size limit.");
                file=Files.readAllBytes(this.path);
                if(file.length<52)throw new IOException("Invalid vault.");
                ByteBuffer header=ByteBuffer.wrap(file);
                if(header.getInt()!=MAGIC||header.getInt()!=VERSION)throw new IOException("Unsupported vault format.");
                header.get(salt);
            }
            else new SecureRandom().nextBytes(salt);
            key=derive(password,salt);
            // A new vault gets its own encounter seed, so two vaults do not meet
            // the same Pokémon in the same order.
            initial=file==null?State.empty().withCampaign(Campaign.start(new SecureRandom().nextLong())):decode(decrypt(file));
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
     */
    static boolean opens(Path vault,char[] secret) {
        byte[] file=null;
        try {
            file=Files.readAllBytes(vault);
            if(file.length<52)return false;
            ByteBuffer header=ByteBuffer.wrap(file);
            if(header.getInt()!=MAGIC||header.getInt()!=VERSION)return false;
            byte[] salt=new byte[16];
            header.get(salt);
            byte[] derived=derive(secret,salt);
            try {
                var cipher=Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(derived,"AES"),
                    new GCMParameterSpec(128,Arrays.copyOfRange(file,24,36)));
                cipher.updateAAD(Arrays.copyOf(file,24));
                cipher.doFinal(file,36,file.length-36);
                return true;
            }
            finally {
                Arrays.fill(derived,(byte)0);
            }
        }
        catch(IOException|GeneralSecurityException|RuntimeException e) {
            return false;
        }
        finally {
            if(file!=null)Arrays.fill(file,(byte)0);
        }
    }

    private byte[] decrypt(byte[] file)throws GeneralSecurityException {
        var cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Arrays.copyOfRange(file,24,36)));
        cipher.updateAAD(Arrays.copyOf(file,24));
        return cipher.doFinal(file,36,file.length-36);
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
     * Schema 11 field order. Positional, so every field is written in exactly
     * the order decode reads it; the older layouts are read by decode's
     * schema checks and upgraded on the next save.
     */
    public void save(State state)throws IOException {
        Path temp=null;
        try {
            var bytes=new ByteArrayOutputStream();
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
                    out.writeBoolean(task.tagId() != null);
                    if (task.tagId() != null) uuid(out, task.tagId());
                    instant(out, task.createdAt()); out.writeInt(task.order());
                    out.writeBoolean(task.plannedFor() != null);
                    if (task.plannedFor() != null) out.writeLong(task.plannedFor().toEpochDay());
                }
                out.writeInt(state.habits().size());
                for(var h:state.habits()) {
                    uuid(out,h.id()); out.writeUTF(h.name()); out.writeUTF(h.kind().name()); out.writeUTF(h.zone());
                    out.writeInt(h.checkIns().size()); for(var day:new TreeSet<>(h.checkIns())) out.writeLong(day.toEpochDay());
                    out.writeInt(h.starts().size()); for(var start:h.starts()) instant(out,start);
                }
                out.writeInt(state.tags().size());
                for (var tag : state.tags()) { uuid(out,tag.id()); out.writeUTF(tag.name()); out.writeInt(tag.colour()); }
                var settings = state.settings();
                out.writeUTF(settings.theme().name()); out.writeUTF(settings.trainer().name());
                out.writeInt(settings.dailyGoalHours()); out.writeInt(settings.minSessionSeconds());
                out.writeUTF(settings.weekStartsOn().name());
                var campaign = state.campaign();
                out.writeLong(campaign.seed()); out.writeLong(campaign.encountersUsed()); out.writeLong(campaign.rewardedSeconds());
                out.writeInt(state.rewards().size());
                for (var r : state.rewards()) {
                    uuid(out, r.id()); out.writeInt(r.nationalDex()); out.writeInt(r.level());
                    instant(out, r.earnedAt());
                    out.writeBoolean(r.deliveredAt() != null);
                    if (r.deliveredAt() != null) instant(out, r.deliveredAt());
                }
                out.writeBoolean(state.game() != null);
                if (state.game() != null) {
                    instant(out, state.game().updatedAt());
                    byte[] save = state.game().bytes();
                    out.writeInt(save.length); out.write(save);
                }
            }
            if(bytes.size()>31_000_000)throw new IOException("Vault is too large.");
            byte[] header=ByteBuffer.allocate(24).putInt(MAGIC).putInt(VERSION).put(salt).array(),nonce=new byte[12];
            new SecureRandom().nextBytes(nonce);
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            cipher.updateAAD(header);
            byte[] encrypted=cipher.doFinal(bytes.toByteArray());
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
                if (!Files.exists(backup)) {
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
            }
            Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
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
        finally {
            if(temp!=null)Files.deleteIfExists(temp);
        }
    }

    /**
     * Reads any schema from 1 to 13.
     *
     * Every field older vaults lack arrives as a sensible empty. Schemas 2 to 10
     * kept a collection of their own after the tasks and gym records after the
     * tags; LegacyCollection turns the first into rewards for the game, and the
     * second — practice battles that no longer exist — is read past and dropped.
     */
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
            LegacyCollection collection = null;
            if (schema >= 2) {
                for (int n = count(in); n > 0; n--) {
                    var id=uuid(in); var activity=in.readBoolean()?uuid(in):null;
                    var title=in.readUTF(); var notes=in.readUTF();
                    var due=in.readBoolean()?java.time.LocalDate.ofEpochDay(in.readLong()):null;
                    // Before schema 5 this was a done flag rather than a status.
                    var status=schema>=5?TaskStatus.valueOf(in.readUTF())
                        :in.readBoolean()?TaskStatus.DONE:TaskStatus.TODO;
                    var source=in.readUTF();
                    var tag=schema>=5&&in.readBoolean()?uuid(in):null;
                    var created=schema>=5?instant(in):Instant.EPOCH.plusSeconds(86400);
                    int order=schema>=5?in.readInt():0;
                    // Schema 8 split the deadline from the day you plan to work on
                    // it. Older vaults planned nothing, which is exactly null.
                    var planned=schema>=8&&in.readBoolean()?java.time.LocalDate.ofEpochDay(in.readLong()):null;
                    tasks.add(new Task(id,activity,tag,title,notes,due,status,source,created,order,planned));
                }
                if (schema <= 10) collection = LegacyCollection.read(in, schema, () -> {
                    try { return count(in); } catch (IOException e) { throw new UncheckedIOException(e); }
                });
            }
            var habits=new ArrayList<Habit>();
            if(schema>=3) for(int n=count(in);n>0;n--) {
                var id=uuid(in); var name=in.readUTF(); var kind=HabitKind.valueOf(in.readUTF()); var zone=in.readUTF();
                var dates=new HashSet<java.time.LocalDate>();
                for(int d=count(in);d>0;d--) dates.add(java.time.LocalDate.ofEpochDay(in.readLong()));
                var starts=new ArrayList<Instant>(); for(int d=count(in);d>0;d--) starts.add(instant(in));
                habits.add(new Habit(id,name,kind,zone,dates,starts));
            }
            var tags=new ArrayList<Tag>();
            var settings=Settings.defaults();
            if(schema>=5) {
                for(int n=count(in);n>0;n--) tags.add(new Tag(uuid(in),in.readUTF(),in.readInt()));
                if(schema<=10) for(int n=count(in);n>0;n--) { uuid(in); in.readInt(); instant(in); }   // practice-gym records
                var theme=ThemeId.known(in.readUTF());var trainer=TrainerId.valueOf(in.readUTF());
                int goal=in.readInt(),floor=in.readInt();
                // Schema 6 and earlier had no week-start preference; those vaults
                // keep the Monday the app has always assumed.
                var weekStart=schema>=7?java.time.DayOfWeek.valueOf(in.readUTF()):java.time.DayOfWeek.MONDAY;
                // Schema 12 alone carried a companion-portrait choice. The
                // feature is gone, so its bytes are read and dropped; without that
                // every record after them would be read at the wrong offset.
                if(schema==12&&in.readBoolean()) in.readUTF();
                settings=new Settings(theme,trainer,goal,floor,weekStart);
            }
            var campaign = schema >= 11 ? new Campaign(in.readLong(), in.readLong(), in.readLong())
                : collection != null ? collection.campaign() : Campaign.start(0);
            var rewards=new ArrayList<Reward>();
            if(schema>=10) for(int n=count(in);n>0;n--) {
                var id=uuid(in); int dex=in.readInt(), level=in.readInt(); var earned=instant(in);
                var delivered=in.readBoolean()?instant(in):null;
                rewards.add(new Reward(id,dex,level,earned,delivered));
            }
            GameSave game = null;
            if (schema >= 11 && in.readBoolean()) {
                var updated = instant(in);
                int length = in.readInt();
                if (length <= 0 || length > GameSave.MAX_BYTES) throw new IOException("Invalid game save.");
                game = new GameSave(in.readNBytes(length), updated);
            }
            if(in.available()!=0)throw new IOException("Unexpected vault content.");
            return new State(activities,sessions,blocks,recurring,tasks,habits,tags,settings,campaign,
                collection != null ? collection.rewards(rewards) : rewards, game);
        }
        catch(RuntimeException e) {
            throw new IOException("Invalid vault data.",e);
        }
        finally {
            Arrays.fill(bytes,(byte)0);
        }
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
     * backup: the copy that was asked for has already been written.
     */
    public void backup() throws IOException {
        Path backup=Path.of(path+".reset-"+System.currentTimeMillis()+"-"+UUID.randomUUID()+".bak");
        Files.copy(path,backup);
        try { Files.setPosixFilePermissions(backup,PosixFilePermissions.fromString("rw-------")); }
        catch(UnsupportedOperationException ignored) { }
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

    public void close()throws IOException {
        Arrays.fill(key,(byte)0);
        lock.release();
        channel.close();
    }
}
