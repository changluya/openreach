package io.github.changlu.openreach.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads an upstream response into memory with an explicit hard limit.
 *
 * <p>Search providers return HTML/JSON that must be parsed in memory. This
 * helper prevents a broken or hostile upstream from turning an otherwise small
 * search request into an unbounded JVM allocation.</p>
 */
public final class BoundedBodyReader {
    private BoundedBodyReader() {}

    public static byte[] read(InputStream input, int configuredMaxBytes, String sourceName) throws IOException {
        int maxBytes = Math.max(1024, configuredMaxBytes);
        String source = sourceName == null || sourceName.isBlank() ? "upstream" : sourceName.trim();
        try (input; ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024))) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new UpstreamException(source + " response body exceeds configured limit=" + maxBytes + " bytes");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }
}
