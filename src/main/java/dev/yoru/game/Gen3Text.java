package dev.yoru.game;

/**
 * The character set the Gen 3 games store names in.
 *
 * Not ASCII: letters, digits and punctuation each sit in their own run, and a
 * name ends with 0xFF rather than being padded. Nicknames and trainer names in
 * a save are written this way, so anything Yoru puts into a save has to be too.
 *
 * Characters with no equivalent become a question mark rather than being
 * dropped or written raw. A name is displayed by the game exactly as stored, so
 * silently emitting a byte outside the table would show as a control glyph and
 * look like corruption.
 */
final class Gen3Text {

    static final int TERMINATOR = 0xFF, SPACE = 0x00;

    /** Index is the byte value; the entry is the character it stands for. */
    private static final char[] TABLE = new char[256];

    static {
        java.util.Arrays.fill(TABLE, '\0');
        TABLE[SPACE] = ' ';
        for (int i = 0; i < 10; i++) TABLE[0xA1 + i] = (char) ('0' + i);
        TABLE[0xAB] = '!'; TABLE[0xAC] = '?'; TABLE[0xAD] = '.'; TABLE[0xAE] = '-';
        TABLE[0xB0] = '.';                       // the game's ellipsis glyph
        TABLE[0xB1] = '"'; TABLE[0xB2] = '"'; TABLE[0xB3] = '\''; TABLE[0xB4] = '\'';
        TABLE[0xB5] = '♂'; TABLE[0xB6] = '♀';
        TABLE[0xB8] = ','; TABLE[0xBA] = '/';
        for (int i = 0; i < 26; i++) TABLE[0xBB + i] = (char) ('A' + i);
        for (int i = 0; i < 26; i++) TABLE[0xD5 + i] = (char) ('a' + i);
    }

    private Gen3Text() { }

    /** Reads up to {@code length} bytes, stopping at the terminator. */
    static String read(byte[] bytes, int at, int length) {
        var out = new StringBuilder(length);
        for (int i = 0; i < length && at + i < bytes.length; i++) {
            int value = bytes[at + i] & 0xFF;
            if (value == TERMINATOR) break;
            char c = TABLE[value];
            out.append(c == '\0' ? '?' : c);
        }
        return out.toString();
    }

    /**
     * Writes a name into a fixed-width field, terminated and padded.
     *
     * The whole field is filled with the terminator first, because a shorter
     * name written over a longer one must not leave the old tail visible.
     */
    static void write(String text, byte[] bytes, int at, int length) {
        for (int i = 0; i < length; i++) bytes[at + i] = (byte) TERMINATOR;
        int written = 0;
        for (int i = 0; i < text.length() && written < length; i++) {
            int value = encode(text.charAt(i));
            if (value < 0) value = 0xAC;         // an unmappable character shows as "?"
            bytes[at + written++] = (byte) value;
        }
    }

    /** The stored byte for a character, or -1 when it has no equivalent. */
    static int encode(char c) {
        for (int value = 0; value < TABLE.length; value++)
            if (TABLE[value] == c && !(value == 0xB2 || value == 0xB4 || value == 0xB0))
                return value;
        return -1;
    }

    /** How long a name is, in stored bytes, before the terminator. */
    static int length(byte[] bytes, int at, int limit) {
        for (int i = 0; i < limit; i++) if ((bytes[at + i] & 0xFF) == TERMINATOR) return i;
        return limit;
    }
}
