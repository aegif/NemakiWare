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
package jp.aegif.nemaki.evidence;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Digests the bytes as they are written, and refuses to answer when it cannot be sure.
 *
 * <p>E1 records the bytes the repository RECEIVED (see
 * {@code docs/design/adr-e1-durable-commitment.md}, decision 2), so the digest has to be taken
 * on the one pass that carries them to storage. That pass is not simple:
 *
 * <ul>
 * <li>the attachment writer <b>retries</b> on a revision conflict, and rewinds the stream with
 *     {@code mark}/{@code reset} to do it. A plain {@link java.security.DigestInputStream} would
 *     keep hashing across the rewind and produce the digest of the bytes sent TWICE;</li>
 * <li>a stream that does not support rewinding is retried anyway by the existing code, which
 *     sends whatever is left. There is no digest to be had in that case, and inventing one is
 *     worse than having none.</li>
 * </ul>
 *
 * <p>So: {@code mark} snapshots the digest, {@code reset} restores the snapshot. When the
 * snapshot cannot be taken — a provider whose {@link MessageDigest} is not cloneable — the
 * stream stops claiming a digest at all rather than returning one that may cover the wrong
 * bytes. {@link #digestIfTrustworthy()} returns null, the statement is not built, and the
 * journal row stays open as a gap an operator can see.
 *
 * <p><b>A wrong digest is worse than no digest.</b> No digest leaves the version without a
 * statement, which a verifier reports as {@code NOT_PRESENT}. A wrong one is recorded in the
 * chain as a fact, and every later check of that version fails against bytes that were never
 * wrong.
 */
public final class DigestingInputStream extends FilterInputStream {

    private MessageDigest digest;
    private MessageDigest marked;
    private long markedBytesRead;
    private boolean trustworthy = true;
    private long bytesRead;

    private DigestingInputStream(InputStream in, MessageDigest digest) {
        super(in);
        this.digest = digest;
    }

    /** @return null when SHA-256 is somehow unavailable, so callers cannot get a silent no-op */
    public static DigestingInputStream over(InputStream in) {
        if (in == null) {
            return null;
        }
        try {
            return new DigestingInputStream(in, MessageDigest.getInstance("SHA-256"));
        } catch (NoSuchAlgorithmException impossible) {
            return null;
        }
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0) {
            digest.update((byte) b);
            bytesRead++;
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n > 0) {
            digest.update(b, off, n);
            bytesRead += n;
        }
        return n;
    }

    @Override
    public synchronized void mark(int readlimit) {
        super.mark(readlimit);
        marked = snapshot(digest);
        markedBytesRead = bytesRead;
        if (marked == null) {
            // The rewind will be possible and the digest will not. Said now rather than at
            // reset, because by then the bytes are already double-counted.
            trustworthy = false;
        }
    }

    @Override
    public synchronized void reset() throws IOException {
        super.reset();
        MessageDigest restored = marked == null ? null : snapshot(marked);
        if (restored == null) {
            trustworthy = false;
            return;
        }
        digest = restored;
        // Back to the count AT THE MARK, not to zero: a mark taken part-way through would
        // otherwise leave the count short by everything before it, and the length check below
        // would reject a write that was perfectly correct.
        bytesRead = markedBytesRead;
    }

    /** A copy, so the snapshot and the live digest cannot advance together. */
    private static MessageDigest snapshot(MessageDigest source) {
        try {
            return (MessageDigest) source.clone();
        } catch (CloneNotSupportedException notCloneable) {
            return null;
        }
    }

    /**
     * Whether {@code in} can be rewound for a retry — <b>looking through this wrapper</b>.
     *
     * <p>The attachment writer decides whether a conflict may be retried with
     * {@code instanceof ByteArrayInputStream && markSupported()}. Wrapping the stream makes that
     * test false, which would silently turn a retryable conflict into a failed upload. This
     * method answers the SAME question about the stream underneath, so the decision is
     * unchanged whether or not a digest is being taken.
     */
    public static boolean isRewindable(InputStream in) {
        InputStream target = in instanceof DigestingInputStream digesting ? digesting.in : in;
        return target instanceof java.io.ByteArrayInputStream && target.markSupported();
    }

    /** Bytes counted since the last rewind. */
    public long bytesRead() {
        return bytesRead;
    }

    /**
     * The digest, or null when this stream cannot vouch for it.
     *
     * @param expectedLength the length the caller declared, or a negative number when it did not
     *        know. When it DID declare one and fewer bytes went past, the write did not send
     *        what it said it would and the digest covers something else — null, for the same
     *        reason as above.
     */
    public String digestIfTrustworthy(long expectedLength) {
        if (!trustworthy) {
            return null;
        }
        if (expectedLength >= 0 && expectedLength != bytesRead) {
            return null;
        }
        // A COPY, because MessageDigest.digest() resets the instance. Asking twice used to
        // return the digest of the empty input the second time — and that value looks exactly
        // like a digest, which is the trap this class's callers are warned about elsewhere.
        MessageDigest finished = snapshot(digest);
        if (finished == null) {
            return null;
        }
        byte[] hash = finished.digest();
        StringBuilder out = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    /** Overload for callers that never had a declared length to check against. */
    public String digestIfTrustworthy() {
        return digestIfTrustworthy(-1);
    }
}
