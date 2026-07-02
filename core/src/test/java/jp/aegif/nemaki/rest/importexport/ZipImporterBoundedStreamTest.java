package jp.aegif.nemaki.rest.importexport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ZipImporter#boundedStream} (3.2.1 hardening).
 *
 * <p>{@code createContentStreamFromZip} trusts {@code ZipEntry.getSize()} (the
 * uploader-controlled central-directory value) for its up-front check but must
 * also bound the ACTUAL bytes streamed so a mismatched / lying entry cannot
 * exceed {@code MAX_SINGLE_FILE_SIZE}. Streaming is preserved (no full read into
 * heap); the wrapper throws once the cumulative byte count passes the cap.
 */
class ZipImporterBoundedStreamTest {

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[7]; // small buffer to exercise the array read() path
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    @Test
    void underCap_readsAllBytes() throws IOException {
        byte[] data = "hello world".getBytes();
        InputStream bounded = ZipImporter.boundedStream(new ByteArrayInputStream(data), 100, "e.txt");
        assertArrayEquals(data, readAll(bounded));
    }

    @Test
    void exactlyAtCap_isAllowed() throws IOException {
        byte[] data = new byte[64];
        InputStream bounded = ZipImporter.boundedStream(new ByteArrayInputStream(data), 64, "e.bin");
        assertEquals(64, readAll(bounded).length);
    }

    @Test
    void overCap_throws_viaArrayRead() {
        byte[] data = new byte[65];
        InputStream bounded = ZipImporter.boundedStream(new ByteArrayInputStream(data), 64, "big.bin");
        IOException ex = assertThrows(IOException.class, () -> readAll(bounded));
        assertEquals(true, ex.getMessage().contains("big.bin"));
    }

    @Test
    void overCap_throws_viaSingleByteRead() {
        byte[] data = new byte[3];
        InputStream bounded = ZipImporter.boundedStream(new ByteArrayInputStream(data), 2, "b.bin");
        assertThrows(IOException.class, () -> {
            for (int i = 0; i < data.length; i++) {
                if (bounded.read() == -1) break;
            }
        });
    }
}
