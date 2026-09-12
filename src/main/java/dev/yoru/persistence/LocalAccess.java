package dev.yoru.persistence;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.*;

/** Convenience mode: the unlock secret is beside the vault, never embedded in the app. */
public final class LocalAccess {
    private LocalAccess() {}
    public static Path keyPath(Path vault) { return Path.of(vault.toAbsolutePath()+".local-key"); }
    public static boolean enabled(Path vault) { return Files.isRegularFile(keyPath(vault)); }
    public static char[] read(Path vault) throws IOException {
        Path path=keyPath(vault);
        if(Files.size(path)!=64) throw new IOException("Invalid local unlock key.");
        String value=Files.readString(path);
        if(!value.matches("[0-9a-f]{64}")) throw new IOException("Invalid local unlock key.");
        return value.toCharArray();
    }
    public static char[] create(Path vault) throws IOException {
        if(Files.exists(vault)) throw new IOException("Choose a new vault for password-free setup.");
        byte[] random=new byte[32];new SecureRandom().nextBytes(random);
        String value=HexFormat.of().formatHex(random); Arrays.fill(random,(byte)0);
        Path key=keyPath(vault);
        try {
            Files.createFile(key,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch(UnsupportedOperationException e) { Files.createFile(key); }
        Files.writeString(key,value,StandardOpenOption.WRITE);
        return value.toCharArray();
    }
}
