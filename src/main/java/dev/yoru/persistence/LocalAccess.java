package dev.yoru.persistence;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.*;

/** Convenience mode: the unlock secret is beside the vault, never embedded in the app. */
public final class LocalAccess {
    /** What an unlock key's file name adds to the name of the vault it opens. */
    public static final String SUFFIX = ".local-key";
    private LocalAccess() {}
    public static Path keyPath(Path vault) { return Path.of(vault.toAbsolutePath()+SUFFIX); }
    public static boolean enabled(Path vault) { return Files.isRegularFile(keyPath(vault)); }
    public static char[] read(Path vault) throws IOException { return readKey(keyPath(vault)); }

    /**
     * A key file that was read and is not a key: whatever it holds opens
     * nothing. Told apart from a file that could not be read, which might.
     */
    static final class Malformed extends IOException {
        Malformed() { super("Invalid local unlock key."); }
    }

    /**
     * Reads an unlock key from exactly this file, whatever it is called: a copy
     * in a staging folder, or a key not yet moved to the name it will open by.
     * Exactly 64 lowercase hexadecimal characters, with nothing after them.
     */
    static char[] readKey(Path key) throws IOException {
        if(Files.size(key)!=64) throw new Malformed();
        byte[] bytes=Files.readAllBytes(key);
        char[] value=new char[bytes.length];
        try {
            if(bytes.length!=64) throw new Malformed();
            for(int i=0;i<bytes.length;i++) {
                char c=(char)(bytes[i]&0xFF);
                if(!(c>='0'&&c<='9'||c>='a'&&c<='f')) throw new Malformed();
                value[i]=c;
            }
            return value;
        } catch(Malformed e) {
            Arrays.fill(value,'\0');
            throw e;
        } finally {
            Arrays.fill(bytes,(byte)0);
        }
    }
    public static char[] create(Path vault) throws IOException {
        if(Files.exists(vault)) throw new IOException("Choose a new vault for password-free setup.");
        return write(vault);
    }

    /**
     * Writes a fresh unlock key beside a vault, whether or not the vault is
     * already there.
     *
     * {@link #create} is for a vault that does not exist yet and refuses one
     * that does, which is the right guard when a key is being written before
     * its vault. Taking the password off a vault is the other way round: the
     * vault exists, holds everything, and is what the key is being made for.
     */
    public static char[] write(Path vault) throws IOException { return writeKey(keyPath(vault)); }

    /**
     * Writes a fresh unlock key to exactly this file, and flushes it to the disk.
     *
     * A vault is re-encrypted under this key and flushed straight after, so the
     * key has to be on the disk first: a power cut that kept the vault's new
     * bytes and lost the key's would leave a vault nothing can ever open. A
     * write that fails takes back the file it made, because an empty or short
     * key is one that opens nothing.
     */
    static char[] writeKey(Path key) throws IOException {
        byte[] random=new byte[32];new SecureRandom().nextBytes(random);
        String value=HexFormat.of().formatHex(random); Arrays.fill(random,(byte)0);
        byte[] bytes=value.getBytes(StandardCharsets.US_ASCII);
        Files.deleteIfExists(key);
        boolean made=false;
        try {
            try(var channel=createPrivate(key)) {
                made=true;
                var data=ByteBuffer.wrap(bytes);
                while(data.hasRemaining()) channel.write(data);
                channel.force(true);
            }
            VaultStore.forceDir(key.toAbsolutePath().getParent());
        } catch(IOException|RuntimeException e) {
            if(made) try { Files.deleteIfExists(key); } catch(IOException ignored) { }
            throw e;
        } finally {
            Arrays.fill(bytes,(byte)0);
        }
        return value.toCharArray();
    }

    /** A new file only its owner can read, where the platform can say so. */
    private static FileChannel createPrivate(Path key) throws IOException {
        try {
            return FileChannel.open(key,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch(UnsupportedOperationException e) {
            return FileChannel.open(key,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
        }
    }
}
