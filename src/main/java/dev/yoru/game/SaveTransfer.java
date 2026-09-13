package dev.yoru.game;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** Tracks dispatch separately from durability without waiting on the writer thread. */
final class SaveTransfer {
    private byte[] dispatched;
    private volatile AtomicBoolean receipt = new AtomicBoolean(true);
    private final GameSession.SaveSink sink;

    SaveTransfer(byte[] initial, GameSession.SaveSink sink) {
        dispatched = initial.clone();
        this.sink = sink;
    }

    synchronized boolean offer(byte[] bytes) {
        if (Arrays.equals(bytes, dispatched)) return false;
        byte[] snapshot = bytes.clone();
        var next = new AtomicBoolean();
        receipt = next;
        sink.saved(snapshot.clone(), () -> next.set(true));
        dispatched = snapshot;
        return true;
    }

    boolean durable() { return receipt.get(); }
}
