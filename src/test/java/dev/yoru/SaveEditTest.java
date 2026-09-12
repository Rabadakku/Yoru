package dev.yoru;

import dev.yoru.application.Repository;
import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.GameSave;
import dev.yoru.domain.Model.State;
import dev.yoru.game.Gen3Fixture;
import dev.yoru.game.StorageEdit;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * The commit behind storage edits: one write, a backup first, and a refusal
 * when the save changed underneath. Also proves two vaults holding the same
 * ROM save diverge independently (#40).
 */
public final class SaveEditTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-11T12:00:00Z"), ZoneOffset.UTC);

    private static final class Vault implements Repository {
        State state = State.empty();
        int saves, backups;
        boolean fail;
        public State load() { return state; }
        public void save(State next) throws IOException { if (fail) throw new IOException("vault write failed"); saves++; state = next; }
        public void backup() throws IOException { if (fail) throw new IOException("backup failed"); backups++; }
        public void close() { }
    }

    private static Tracker trackerWith(Vault repo, byte[] saveBytes) throws IOException {
        repo.state = State.empty().withGame(new GameSave(saveBytes, CLOCK.instant()));
        return new Tracker(repo, CLOCK);
    }

    /** The edit commits in one write, after a backup, and touches nothing else. */
    private static void commitsOneWriteAfterABackup() throws IOException {
        var repo = new Vault();
        var tracker = trackerWith(repo, Gen3Fixture.save(2, 4));
        byte[] before = tracker.state().game().bytes();
        byte[] after = StorageEdit.renameBox(before, 0, "STUDY");

        tracker.editSave(before, after);
        check(repo.saves == 1, "the save was committed in one write, got " + repo.saves);
        check(repo.backups == 1, "the vault was backed up first, got " + repo.backups);
        check(tracker.state().game().holds(after), "the vault holds the edited save");
        check(tracker.state().rewards().isEmpty(), "and the reward ledger was not touched");
    }

    /** An edit planned against one save is never written over another. */
    private static void refusesWhenTheSaveChangedUnderneath() throws IOException {
        var repo = new Vault();
        var tracker = trackerWith(repo, Gen3Fixture.save(2, 4));
        byte[] before = tracker.state().game().bytes();
        byte[] other = Gen3Fixture.save(4, 4);
        boolean refused = false;
        try { tracker.editSave(other, StorageEdit.renameBox(other, 0, "STUDY")); }
        catch (IllegalStateException e) { refused = true; }
        check(refused, "an edit planned against another save is refused");
        check(tracker.state().game().holds(before), "and the vault's save is untouched");
        check(repo.saves == 0 && repo.backups == 0, "with nothing written or backed up");
    }

    /** A failed backup blocks the edit, exactly as it blocks a reset. */
    private static void aFailedBackupBlocksTheEdit() throws IOException {
        var repo = new Vault();
        var tracker = trackerWith(repo, Gen3Fixture.save(2, 4));
        byte[] before = tracker.state().game().bytes();
        byte[] after = StorageEdit.renameBox(before, 0, "STUDY");
        repo.fail = true;
        boolean refused = false;
        try { tracker.editSave(before, after); } catch (IOException e) { refused = true; }
        check(refused, "a failed backup is reported");
        check(tracker.state().game().holds(before), "and the save is unchanged");
        check(repo.saves == 0, "with nothing written");
    }

    /**
     * A run of edits is backed up once, before its first: arranging a box is
     * many moves, and a copy of the vault per move would fill the disk. A save
     * arriving from elsewhere in between — the game saving — starts a new run.
     */
    private static void backsUpOncePerRunOfEdits() throws IOException {
        var repo = new Vault();
        var tracker = trackerWith(repo, Gen3Fixture.save(2, 4));
        byte[] first = tracker.state().game().bytes();
        byte[] second = StorageEdit.renameBox(first, 0, "STUDY");
        tracker.editSave(first, second);
        byte[] third = StorageEdit.wallpaper(second, 0, 5);
        tracker.editSave(second, third);
        check(repo.backups == 1, "two edits in a row are backed up once, got " + repo.backups);
        check(repo.saves == 2, "and each is still its own write");

        byte[] fromTheGame = Gen3Fixture.save(4, 4);
        tracker.gameSaved(fromTheGame);
        tracker.editSave(fromTheGame, StorageEdit.renameBox(fromTheGame, 1, "NEXT"));
        check(repo.backups == 2, "a save from the game starts a new run, backed up again, got " + repo.backups);
    }

    /** Two vaults holding the same ROM save diverge independently — #40's isolation criterion. */
    private static void twoVaultsDivergeIndependently() throws IOException {
        byte[] sameRomSave = Gen3Fixture.save(2, 4);
        var one = new Vault();
        var two = new Vault();
        var a = trackerWith(one, sameRomSave);
        var b = trackerWith(two, sameRomSave);

        var aAfter = StorageEdit.renameBox(sameRomSave, 0, "ALPHA");
        a.editSave(sameRomSave, aAfter);
        var bAfter = StorageEdit.wallpaper(sameRomSave, 1, 9);
        b.editSave(sameRomSave, bAfter);

        check(!one.state.game().equals(two.state.game()), "each vault holds its own edited save");
        check(a.state().game().holds(aAfter), "vault A has only its own rename");
        check(b.state().game().holds(bAfter), "vault B has only its own wallpaper change");
    }

    public static void main(String[] args) throws IOException {
        commitsOneWriteAfterABackup();
        refusesWhenTheSaveChangedUnderneath();
        aFailedBackupBlocksTheEdit();
        twoVaultsDivergeIndependently();
        backsUpOncePerRunOfEdits();
        System.out.println("PASS: " + checks + " save-edit checks (one write, backup first, changed-save refusal, vault isolation)");
    }
}
