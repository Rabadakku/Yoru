package dev.yoru.ui;

import dev.yoru.application.*;
import dev.yoru.domain.Model.State;
import dev.yoru.game.Gen3Fixture;
import java.io.IOException;
import java.time.Clock;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;

/** A disk failure must not let closing discard the last game save. */
public final class GameSaveFailureTest {
    private static final class Memory implements Repository {
        State state=State.empty();boolean fail=true;
        public State load(){return state;}
        public void save(State next)throws IOException {if(fail)throw new IOException("test disk unavailable");state=next;}
        public void close(){}
    }
    public static void main(String[] args)throws Exception {
        var repo=new Memory();var tracker=new Tracker(repo,Clock.systemUTC());
        var game=new GameController(tracker);byte[] save=Gen3Fixture.save(1,3);
        SwingUtilities.invokeAndWait(()->game.saved(save));
        if(tracker.state().game()!=null)throw new AssertionError("failed save published");
        var phase=GameController.class.getDeclaredField("phase");phase.setAccessible(true);
        SwingUtilities.invokeAndWait(()-> {try{phase.set(game,GameController.Phase.RUNNING);}catch(Exception e){throw new RuntimeException(e);} });
        var closed=new CountDownLatch(1);
        SwingUtilities.invokeAndWait(()->game.close(closed::countDown));
        if(!closed.await(5,TimeUnit.SECONDS))throw new AssertionError("close stalled");
        SwingUtilities.invokeAndWait(()-> {
            if(game.phase()!=GameController.Phase.STUCK)throw new AssertionError("close discarded pending save");
            if(!game.running())throw new AssertionError("vault switching is not blocked");
        });
        repo.fail=false;var retried=new CountDownLatch(1);
        SwingUtilities.invokeAndWait(()->game.close(retried::countDown));
        if(!retried.await(5,TimeUnit.SECONDS))throw new AssertionError("retry stalled");
        SwingUtilities.invokeAndWait(()-> {
            if(game.phase()!=GameController.Phase.IDLE)throw new AssertionError("retry did not finish");
            if(!Arrays.equals(save,tracker.state().game().bytes()))throw new AssertionError("latest save not recovered");
        });
        var acknowledged=new java.util.concurrent.atomic.AtomicInteger();
        repo.fail=true;
        SwingUtilities.invokeAndWait(()->game.enqueueSave(0,save,acknowledged::incrementAndGet));
        SwingUtilities.invokeAndWait(()-> { });
        // Use changed bytes so Tracker cannot satisfy the request idempotently.
        byte[] newer=Gen3Fixture.save(2,4);
        SwingUtilities.invokeAndWait(()->game.enqueueSave(0,newer,acknowledged::incrementAndGet));
        SwingUtilities.invokeAndWait(()-> {
            if(game.saveStatus()!=GameController.SaveStatus.FAILED)throw new AssertionError("failed write looks saved");
        });
        int before=acknowledged.get();
        repo.fail=false;
        SwingUtilities.invokeAndWait(game::retrySave);
        SwingUtilities.invokeAndWait(()-> {
            if(acknowledged.get()!=before+1)throw new AssertionError("durable save was not acknowledged once");
            if(!Arrays.equals(newer,tracker.state().game().bytes()))throw new AssertionError("retry persisted stale bytes");
            game.enqueueSave(-1,save,()-> {throw new AssertionError("stale session acknowledged");});
        });
        SwingUtilities.invokeAndWait(()-> {
            if(!Arrays.equals(newer,tracker.state().game().bytes()))throw new AssertionError("stale session crossed vault boundary");
        });
        byte[] newest=Gen3Fixture.save(3,5);
        int acknowledgedBefore=acknowledged.get();
        SwingUtilities.invokeAndWait(()-> {
            game.enqueueSave(0,newest,acknowledged::incrementAndGet);
            game.enqueueSave(-1,save,()-> {throw new AssertionError("stale producer acknowledged");});
        });
        SwingUtilities.invokeAndWait(()-> {
            if(!Arrays.equals(newest,tracker.state().game().bytes()))throw new AssertionError("stale producer displaced current save");
            if(acknowledged.get()!=acknowledgedBefore+1)throw new AssertionError("current save receipt lost");
        });
        System.out.println("PASS: failed game saves survive Close and retry after the vault recovers");
    }
}
