package io.github.changlu.openreach.monitor;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Repairs the reversible mojibake produced when UTF-8 bytes were decoded as ISO-8859-1.
 *
 * <p>The repair is intentionally conservative. It only runs when the source contains
 * strong mojibake markers, every character can be represented as a single Latin-1 byte,
 * the reconstructed byte stream is valid UTF-8, and the repaired value materially reduces
 * mojibake markers while introducing genuine non-Latin Unicode content.</p>
 */
public final class Utf8MojibakeRepair {
    private Utf8MojibakeRepair() {
    }

    public static String repairIfNeeded(String value) {
        if (value == null || value.isBlank()) return value;

        int beforeScore = mojibakeScore(value);
        if (beforeScore < 3 || !isLatin1RoundTrippable(value)) return value;

        byte[] originalBytes = value.getBytes(StandardCharsets.ISO_8859_1);
        String repaired;
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(originalBytes));
            repaired = decoded.toString();
        } catch (CharacterCodingException ex) {
            return value;
        }

        int afterScore = mojibakeScore(repaired);
        if (afterScore >= beforeScore) return value;
        if (!containsNonLatinUnicode(repaired)) return value;
        return repaired;
    }

    static boolean looksRepairable(String value) {
        return value != null && mojibakeScore(value) >= 3 && isLatin1RoundTrippable(value);
    }

    private static boolean isLatin1RoundTrippable(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 0x00FF) return false;
        }
        return true;
    }

    private static boolean containsNonLatinUnicode(String value) {
        return value.codePoints().anyMatch(cp -> cp > 0x00FF);
    }

    private static int mojibakeScore(String value) {
        int score = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch >= 0x0080 && ch <= 0x009F) {
                score += 3;
                continue;
            }
            switch (ch) {
                case 'Â', 'Ã', 'â' -> score += 3;
                case 'ä', 'å', 'æ', 'ç', 'è', 'é' -> score += 1;
                default -> { }
            }
        }
        return score;
    }
}
