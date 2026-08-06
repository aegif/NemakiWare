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

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The disabled-by-default admin entry to the §8-a fenced sequencer (D-rest-2, v2.3.18 ⑤).
 *
 * <p>Manual and node-local only in this slice: no background scheduler exists, and the run
 * refuses unless {@link LineageDrestReadiness} is fully ready — which includes the structural
 * sink-verification gate (A2: a finalized v2 row is an ordered barrier; sequencing must not
 * create barriers no configured sink can ever drain) and the view-signature check (B1: an old
 * binary's design document means old readers may still see v2 rows).
 */
@Component
public class LineageSequencerAdminService {

    @Autowired
    private LineageJournalStore journalStore;

    @Autowired
    private LineageDrestReadiness readiness;

    @Autowired
    private LineageConfig lineageConfig;

    @Autowired(required = false)
    private LineageMetrics lineageMetrics;

    @Autowired(required = false)
    private LineageCapabilityProvider capabilityProvider;

    /** A run refusal (readiness violations) as data; {@code summary} set on success. */
    public record SequencerRunOutcome(boolean ran,
                                      java.util.List<String> violations,
                                      LineageFencedSequencer.RunSummary summary) {
    }

    public SequencerRunOutcome run(String repositoryId) {
        return run(repositoryId, "admin");
    }

    /**
     * One fenced sequencer pass, labelled by whoever drove it.
     *
     * <p>The label becomes the lease owner, so an operator reading
     * {@code /sequencer/{repo}} can tell a scheduled pass from one they triggered by hand.
     *
     * @param driverLabel short identifier for the caller, e.g. {@code admin} or {@code loop}
     */
    public SequencerRunOutcome run(String repositoryId, String driverLabel) {
        LineageDrestReadiness.Readiness verdict = readiness.evaluate();
        if (!verdict.ready()) {
            return new SequencerRunOutcome(false, verdict.violations(), null);
        }
        LineageSequencingStore store = (LineageSequencingStore) journalStore;
        String nodeId = driverLabel + "-" + java.util.UUID.randomUUID();
        LineageFencedSequencer sequencer = new LineageFencedSequencer(store, lineageMetrics,
                nodeId,
                java.time.Duration.ofSeconds(lineageConfig.getSequencerLeaseSeconds()),
                lineageConfig.getSequencerBatchSize(),
                lineageConfig.getSequencerBacklogCap());
        return new SequencerRunOutcome(true, java.util.List.of(),
                sequencer.runOnce(repositoryId));
    }

    /** Read-only status — allowed while disabled (diagnostics). */
    public Map<String, Object> status(String repositoryId) {
        Map<String, Object> out = new LinkedHashMap<>();
        LineageDrestReadiness.Readiness verdict = readiness.evaluate();
        out.put("enabled", verdict.ready());
        out.put("violations", verdict.violations());
        if (capabilityProvider != null) {
            Map<String, Object> capabilities = new LinkedHashMap<>();
            capabilities.put("wired", capabilityProvider.wiredCapabilities().stream()
                    .sorted().toList());
            capabilities.put("active", verdict.ready());
            out.put("capabilities", capabilities);
        }
        if (!(journalStore instanceof LineageSequencingStore store)) {
            out.put("leasePresent", false);
            out.put("hint", "journal store has no sequencing surface");
            return out;
        }
        try {
            java.util.Optional<LineageSequencingStore.LeaseView> lease =
                    store.readSequencerLease(repositoryId);
            if (lease.isEmpty()) {
                out.put("leasePresent", false);
                out.put("hint", "run the LineageSequencerBootstrap patch (lease is"
                        + " bootstrap-created only)");
            } else {
                out.put("leasePresent", true);
                LineageSequencingStore.LeaseView view = lease.get();
                Map<String, Object> leaseOut = new LinkedHashMap<>();
                leaseOut.put("generation", view.generation());
                leaseOut.put("owner", view.owner());
                leaseOut.put("expiresAt", view.expiresAt());
                out.put("lease", leaseOut);
            }
        } catch (RuntimeException e) {
            // Infrastructure failure is 503 territory — distinguished from lease-missing.
            throw new IllegalStateException("sequencer status read failed: " + e.getMessage(),
                    e);
        }
        try {
            // Backlog probe at cap+1: a measurement bounded by its own batch limit cannot
            // see past itself (D-rest-1's rule, reused).
            // Diagnostics must survive a misconfigured cap (readiness reports it; this GET
            // still answers): clamp to the readiness-valid range so cap+1 cannot overflow.
            int cap = Math.min(Math.max(lineageConfig.getSequencerBacklogCap(), 1), 1_000_000);
            int probe = store.findUnsequencedV2(repositoryId, cap + 1).size();
            out.put("unsequencedBacklog", Math.min(probe, cap));
            out.put("unsequencedBacklogAtCap", probe > cap);
        } catch (RuntimeException e) {
            out.put("unsequencedBacklog", null);
            out.put("unsequencedBacklogError", e.getMessage());
        }
        if (journalStore instanceof CouchLineageJournalStore couch) {
            for (String target : lineageConfig.getTargets()) {
                try {
                    out.put("verifying:" + target, couch.verifyingStats(target));
                } catch (RuntimeException e) {
                    out.put("verifying:" + target, Map.of("error", e.getMessage()));
                }
            }
            try {
                out.put("unackedReplayRequests", couch.countUnackedReplayRequests());
            } catch (RuntimeException e) {
                out.put("unackedReplayRequestsError", e.getMessage());
            }
        }
        return out;
    }
}
