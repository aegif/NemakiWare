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

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The single aggregate D-rest readiness gate (v2.3.18 ⑤, refined B2/C1 in v2.3.19).
 *
 * <p>Every D-rest driver — the admin sequencer entry, the v2 projection branch, the v2 reaper,
 * the v2 half of purge — consults THIS evaluation and nothing else. One gate, one answer, so a
 * driver can never run under a condition another driver already refused.
 *
 * <p>Readiness requires ALL of:
 * <ol>
 *   <li>the operator switch {@code lineage.drest.enabled} (default false);</li>
 *   <li>valid claim/verify configuration — positive values and
 *       {@code claim-lease > verify.timeout + margin}, margin = max(2×interval, 10s): a lease
 *       that can expire inside one verify encounter would hand the reaper a live claimant;</li>
 *   <li>the deployed design document carries THIS binary's view definitions (an old binary
 *       redeploying dual-schema views during a rolling window must refuse activation);</li>
 *   <li>every configured target's sink structurally supports verification — a finalized v2 row
 *       is an ordered barrier, and a barrier no sink can ever drain would strand all later
 *       traffic. Journal-only deployments (no targets) pass this clause: there is no ordered
 *       consumer to strand.</li>
 * </ol>
 *
 * <p>Targets are dynamically reread, so callers re-evaluate per poll / per request; a target
 * added later without verification support flips readiness to violated at the only place
 * admission happens. When readiness is false the entire v2 branch is dormant — no claim, no
 * renewal, no verify, no transition, no reap (C2).
 */
@Component
public class LineageDrestReadiness {

    private static final Logger logger = LoggerFactory.getLogger(LineageDrestReadiness.class);

    /** Last verdict's violations — WARN fires on CHANGE, not on every poll (F8). */
    private final java.util.concurrent.atomic.AtomicReference<List<String>> lastViolations =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** The verdict: ready, or the exact violations blocking activation. */
    public record Readiness(boolean ready, List<String> violations) {
        public Readiness {
            violations = List.copyOf(violations);
            if (ready && !violations.isEmpty()) {
                throw new IllegalArgumentException("ready with violations is a contradiction");
            }
        }
    }

    @Autowired
    private LineageConfig lineageConfig;

    @Autowired
    private LineageJournalStore journalStore;

    @Autowired(required = false)
    private List<LineageTargetSink> targetSinks;

    @Autowired(required = false)
    private jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap repositoryInfoMap;

    public Readiness evaluate() {
        List<String> violations = new ArrayList<>();
        if (!lineageConfig.isDrestEnabled()) {
            violations.add("lineage.drest.enabled is false");
        }
        violations.addAll(configViolations());
        if (journalStore instanceof CouchLineageJournalStore couch) {
            violations.addAll(couch.viewSignatureViolations());
        } else {
            violations.add("journal store is not the Couch store — no D-rest surface");
        }
        violations.addAll(sinkCapabilityViolations());
        violations.addAll(spoolViolations());
        List<String> previous = lastViolations.getAndSet(List.copyOf(violations));
        if (!violations.equals(previous)) {
            if (violations.isEmpty()) {
                logger.info("D-rest readiness gate is green");
            } else if (lineageConfig.isDrestEnabled()) {
                // The operator asked for activation and is not getting it — say why, loudly,
                // once per change.
                logger.warn("D-rest readiness gate is RED despite lineage.drest.enabled:"
                        + " {}", violations);
            }
        }
        return new Readiness(violations.isEmpty(), violations);
    }

    private List<String> configViolations() {
        List<String> violations = new ArrayList<>();
        int lease = lineageConfig.getProjectionClaimLeaseSeconds();
        int timeout = lineageConfig.getVerifyTimeoutSeconds();
        int interval = lineageConfig.getVerifyIntervalSeconds();
        int maxAge = lineageConfig.getVerifyMaxAgeMinutes();
        if (lease < 30 || lease > 3600) {
            violations.add("lineage.projection.claim-lease-seconds must be in [30, 3600],"
                    + " got " + lease);
        }
        if (timeout < 1 || timeout > 300) {
            violations.add("lineage.verify.timeout-seconds must be in [1, 300], got "
                    + timeout);
        }
        if (interval < 1 || interval > 60) {
            violations.add("lineage.verify.interval-seconds must be in [1, 60], got "
                    + interval);
        }
        if (maxAge < 1 || maxAge > 1440) {
            violations.add("lineage.verify.max-age-minutes must be in [1, 1440], got "
                    + maxAge);
        }
        int seqLease = lineageConfig.getSequencerLeaseSeconds();
        int seqBatch = lineageConfig.getSequencerBatchSize();
        int seqCap = lineageConfig.getSequencerBacklogCap();
        if (seqLease < 10 || seqLease > 3600) {
            violations.add("lineage.sequencer.lease-seconds must be in [10, 3600], got "
                    + seqLease);
        }
        if (seqBatch < 1 || seqBatch > 10_000) {
            violations.add("lineage.sequencer.batch-size must be in [1, 10000], got "
                    + seqBatch);
        }
        if (seqCap < 1 || seqCap > 1_000_000) {
            // The upper bound also keeps the cap+1 backlog probe overflow-safe.
            violations.add("lineage.sequencer.backlog-cap must be in [1, 1000000], got "
                    + seqCap);
        }
        if (lease >= 30 && timeout >= 1 && interval >= 1) {
            long marginSeconds = Math.max(2L * interval, 10L);
            if (lease <= timeout + marginSeconds) {
                violations.add("lineage.projection.claim-lease-seconds (" + lease + "s) must"
                        + " exceed verify timeout + margin (" + timeout + "s + " + marginSeconds
                        + "s) — a lease expiring inside one verify encounter hands the reaper"
                        + " a live claimant");
            }
        }
        return violations;
    }

