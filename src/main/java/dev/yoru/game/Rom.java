package dev.yoru.game;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The player's own game file, held in memory while Yoru reads from it.
 *
 * Only ever read: Yoru never writes a ROM, never copies one anywhere, and never
 * ships one. Tables are found by their shape rather than at fixed addresses
 * wherever that is practical, because the supported game is a variant whose
 * tables need not sit where the original's do.
 */
public final class Rom {

    /** Where the cartridge is mapped on the GBA; pointers inside a ROM are relative to it. */
    public static final long BASE = 0x08000000L;
    private static final long MAX_BYTES = 32L << 20;

    private final byte[] bytes;

    private Rom(byte[] bytes) { this.bytes = bytes; }

    /** A ROM image already in memory. Tests build synthetic ones. */
    public static Rom of(byte[] bytes) { return new Rom(bytes.clone()); }

    private static Path cachedPath;
    private static long cachedStamp;
    private static Rom cached;

    /** Reads a game file, reusing the last one read while it is unchanged on disk. */
    public static synchronized Rom load(Path path) throws IOException {
        long size = Files.size(path);
        long stamp = Files.getLastModifiedTime(path).toMillis() * 31 + size;
        if (cached != null && path.equals(cachedPath) && stamp == cachedStamp) return cached;
        if (size > MAX_BYTES) throw new IOException(path.getFileName() + " is too large to be a GBA game.");
        cached = new Rom(Files.readAllBytes(path));
        cachedPath = path;
        cachedStamp = stamp;
        return cached;
    }

    public int size() { return bytes.length; }

    /** Whether a run of bytes this long, starting here, is inside the ROM. */
    boolean within(int at, int length) { return at >= 0 && length >= 0 && (long) at + length <= bytes.length; }

    int u8(int at) { return bytes[at] & 0xFF; }
    int u16(int at) { return u8(at) | u8(at + 1) << 8; }
    long u32(int at) { return (u16(at) | (long) u16(at + 2) << 16) & 0xFFFFFFFFL; }

    /** Whether a value points inside this ROM. */
    boolean pointer(long value) { return value >= BASE && value < BASE + bytes.length; }

    int offset(long pointer) { return (int) (pointer - BASE); }
}
