/**
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
package jp.aegif.nemaki.rest.importexport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.rest.importexport.ImportExportUtils.DiscardableOutputStream;

/**
 * The stream that lets a refused ZIP be closed without being delivered.
 *
 * <h2>Why this needed its own test</h2>
 *
 * <p>The two properties it exists for pull against each other:
 * {@code ZipOutputStream.close()} is what frees the native deflater AND what writes the
 * central directory that makes an archive open. Closing on the refusal path handed back a
 * readable, silently short archive; not closing leaked the deflater. Two review rounds found
 * one each. This stream resolves it by being closed either way and, after
 * {@link DiscardableOutputStream#stopForwarding()}, swallowing what the close produces.
 *
 * <p>A third review pointed out that nothing measured the stream itself — the export locks
 * assert the SHAPE of the call sites (built over the sink, stop before close), so making
 * {@code stopForwarding()} a no-op, or letting a stopped {@code flush()} reach the delegate,
 * would have left every one of them green.
 */
class DiscardableOutputStreamTest {

    /** Records whether the delegate was touched, and fails loudly if it is after stopping. */
    private static final class Recording extends OutputStream {
        private final ByteArrayOutputStream received = new ByteArrayOutputStream();
        boolean flushed = false;
        boolean closed = false;
        boolean poisoned = false;

        @Override
        public void write(int b) throws IOException {
            if (poisoned) {
                throw new IOException("the delegate was written to after stopForwarding()");
            }
            received.write(b);
        }

        @Override
        public void flush() throws IOException {
            if (poisoned) {
                throw new IOException("the delegate was flushed after stopForwarding()");
            }
            flushed = true;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test
    @DisplayName("while forwarding, every byte and flush reaches the delegate")
    void theSuccessPathIsUntouched() throws Exception {
        Recording delegate = new Recording();
        DiscardableOutputStream sink = new DiscardableOutputStream(delegate);

        sink.write('h');
        sink.write(new byte[] {'e', 'l', 'l', 'o'}, 1, 3);
        sink.flush();

        // offset 1, length 3 of {e,l,l,o} is "llo"
        assertArrayEquals("hllo".getBytes(), delegate.received.toByteArray(),
                "the sink dropped bytes on the ordinary path");
        assertTrue(delegate.flushed, "flush did not reach the delegate");
    }

    @Test
    @DisplayName("after stopForwarding, writes and flushes are dropped, not delegated")
    void aStoppedSinkDelegatesNothing() throws Exception {
        Recording delegate = new Recording();
        DiscardableOutputStream sink = new DiscardableOutputStream(delegate);
        sink.write('a');
        sink.stopForwarding();
        delegate.poisoned = true;

        // This is what ZipOutputStream.close() does on the refusal path: it writes the
        // central directory and flushes. None of it may reach the client.
        assertDoesNotThrow(() -> {
            sink.write('X');
            sink.write(new byte[] {'Y', 'Z'}, 0, 2);
            sink.flush();
            sink.close();
        }, "a stopped sink reached the delegate, so the central directory was delivered and "
                + "the refused archive opens");
        assertArrayEquals("a".getBytes(), delegate.received.toByteArray(),
                "bytes written after stopForwarding reached the delegate");
    }

    @Test
    @DisplayName("the sink never closes the response stream — the container owns it")
    void theDelegateIsNotClosed() throws Exception {
        Recording delegate = new Recording();
        DiscardableOutputStream sink = new DiscardableOutputStream(delegate);

        sink.close();

        assertFalse(delegate.closed,
                "the sink closed the container's response stream, which is not its to close");
    }
}
