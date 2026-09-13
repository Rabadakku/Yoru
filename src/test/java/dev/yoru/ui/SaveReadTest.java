package dev.yoru.ui;

import dev.yoru.application.Repository;
import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.*;
import dev.yoru.game.Gen3Fixture;
import dev.yoru.game.Gen3Save;
import java.io.IOException;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;

/**
 * The vault's save as the pages see it (#5): absent, unreadable with a reason,
 * or readable with its party and PC counted, and never changed by being read.
 */
public final class SaveReadTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static final class Memory implements Repository {
        State state = State.empty();
        boolean failing;
        public State load() { return state; }
        public void save(State next) throws IOException {
            if (failing) throw new IOException("The disk is full.");
            state = next;
        }
        public void close() { }
    }

    public static void main(String[] args) throws Exception {
        var repo = new Memory();
        var tracker = new Tracker(repo, Clock.systemUTC());

        var absent = GameView.read(tracker.state());
        check(absent.kind() == GameView.SaveKind.ABSENT && absent.save() == null && absent.savedAt() == null,
            "no save is absent, with nothing to show");
        check(absent.headline().equals("No game save yet"), "and says so");
        check(absent.detail().equals("Save once inside the game to show your party here."), "with the next step");
        check(absent.editBlock(false) != null, "and there is nothing to edit");

        byte[] wrongSize = new byte[1000];
        Arrays.fill(wrongSize, (byte) 7);
        tracker.replaceGameSave(wrongSize);
        var unreadable = GameView.read(tracker.state());
        check(unreadable.kind() == GameView.SaveKind.UNREADABLE, "bytes that are not a save are unreadable, not absent");
        check(unreadable.problem() == Gen3Save.Unreadable.WRONG_SIZE, "and carry their reason");
        check(unreadable.headline().equals("Your save is here, but Yoru cannot read it."),
            "the headline keeps the save's presence first");
        check(!unreadable.detail().contains("bytes") && !unreadable.detail().contains("Exception"),
            "the detail is a sentence, not an internal message: " + unreadable.detail());
        check(unreadable.savedAt().equals(tracker.state().game().updatedAt()), "its saved time is still the vault's");
        check(unreadable.editBlock(false) != null, "an unreadable save is never edited");
        check(tracker.state().game().holds(wrongSize), "reading it changed nothing");

        var raw = Gen3Fixture.withTrainer(Gen3Fixture.save(2, 0), "TESTER", 0, 12345, 54321);
        raw = Gen3Fixture.withParty(raw, List.of(Gen3Fixture.member(raw, 258, 5, 1), Gen3Fixture.member(raw, 25, 7, 2)));
        tracker.replaceGameSave(raw);
        var readable = GameView.read(tracker.state());
        check(readable.kind() == GameView.SaveKind.READABLE && readable.save() != null, "a complete save reads");
        check(readable.partyCount() == 2 && readable.boxedCount() == 0, "with its party and PC counted, got "
            + readable.partyCount() + " / " + readable.boxedCount());
        check(!readable.save().hasStarter(), "this fixture never set the starter flag");
        check(readable.editBlock(false) == null, "a closed, consistent save can be edited");
        check(readable.editBlock(true) != null, "but not while the game runs");

        // A failed write leaves the last durable time where it was.
        var before = tracker.state().game().updatedAt();
        repo.failing = true;
        try {
            tracker.replaceGameSave(Gen3Fixture.save(4, 0));
            check(false, "a failing disk refuses the write");
        } catch (IOException expected) { }
        check(GameView.read(tracker.state()).savedAt().equals(before), "the saved time does not advance on a failed write");
        check(GameView.read(tracker.state()).partyCount() == 2, "and the page still reads the save that was kept");

        System.out.println("PASS: " + checks + " save read model checks (absent, unreadable reason, counts, edit block, failed write)");
    }
}