    /**
     * B6 (v2.3.21, mode-aware): journaled lineage mode requires a working node-local spool —
     * unset or unwritable is NOT_READY. Direct/disabled modes pass WITHOUT constructing a
     * Path or probing (the spool is not part of those modes). Evaluated only when the
     * operator switch is already on (the disabled case short-circuits at the switch clause
     * anyway; this method still guards itself for exactness).
     */
    private List<String> spoolViolations() {
        if (!lineageConfig.isDrestEnabled()) {
            return List.of();
        }
        boolean journaled = lineageConfig.getMode() == LineageMode.JOURNALED;
        if (!journaled && repositoryInfoMap != null) {
            for (String repositoryId : repositoryInfoMap.keys()) {
                if (lineageConfig.getModeForRepository(repositoryId)
                        == LineageMode.JOURNALED) {
                    journaled = true;
                    break;
                }
            }
        }
        if (!journaled) {
            return List.of();
        }
        String dir = lineageConfig.getSpoolDir();
        if (dir.isBlank()) {
            return List.of("journaled mode requires lineage.spool.dir — the fact spool is"
                    + " part of the journaled contract");
        }
        List<String> budgetViolations = new ArrayList<>();
        int maxFiles = lineageConfig.getSpoolScanMaxFiles();
        int maxMat = lineageConfig.getSpoolScanMaxMaterializations();
        long maxMillis = lineageConfig.getSpoolScanMaxMillis();
        if (maxFiles < 1 || maxFiles > 100_000) {
            budgetViolations.add("lineage.spool.scan.max-files must be in [1, 100000], got "
                    + maxFiles);
        }
        if (maxMat < 1 || maxMat > 10_000) {
            budgetViolations.add("lineage.spool.scan.max-materializations must be in"
                    + " [1, 10000], got " + maxMat);
        }
        if (maxMillis < 1 || maxMillis > 600_000) {
            budgetViolations.add("lineage.spool.scan.max-millis must be in [1, 600000], got "
                    + maxMillis);
        }
        int maxPerEvent = lineageConfig.getEndpointMaxPerEvent();
        long maxPayload = lineageConfig.getEventMaxPayloadBytes();
        long maxDocument = lineageConfig.getEventMaxDocumentBytes();
        if (maxPerEvent < 2 || maxPerEvent > 10_000) {
            budgetViolations.add("lineage.endpoint.max-per-event must be in [2, 10000] (2"
                    + " admits an anchor plus one payload endpoint), got " + maxPerEvent);
        }
        if (maxPayload < 64L * 1024 || maxPayload > 16L * 1024 * 1024) {
            budgetViolations.add("lineage.event.max-payload-bytes must be in [65536,"
                    + " 16777216], got " + maxPayload);
        }
        if (maxDocument < 1024L * 1024 || maxDocument > 8_000_000L) {
            // 8,000,000 is CouchDB 3.x's default max_document_size; a ceiling above it would
            // promise more than the backend accepts.
            budgetViolations.add("lineage.event.max-document-bytes must be in [1048576,"
                    + " 8000000] (CouchDB 3.x's default max_document_size), got "
                    + maxDocument);
        }
        if (!budgetViolations.isEmpty()) {
            return budgetViolations;
        }
        try {
            LineageFactSpool probe = new LineageFactSpool(java.nio.file.Path.of(dir), null);
            if (!probe.probeReadiness()) {
                return List.of("lineage.spool.dir '" + dir + "' failed the write/link/fsync"
                        + " probe — the spool volume cannot honor the durability contract");
            }
        } catch (RuntimeException e) {
            return List.of("lineage.spool.dir '" + dir + "' is unusable: " + e.getMessage());
        }
        return List.of();
    }

    private List<String> sinkCapabilityViolations() {
        List<String> violations = new ArrayList<>();
        for (String target : lineageConfig.getTargets()) {
            LineageTargetSink sink = findSink(target);
            if (sink == null) {
                violations.add("configured target '" + target + "' has no sink bean");
            } else if (!sink.supportsVerification()) {
                violations.add("configured target '" + target + "' cannot verify (structural)"
                        + " — sequencing v2 rows would create barriers no sink can drain");
            }
        }
        return violations;
    }

    private LineageTargetSink findSink(String targetName) {
        if (targetSinks == null) {
            return null;
        }
        for (LineageTargetSink sink : targetSinks) {
            if (targetName.equals(sink.targetName())) {
                return sink;
            }
        }
        return null;
    }
}
