package dev.yoru.game;

import java.util.ArrayList;

public final class SaveTransferTest {
    private static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) {
        var acknowledgements = new ArrayList<Runnable>();
        var transfer = new SaveTransfer(new byte[]{0}, (bytes, acknowledged) -> acknowledgements.add(acknowledged));
        check(transfer.durable());
        check(!transfer.offer(new byte[]{0}));
        check(transfer.offer(new byte[]{1}));
        check(!transfer.durable());
        check(!transfer.offer(new byte[]{1}));
        check(transfer.offer(new byte[]{2}));
        acknowledgements.getFirst().run();
        check(!transfer.durable());
        acknowledgements.getLast().run();
        check(transfer.durable());
        acknowledgements.getFirst().run();
        check(transfer.durable());
        var fail = new boolean[]{true};
        var retry = new SaveTransfer(new byte[]{0}, (bytes, acknowledged) -> {
            if (fail[0]) throw new IllegalStateException("writer refused");
            bytes[0] = 9; // A receiver cannot mutate the deduplication snapshot.
            acknowledged.run();
        });
        try { retry.offer(new byte[]{1}); throw new AssertionError(); }
        catch (IllegalStateException expected) { }
        check(!retry.durable());
        fail[0] = false;
        check(retry.offer(new byte[]{1}));
        check(retry.durable());
        check(!retry.offer(new byte[]{1}));
        System.out.println("PASS: save receipts wait for durability, reject stale acknowledgements and retry refused dispatch");
    }
}
