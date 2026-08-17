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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enumerates and verifies spooled facts — D-spool's read side.
 *
 * <p>The scanner's one hard rule is §6-a test 13c: a record that fails self-verification is
 * rejected here, before any decision document could ever be created from it. Verification
 * recomputes both hashes from content; nothing stored in the file is trusted.
 *
 * <p>What happens to a verified fact is someone else's job: the {@link SpoolMaterializer}
 * seam is where D-rest's convergent materialisation protocol (decision documents, version
 * binding, journal create-if-absent, ACK) plugs in. Until D-rest ships, the only production
 * implementation is a no-op reporter — the scanner verifies and counts, and materialisation
 * does not exist. There is deliberately no scheduler wiring in this slice.
 *
 * <p>File hygiene: only regular files (symlinks are never followed) whose names match
 * {@code fact-{64 hex}.json} are considered; quarantine files are reported, not decoded;
 * reads are size-bounded.
 */
public class LineageSpoolScanner {

    /** Where D-rest's convergent materialiser plugs in. Implementations must be idempotent. */
    public interface SpoolMaterializer {
        void materialize(LineageSpoolPayloadV1 verifiedFact);

        /**
         * D-rest-4: the path-aware entry the scanner calls — the materializer needs the fact
         * file to read/publish/repair its sibling ACK. Default delegates for D-spool-era
         * bindings and reports no outcome detail.
         */
        default LineageSpoolMaterializer.MaterializeResult materialize(
                LineageSpoolPayloadV1 verifiedFact, java.nio.file.Path factFile) {
            materialize(verifiedFact);
            return new LineageSpoolMaterializer.MaterializeResult(
                    LineageSpoolMaterializer.Outcome.UNRESOLVED, false);
        }
    }

    /** One scan's tally; {@code quarantinedNow} are records this scan moved aside. */
    public record ScanSummary(int verified, int quarantinedNow, int alreadyQuarantined,
                              int failed, int acked, int alreadyAcked, int unresolved,
                              int partial, int ackBroken, boolean budgetExhausted) {

        /** D-spool-era shape, kept for existing call sites and tests. */
        public ScanSummary(int verified, int quarantinedNow, int alreadyQuarantined,
                           int failed) {
            this(verified, quarantinedNow, alreadyQuarantined, failed, 0, 0, 0, 0, 0, false);
        }
    }

    /**
     * A finite, fair scan budget (v2.3.21 A6/B2): every file visited counts — including
     * ACKed facts, whose suppression requires FULL verification on every encounter (a forged
     * ACK must never suppress work, so existence alone never skips). Fairness across polls
     * comes from the JVM-lifetime rotating cursor: successive scans continue after the last
     * visited file and wrap around; a restart restarts the rotation.
     */
    public record ScanBudget(int maxFilesVisited, int maxMaterializations, long maxMillis) {
        public ScanBudget {
            if (maxFilesVisited < 1 || maxMaterializations < 1 || maxMillis < 1) {
                throw new IllegalArgumentException("budget values must be positive");
            }
        }

