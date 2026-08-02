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
package jp.aegif.nemaki.rest.purview.journal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The durable file spool for version-independent facts — §6-a's landing zone for a fact that
 * could not be encoded as an event because the write-version flag was unreadable.
 *
 * <p>Layout: {@code {baseDir}/{repoSegment}/{yyyyMMdd}/fact-{spoolRecordId}.json}. The date
 * comes from the payload's {@code occurredAt} <b>in UTC</b> — never the wall clock — so a
 * retried fact always lands on the same path. {@code repoSegment} is §8's safe encoding of
 * the repository id (URL-safe base64 plus a hex hash prefix), never the raw id: a repository
 * id is external input, and {@code ../} in a path segment is an escape from the spool root;
 * the hash keeps two ids that differ only in letter case apart on case-insensitive
 * filesystems.
 *
 * <h2>Create-if-absent, not last-writer-wins</h2>
 *
 * <p>Publication is write-temp (0600, same directory) → write-loop + {@code force(true)} →
 * {@link Files#createLink} → directory fsync → remove temp. The hard link is the atomic
 * fail-if-exists step; an atomic <em>move</em> is a replacement, and two concurrent writers
 * with different bytes must not silently resolve to whichever finished last. There is no
 * {@code ATOMIC_MOVE} fallback: filesystems without hard links, POSIX permissions or
 * directory fsync fail the {@linkplain #probeReadiness() readiness probe} and every write
 * fails closed into the caller's dropped metric. Directory fsync failures propagate — a
 * swallowed one would let the probe pass on a filesystem that cannot honour the protocol.
 *
 * <p>Same id, different content: the incoming record is preserved as
 * {@code fact-{id}.quarantine.json} ({@code reason=digest_mismatch}) — the same id with
 * different bytes means the identity rule broke or someone tampered, and neither may be
 * silently overwritten. A quarantine file is itself never overwritten: with the slot
 * occupied, further variants are dropped loudly (the original and the first conflicter are
 * evidence enough; keeping every variant would let a bug flood the disk). A durable record
 * that fails re-verification is healed: the corrupt bytes move to the quarantine slot
 * ({@code reason=self_check_failed}) and the verified retry is republished; races with the
 * scanner's own quarantine move are absorbed by re-observing and retrying, bounded.
 */
public class LineageFactSpool {

    private static final Logger logger = LoggerFactory.getLogger(LineageFactSpool.class);

    private static final Pattern SPOOL_RECORD_ID = Pattern.compile("[0-9a-f]{64}");
    private static final DateTimeFormatter UTC_DAY =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    /** A spool record is small; anything bigger than this is not one of ours. */
    static final long DEFAULT_MAX_RECORD_BYTES = 32L * 1024 * 1024;
    /** Quarantine reasons — the two the design names. */
    static final String REASON_DIGEST_MISMATCH = "digest_mismatch";
    static final String REASON_SELF_CHECK_FAILED = "self_check_failed";

    private static final FileAttribute<Set<PosixFilePermission>> DIR_0700 =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
    private static final FileAttribute<Set<PosixFilePermission>> FILE_0600 =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

    public enum AppendOutcome { APPENDED, IDEMPOTENT, QUARANTINED, FAILED }

    private final Path baseDir;
    private final LineageMetrics metrics;
    private final long maxRecordBytes;
    private volatile Boolean ready;

    public LineageFactSpool(Path baseDir, LineageMetrics metrics) {
        this(baseDir, metrics, DEFAULT_MAX_RECORD_BYTES);
    }

    public LineageFactSpool(Path baseDir, LineageMetrics metrics, long maxRecordBytes) {
        if (baseDir == null) {
            throw new IllegalArgumentException("baseDir must not be null");
        }
        if (maxRecordBytes <= 0) {
            throw new IllegalArgumentException("maxRecordBytes must be positive");
        }
        this.baseDir = baseDir;
        this.metrics = metrics;
        this.maxRecordBytes = maxRecordBytes;
    }

    /**
     * Whether this filesystem supports the publication protocol — POSIX permissions, hard
     * links, file and directory fsync — probed once with a real write/link/fsync cycle. An
     * unsupported filesystem means every append fails closed; the metric to watch is the
     * caller's dropped counter, not silence.
     */
    public boolean probeReadiness() {
        Boolean cached = ready;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (ready != null) {
                return ready;
            }
            Path probeDir = baseDir.resolve(".probe");
            Path tmp = probeDir.resolve(".tmp-probe-" + UUID.randomUUID());
            Path target = probeDir.resolve("probe-" + UUID.randomUUID());
            try {
                // The spool root is operator-provisioned (§8: journaled mode with no usable
                // spool is NOT_READY, never auto-created): creating it here would leave its
                // directory entry — which lives in a parent outside the spool — un-fsyncable
                // by this class, and would follow whatever symlink an attacker parked there.
                if (!Files.isDirectory(baseDir, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(baseDir)) {
                    throw new IOException("spool base directory is absent or a symlink: "
                            + baseDir);
                }
                // §8: directory 0700 applies to the provisioned root too — a 0755 root would
                // expose every spooled fact. Normalised rather than rejected: tightening a
                // too-open mode is always safe, and failing on it would make a umask slip an
                // outage.
                Files.setPosixFilePermissions(baseDir,
                        PosixFilePermissions.fromString("rwx------"));
                createDirectoriesDurably(probeDir);
                writeDurably(tmp, new byte[] {'o', 'k'});
                Files.createLink(target, tmp);
                fsyncDirectory(probeDir);
                ready = true;
            } catch (IOException | UnsupportedOperationException | SecurityException e) {
                logger.error("Lineage fact spool is NOT ready — the filesystem at {} does not"
                        + " support the durable create-if-absent protocol ({}). Every spool"
                        + " write will fail closed.", baseDir, e.toString());
                ready = false;
            } finally {
                quietDelete(tmp);
                quietDelete(target);
            }
            return ready;
        }
    }

    /**
     * Durably records one fact; never throws. {@link AppendOutcome#FAILED} is the caller's cue
     * to raise its dropped metric ({@code lineage.emit.dropped}) — this store does not claim
     * the loss is repairable.
     */
    public AppendOutcome append(LineageSpoolPayloadV1 payload) {
        try {
            if (payload == null || !payload.selfVerifies()) {
                logger.warn("Refusing to spool a payload that fails self-verification");
                if (metrics != null) metrics.recordSpoolWriteFailed();
                return AppendOutcome.FAILED;
            }
            if (!SPOOL_RECORD_ID.matcher(payload.spoolRecordId()).matches()) {
                logger.warn("Refusing to spool a payload with a malformed spoolRecordId");
                if (metrics != null) metrics.recordSpoolWriteFailed();
                return AppendOutcome.FAILED;
            }
            byte[] bytes = LineageSpoolCodec.encode(payload).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > maxRecordBytes) {
                // Symmetric with the read bound: a record the reader would refuse must never
                // be written in the first place.
                logger.warn("Refusing to spool {}: {} bytes exceeds the {} byte record limit",
                        payload.spoolRecordId(), bytes.length, maxRecordBytes);
                if (metrics != null) metrics.recordSpoolWriteFailed();
                return AppendOutcome.FAILED;
            }
            if (!probeReadiness()) {
                if (metrics != null) metrics.recordSpoolWriteFailed();
                return AppendOutcome.FAILED;
            }
            return appendInternal(payload, bytes);
        } catch (RuntimeException e) {
            logger.warn("Spool append failed (business operation unaffected): {}", e.toString());
            if (metrics != null) metrics.recordSpoolWriteFailed();
            return AppendOutcome.FAILED;
        }
    }

    private AppendOutcome appendInternal(LineageSpoolPayloadV1 payload, byte[] bytes) {
        Path target = recordPath(payload);
        Path dir = target.getParent();
        Path tmp = dir.resolve(".tmp-" + payload.spoolRecordId() + "-" + UUID.randomUUID());
        try {
            createDirectoriesDurably(dir);
            writeDurably(tmp, bytes);
            // Bounded retry: link races (a scanner or a concurrent healer moving/creating the
            // target between our observations) re-observe and try again instead of failing a
            // verified retry.
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    createLinkAtomically(target, tmp);
                    fsyncDirectory(dir);
                    if (metrics != null) metrics.recordSpoolAppended();
                    return AppendOutcome.APPENDED;
                } catch (FileAlreadyExistsException exists) {
                    AppendOutcome outcome = converge(payload, target, tmp, dir);
                    if (outcome != null) {
                        return outcome;
                    }
                    // null = the target vanished mid-converge (scanner quarantined it);
                    // loop and republish.
                }
            }
            logger.warn("Spool link for {} kept racing; giving up this attempt",
                    payload.spoolRecordId());
            if (metrics != null) metrics.recordSpoolWriteFailed();
            return AppendOutcome.FAILED;
        } catch (IOException | UnsupportedOperationException e) {
            logger.warn("Spool write failed for {}: {}", payload.spoolRecordId(), e.toString());
            if (metrics != null) metrics.recordSpoolWriteFailed();
            return AppendOutcome.FAILED;
        } finally {
            quietDelete(tmp);
        }
    }

    /**
     * The target already exists: converge — idempotent when equal, quarantine when not, heal
     * when the durable copy is corrupt. Returns null when the target disappeared and the
     * caller should retry its link.
     */
    private AppendOutcome converge(LineageSpoolPayloadV1 incoming, Path target, Path tmp,
                                   Path dir) throws IOException {
        LineageSpoolPayloadV1 existing = null;
        boolean existingReadable = true;
        try {
            existing = readVerified(target);
        } catch (NoSuchFileException vanished) {
            return null;
        } catch (IOException | RuntimeException e) {
            existingReadable = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
            if (!existingReadable) {
                return null;
            }
            // Reason class only — a record that failed decoding is attacker-influenceable
            // bytes, and its parser messages echo them; spool payloads may carry stable keys
            // and legacy snapshots that must not reach ordinary logs.
            logger.warn("Existing spool record {} fails verification ({})", target,
                    e.getClass().getSimpleName());
        }
        if (existing != null && existing.payloadDigest().equals(incoming.payloadDigest())) {
            // Digests are recomputed inside readVerified, never trusted from the file — equal
            // digest of verified content means the same fact already landed. The directory is
            // fsynced before acknowledging: the winner's link may not be durable yet, and an
            // IDEMPOTENT answer is an acknowledgement.
            fsyncDirectory(dir);
            if (metrics != null) metrics.recordSpoolIdempotent();
            return AppendOutcome.IDEMPOTENT;
        }
        if (existing == null) {
            // The durable record is corrupt and the incoming one verifies: preserve the
            // corrupt bytes as quarantine evidence and republish the good record in its place.
            Path quarantine = quarantinePath(target);
            if (Files.exists(quarantine, LinkOption.NOFOLLOW_LINKS)) {
                logger.error("Spool record {} is corrupt and its quarantine slot is already"
                        + " occupied — dropping the incoming copy", target);
                if (metrics != null) metrics.recordSpoolQuarantine(REASON_SELF_CHECK_FAILED);
                return AppendOutcome.QUARANTINED;
            }
            try {
                Files.move(target, quarantine);
                Files.setPosixFilePermissions(quarantine,
                        PosixFilePermissions.fromString("rw-------"));
            } catch (NoSuchFileException vanished) {
                return null; // the scanner moved it first; retry the link
            }
            fsyncDirectory(dir);
            if (metrics != null) metrics.recordSpoolQuarantine(REASON_SELF_CHECK_FAILED);
            return null; // target slot is now free; the caller's loop republishes
        }
        // Same id, different verified content: the identity rule broke or someone tampered.
        Path quarantine = quarantinePath(target);
        if (Files.exists(quarantine, LinkOption.NOFOLLOW_LINKS)) {
            logger.error("Conflicting spool record for {} and the quarantine slot is already"
                    + " occupied — dropping this copy (two conflicting payloads are preserved)",
                    incoming.spoolRecordId());
        } else {
            try {
                createLinkAtomically(quarantine, tmp);
                fsyncDirectory(dir);
            } catch (FileAlreadyExistsException raced) {
                logger.error("Quarantine slot for {} was taken concurrently — dropping this"
                        + " copy", incoming.spoolRecordId());
            }
        }
        if (metrics != null) metrics.recordSpoolQuarantine(REASON_DIGEST_MISMATCH);
        return AppendOutcome.QUARANTINED;
    }

    /**
     * Reads and fully re-verifies a record; throws when it is not a verified spool record.
     * The read never follows a symlink and is bounded while streaming, not by a separate
     * (raceable) size probe.
     */
    LineageSpoolPayloadV1 readVerified(Path file) throws IOException {
        byte[] bytes = readBoundedNoFollow(file);
        LineageSpoolPayloadV1 payload =
                LineageSpoolCodec.decode(new String(bytes, StandardCharsets.UTF_8));
        if (!payload.selfVerifies()) {
            throw new IllegalArgumentException("content does not match its identity/digest");
        }
        String actualName = String.valueOf(file.getFileName());
        boolean nameMatches = actualName.equals("fact-" + payload.spoolRecordId() + ".json")
                || actualName.equals("fact-" + payload.spoolRecordId() + ".quarantine.json")
                || actualName.startsWith(".tmp-");
        if (!nameMatches) {
            throw new IllegalArgumentException("file name " + actualName
                    + " does not match spoolRecordId");
        }
        return payload;
    }

    private byte[] readBoundedNoFollow(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            long total = 0;
            int read;
            while ((read = channel.read(buffer)) != -1) {
                total += read;
                if (total > maxRecordBytes) {
                    throw new IllegalArgumentException("spool record exceeds " + maxRecordBytes
                            + " bytes");
                }
                buffer.flip();
                out.write(buffer.array(), 0, buffer.limit());
                buffer.clear();
            }
            return out.toByteArray();
        }
    }

    Path recordPath(LineageSpoolPayloadV1 payload) {
        String day = UTC_DAY.format(Instant.parse(payload.occurredAt()));
        return baseDir.resolve(repositorySegment(payload.repositoryId()))
                .resolve(day)
                .resolve("fact-" + payload.spoolRecordId() + ".json");
    }

    /**
     * §8's safe path encoding of a repository id: URL-safe base64 (no {@code /}, no
     * {@code ..}, no separators) plus a hex hash prefix that keeps ids differing only in
     * letter case apart on case-insensitive filesystems. Never the raw id — it is external
     * input and a path traversal vector.
     */
    static String repositorySegment(String repositoryId) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(repositoryId.getBytes(StandardCharsets.UTF_8));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(repositoryId.getBytes(StandardCharsets.UTF_8));
            return encoded + "-" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }

    static Path quarantinePath(Path target) {
        String name = String.valueOf(target.getFileName());
        return target.resolveSibling(name.replace(".json", ".quarantine.json"));
    }

    /**
     * Creates the directory chain with 0700 and fsyncs every level up to the spool root.
     *
     * <p>Every level below the (pre-provisioned, never-created-here) base is checked against
     * the §8 contract even when it already exists: a pre-existing symlink is filesystem
     * traversal that no lexical encoding prevents, and pre-existing permissions are
     * normalised to 0700 — creation attributes only apply to directories created now.
     */
    private void createDirectoriesDurably(Path dir) throws IOException {
        Path relative = baseDir.relativize(dir);
        Path cursor = baseDir;
        for (Path segment : relative) {
            cursor = cursor.resolve(segment);
            boolean createdNow = false;
            try {
                Files.createDirectory(cursor, DIR_0700);
                createdNow = true;
            } catch (FileAlreadyExistsException raced) {
                // A concurrent writer (or an earlier run) got there first — validate what
                // exists instead of trusting it.
            }
            if (!createdNow) {
                if (Files.isSymbolicLink(cursor)) {
                    throw new IOException("spool path level is a symlink: " + cursor);
                }
                if (!Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("spool path level is not a directory: " + cursor);
                }
                Files.setPosixFilePermissions(cursor,
                        PosixFilePermissions.fromString("rwx------"));
            }
        }
        // fsync from the leaf up to (and including) baseDir: which levels were just created
        // is irrelevant — syncing them all is cheap and unconditional. The base itself is
        // operator-provisioned, so its parent entry is not this class's to make durable.
        Path syncCursor = dir;
        while (syncCursor != null && syncCursor.startsWith(baseDir)) {
            fsyncDirectory(syncCursor);
            syncCursor = syncCursor.getParent();
        }
    }

    private void writeDurably(Path file, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(file,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), FILE_0600)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            forceFile(channel);
        }
    }

    /** File fsync — the other half of the durability contract, seamed for the same pinning. */
    protected void forceFile(FileChannel channel) throws IOException {
        channel.force(true);
    }

    /** The atomic create-if-absent step, seamed so tests can sustain link races. */
    protected void createLinkAtomically(Path target, Path tmp) throws IOException {
        Files.createLink(target, tmp);
    }

    /**
     * Directory fsync — a hard requirement of the protocol, so failures propagate. Overridable
     * seam so tests can pin every call site (a removed fsync is a lost durability guarantee,
     * not an acceptable mutation).
     */
    protected void fsyncDirectory(Path dir) throws IOException {
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static void quietDelete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }
}
