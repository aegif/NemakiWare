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
    }

    /** One scan's tally; {@code quarantinedNow} are records this scan moved aside. */
    public record ScanSummary(int verified, int quarantinedNow, int alreadyQuarantined,
                              int failed) {
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
        int verified = 0;
        int quarantinedNow = 0;
        int alreadyQuarantined = 0;
        int failed = 0;
        java.util.concurrent.atomic.AtomicInteger enumerationFailures =
                new java.util.concurrent.atomic.AtomicInteger();
        for (Path file : enumerateFactFiles(baseDir, enumerationFailures)) {
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
                    materializer.materialize(payload);
                } catch (RuntimeException e) {
                    // Materialisation failures are the materialiser's to record and retry —
                    // the fact stays in the spool untouched, which is the whole point of it.
                    // It still counts as failed work in this scan's summary.
                    // Class only: a materializer exception can echo endpoint or legacy
                    // values, which must not reach ordinary logs.
                    logger.warn("Materializer failed for {} (fact retained): {}",
                            payload.spoolRecordId(), e.getClass().getSimpleName());
                    failed++;
                }
            }
        }
        return new ScanSummary(verified, quarantinedNow, alreadyQuarantined,
                failed + enumerationFailures.get());
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