        public static ScanBudget unbounded() {
            return new ScanBudget(Integer.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE);
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(LineageSpoolScanner.class);
    private static final Pattern FACT_FILE = Pattern.compile("fact-[0-9a-f]{64}\\.json");
    private static final Pattern QUARANTINE_FILE =
            Pattern.compile("fact-[0-9a-f]{64}\\.quarantine\\.json");

    private final LineageFactSpool spool;
    private final LineageMetrics metrics;

    public LineageSpoolScanner(LineageFactSpool spool, LineageMetrics metrics) {
        if (spool == null) {
            throw new IllegalArgumentException("spool must not be null");
        }
        this.spool = spool;
        this.metrics = metrics;
    }

    /**
     * Walks {@code {baseDir}/{repoSegment}/{yyyyMMdd}} under the given base, verifying every
     * fact file and handing the verified ones to the materialiser. Never throws; a record that
     * cannot be verified is moved to its quarantine slot (unless one exists) and counted.
     */
    public ScanSummary scan(Path baseDir, SpoolMaterializer materializer) {
        return scan(baseDir, materializer, ScanBudget.unbounded());
    }

    /** JVM-lifetime rotating cursor: the last visited file name (sorted order), or null. */
    private volatile String rotationCursor;

    public ScanSummary scan(Path baseDir, SpoolMaterializer materializer, ScanBudget budget) {
        int verified = 0;
        int quarantinedNow = 0;
        int alreadyQuarantined = 0;
        int failed = 0;
        int acked = 0;
        int alreadyAcked = 0;
        int unresolved = 0;
        int partial = 0;
        int ackBroken = 0;
        int visited = 0;
        int materializations = 0;
        boolean exhausted = false;
        // Saturated: an unbounded budget must not overflow into an already-passed deadline.
        long deadline = budget.maxMillis() >= Long.MAX_VALUE / 2_000_000L
                ? Long.MAX_VALUE
                : System.nanoTime() + budget.maxMillis() * 1_000_000L;
        java.util.concurrent.atomic.AtomicInteger enumerationFailures =
                new java.util.concurrent.atomic.AtomicInteger();
        // BOUNDED enumeration (round-1 finding 2): the traversal itself honors the file cap
        // and the deadline — a large spool cannot stall the poll in listing/sorting before
        // any budget check. Rotation order is built INTO the walk (after-cursor, then wrap).
        boolean[] walkStopped = new boolean[1];
        List<Path> ordered = collectBounded(baseDir, rotationCursor,
                budget.maxFilesVisited(), deadline, enumerationFailures, walkStopped);
        if (ordered.size() >= budget.maxFilesVisited() || walkStopped[0]) {
            exhausted = true; // the walk stopped on cap or deadline; more likely remains
        }
        for (Path file : ordered) {
            if (materializations >= budget.maxMaterializations()
                    || System.nanoTime() > deadline) {
                exhausted = true;
                break;
            }
            visited++;
            rotationCursor = file.toString();
            String name = String.valueOf(file.getFileName());
            if (QUARANTINE_FILE.matcher(name).matches()) {
                alreadyQuarantined++;
                continue;
            }
            LineageSpoolPayloadV1 payload;
            try {
                payload = spool.readVerified(file);
            } catch (IOException | RuntimeException e) {
                // Reason class only — parser messages echo the (attacker-influenceable) file
                // bytes, and spool payloads may carry values that must not reach logs.
                logger.warn("Spool record {} failed verification ({})", file,
                        e.getClass().getSimpleName());
                if (quarantine(file)) {
                    quarantinedNow++;
                    if (metrics != null) metrics.recordSpoolQuarantine(
                            LineageFactSpool.REASON_SELF_CHECK_FAILED);
                } else {
                    failed++;
                }
                continue;
            }
            verified++;
            if (materializer != null) {
                try {
                    LineageSpoolMaterializer.MaterializeResult result =
                            materializer.materialize(payload, file);
                    if (result.brokenAck()) {
                        ackBroken++;
                    }
                    switch (result.outcome()) {
                        case ACKED -> {
                            acked++;
                            materializations++;
                        }
                        case ALREADY_ACKED -> alreadyAcked++;
                        case UNRESOLVED -> unresolved++;
                        case PARTIAL -> {
                            partial++;
                            materializations++;
                        }
                        case FAILED -> {
                            failed++;
                            materializations++;
                        }
                    }
                } catch (RuntimeException e) {
                    // Materialisation failures are the materialiser's to record and retry —
                    // the fact stays in the spool untouched, which is the whole point of it.
                    // It still counts as failed work in this scan's summary.
                    // Class only: a materializer exception can echo endpoint or legacy
                    // values, which must not reach ordinary logs.
                    logger.warn("Materializer failed for {} (fact retained): {}",
                            payload.spoolRecordId(), e.getClass().getSimpleName());
                    failed++;
                    materializations++; // a throwing binding must not bypass the budget
                }
            }
        }
        if (unresolved > 0 && acked == 0 && partial == 0 && failed == 0) {
            // One WARN per scan: everything skipped for lack of a resolved write version —
            // expected pre-4a, but never silent.
            logger.warn("Spool scan: {} facts unresolved (no write version) — the"
                    + " materializer is inert until 4a's barrier resolver", unresolved);
        }
        return new ScanSummary(verified, quarantinedNow, alreadyQuarantined,
                failed + enumerationFailures.get(), acked, alreadyAcked, unresolved, partial,
                ackBroken, exhausted);
    }

    /**
     * Bounded rotation-ordered collection: sorted directory traversal (repo → day → file,
     * each level sorted), phase 1 strictly AFTER the cursor, phase 2 the wrap (paths at or
     * before it) — capped and deadline-bounded DURING the walk.
     */
    private List<Path> collectBounded(Path baseDir, String cursor, int cap, long deadline,
            java.util.concurrent.atomic.AtomicInteger enumerationFailures,
            boolean[] stopped) {
        List<Path> out = new java.util.ArrayList<>(Math.min(cap, 4096));
        walkPhase(baseDir, cursor, false, out, cap, deadline, enumerationFailures, stopped);
        if (cursor != null && out.size() < cap && !stopped[0]) {
            walkPhase(baseDir, cursor, true, out, cap, deadline, enumerationFailures,
                    stopped);
        }
        return out;
    }

    private void walkPhase(Path baseDir, String cursor, boolean wrapPhase, List<Path> out,
            int cap, long deadline,
            java.util.concurrent.atomic.AtomicInteger enumerationFailures,
            boolean[] stopped) {
        if (!Files.isDirectory(baseDir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            for (Path repo : sortedChildren(baseDir, deadline, stopped)) {
                if (out.size() >= cap || System.nanoTime() > deadline) {
                    stopped[0] = true;
                    return;
                }
                if (!Files.isDirectory(repo, LinkOption.NOFOLLOW_LINKS)
                        || String.valueOf(repo.getFileName()).startsWith(".")) {
                    continue;
                }
                List<Path> days;
                try {
                    days = sortedChildren(repo, deadline, stopped);
                } catch (IOException e) {
                    // One unreadable repository must not starve every later one.
                    logger.warn("Spool enumeration failed under {}: {}", repo, e.toString());
                    enumerationFailures.incrementAndGet();
                    continue;
                }
                for (Path day : days) {
                    if (out.size() >= cap || System.nanoTime() > deadline) {
                        stopped[0] = true;
                        return;
                    }
                    if (!Files.isDirectory(day, LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    // Per-day collection goes through the collectDay seam (tests inject
                    // failures there). One day's NAME listing is the irreducible sorted
                    // unit; the deadline is re-checked immediately after it so an
                    // over-large day stops the walk loudly instead of running on.
                    List<Path> dayFiles = new java.util.ArrayList<>();
                    try {
                        collectDay(day, dayFiles);
                    } catch (IOException e) {
                        logger.warn("Spool enumeration failed under {}: {}", day,
                                e.toString());
                        enumerationFailures.incrementAndGet();
                        continue;
                    }
                    if (System.nanoTime() > deadline) {
                        stopped[0] = true;
                        return;
                    }
                    dayFiles.sort(java.util.Comparator.comparing(Path::toString));
                    for (Path file : dayFiles) {
                        if (out.size() >= cap || System.nanoTime() > deadline) {
                            stopped[0] = true;
                            return;
                        }
                        String key = file.toString();
                        boolean afterCursor = cursor == null || key.compareTo(cursor) > 0;
                        if (wrapPhase == afterCursor) {
                            continue; // phase 1 takes > cursor; the wrap takes <= cursor
                        }
                        out.add(file);
                    }
                }
            }
        } catch (IOException e) {
            logger.warn("Spool enumeration failed under {}: {}", baseDir, e.toString());
            enumerationFailures.incrementAndGet();
        }
    }

    /** Deadline-aware single-level listing: a huge directory stops the walk loudly. */
    private static List<Path> sortedChildren(Path dir, long deadline, boolean[] stopped)
            throws IOException {
        List<Path> children = new java.util.ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                if (System.nanoTime() > deadline) {
                    stopped[0] = true;
                    break;
                }
                children.add(child);
            }
        }
        children.sort(java.util.Comparator.comparing(Path::toString));
        return children;
    }

    private List<Path> enumerateFactFiles(Path baseDir,
            java.util.concurrent.atomic.AtomicInteger enumerationFailures) {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(baseDir, LinkOption.NOFOLLOW_LINKS)) {
            return files;
        }
        try (DirectoryStream<Path> repos = Files.newDirectoryStream(baseDir)) {
            for (Path repo : repos) {
                if (!Files.isDirectory(repo, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (String.valueOf(repo.getFileName()).startsWith(".")) {
                    continue; // the readiness probe directory, and nothing else is ours
                }
                try (DirectoryStream<Path> days = Files.newDirectoryStream(repo)) {
                    for (Path day : days) {
                        if (!Files.isDirectory(day, LinkOption.NOFOLLOW_LINKS)) {
                            continue;
                        }
                        try {
                            collectDay(day, files);
                        } catch (IOException e) {
                            logger.warn("Spool enumeration failed under {}: {}", day,
                                    e.toString());
                            enumerationFailures.incrementAndGet();
                        }
                    }
                } catch (IOException e) {
                    logger.warn("Spool enumeration failed under {}: {}", repo, e.toString());
                    enumerationFailures.incrementAndGet();
                }
            }
        } catch (IOException e) {
            logger.warn("Spool enumeration failed under {}: {}", baseDir, e.toString());
            enumerationFailures.incrementAndGet();
        }
        return files;
    }

    /** Seamed so tests can inject enumeration failures — see ScanSummary.failed. */
    protected void collectDay(Path day, List<Path> files) throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(day)) {
            for (Path entry : entries) {
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String name = String.valueOf(entry.getFileName());
                if (FACT_FILE.matcher(name).matches()
                        || QUARANTINE_FILE.matcher(name).matches()) {
                    files.add(entry);
                }
            }
        }
    }

    private boolean quarantine(Path file) {
        Path quarantine = LineageFactSpool.quarantinePath(file);
        try {
            if (Files.exists(quarantine, LinkOption.NOFOLLOW_LINKS)) {
                logger.error("Quarantine slot for {} is already occupied — leaving the record"
                        + " in place", file);
                return false;
            }
            Files.move(file, quarantine);
            // The moved bytes may be an externally injected file with any mode — normalise to
            // the contract's 0600 before making the entry durable.
            Files.setPosixFilePermissions(quarantine,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            // Same durability rule as the store's own quarantine writes: the move is not real
            // until the directory entry is.
            spool.fsyncDirectory(file.getParent());
            return true;
        } catch (IOException e) {
            logger.error("Could not quarantine {}: {}", file, e.toString());
            return false;
        }
    }
}
