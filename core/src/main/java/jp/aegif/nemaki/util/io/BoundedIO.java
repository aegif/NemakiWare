package jp.aegif.nemaki.util.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Single source of truth for size-capped stream reads.
 *
 * <p>Any code that buffers a whole, caller- or remote-influenced stream into a
 * {@code byte[]} must go through here so an unbounded stream cannot exhaust the
 * heap. Unlike a read-then-check, this fails fast as soon as the cap is crossed
 * — nothing beyond {@code maxBytes} is ever held in memory.
 *
 * <p>The stream is NOT closed here (callers use try-with-resources).
 */
public final class BoundedIO {

    private BoundedIO() {
    }

    /**
     * Read {@code in} fully into a byte[], throwing {@link IOException} the
     * moment more than {@code maxBytes} have been read.
     *
     * @param what human-readable label for the error message
     */
    public static byte[] readBounded(InputStream in, int maxBytes, String what) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        long total = 0;
        while ((n = in.read(chunk)) != -1) {
            total += n;
            if (total > maxBytes) {
                throw new IOException(what + " exceeds maximum size of " + maxBytes + " bytes");
            }
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }
}
