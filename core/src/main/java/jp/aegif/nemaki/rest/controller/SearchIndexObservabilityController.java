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
package jp.aegif.nemaki.rest.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.cmis.service.impl.PropagationProgress;
import jp.aegif.nemaki.cmis.service.impl.RefreshCounters;
import jp.aegif.nemaki.dao.TruncatedGroupResolution;
import jp.aegif.nemaki.util.constant.CallContextKey;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * "Is this repository busy propagating a permission change, and when will it settle?"
 *
 * <h2>Why these numbers and not a health check</h2>
 *
 * <p>Changing the ACL of a large folder rewrites the search-index {@code readers} of every
 * inheriting descendant, one document at a time, on a single background thread. That is an
 * accepted cost. What was missing is any way to see it: no endpoint reported that a traversal was
 * running, how big it was, how deep the queue behind it had grown, or whether the executor had
 * started running work inline on request threads because the queue was full. An administrator
 * watching an apparently idle server had to read Solr by hand to find out.
 *
 * <p>Three of these counters are the ones that turn a symptom into a diagnosis:
 *
 * <ul>
 * <li>{@code inlineRunsOnRequestThreads} rising means the refresh queue is saturating and the
 *     executor is handing tasks back to request threads. Compare it with
 *     {@code deferredToReconciliation}: when the two rise together, every overflow was moved to
 *     the durable queue and no request walked a subtree. Caller-runs rising while deferrals stay
 *     flat is the bad shape — requests really are doing propagation work, which is the mechanism
 *     by which one big permission change slows the whole application down.</li>
 * <li>{@code pendingGateBlocks} rising while nothing converges means an ACL-epoch marker is stuck.
 *     Those tasks are retained forever without consuming a retry attempt (deliberately), so
 *     without this counter a stuck marker is invisible.</li>
 * <li>{@code groupResolutionTruncations} rising means some account sits below a group chain deeper
 *     than the traversal limit and is silently missing permissions granted above that depth.</li>
 * <li>{@code queriesDegradedByAclScanLimit} rising while nothing is propagating means queries are
 *     being answered with partial result sets for a reason that no longer applies.</li>
 * </ul>
 *
 * <p>Read-only and admin-gated. Under {@code /v1/admin/*}, so the shared CSRF interceptor applies
 * to any future mutating route added here.
 */
@RestController
@RequestMapping("/v1/admin/search-index")
public class SearchIndexObservabilityController {

    @Autowired
    private HttpServletRequest httpRequest;

    /**
     * Live counters for the propagation machinery.
     *
     * <p>Executor queue depths are deliberately absent: the executors are private to their owning
     * services, and exposing them would mean widening those APIs purely for a metric. The
     * information that actually matters — whether the queue overflowed into request threads — is
     * reported directly by {@code inlineRunsOnRequestThreads}, which is observed at the point of
     * execution and does not depend on sampling a depth at the right moment.
     */
    @GetMapping("/metrics")
    public ResponseEntity<?> metrics() {
        if (!isAdmin()) {
            return forbidden();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("inlineRunsOnRequestThreads", PropagationProgress.callerRunsTotal());
        body.put("deferredToReconciliation", PropagationProgress.deferredFromRequestThreadTotal());
        body.put("pendingGateBlocks", RefreshCounters.pendingBlocksTotal());
        body.put("groupResolutionTruncations", TruncatedGroupResolution.truncationCount());
        // Queries that were answered with a confirmed prefix instead of a 400, because a
        // permission change was still propagating. A count that keeps climbing after propagation
        // should have settled means something is not converging.
        body.put("queriesDegradedByAclScanLimit",
                jp.aegif.nemaki.cmis.aspect.query.solr.AclPropagationStaleness.degradedQueryCount());
        body.put("activePropagations", PropagationProgress.active().size());
        // Lock-ordering findings. `lockOrderUpgrades` above zero means a thread has already parked
        // forever (ReentrantReadWriteLock cannot upgrade a read hold to a write hold), and every
        // later request for that stripe queues behind it — the repository stops. `inversions` is a
        // warning rather than a failure: two orders were observed that CAN deadlock, and reporting
        // the possibility is the point, because the alternative is learning it from an outage.
        body.put("lockOrderUpgrades", jp.aegif.nemaki.util.lock.LockOrderMonitor.upgradeCount());
        body.put("lockOrderInversions", jp.aegif.nemaki.util.lock.LockOrderMonitor.inversionCount());
        body.put("note", "Counters are per-JVM and reset on restart. In a multi-replica"
                + " deployment, read them from every replica.");
        return ResponseEntity.ok(body);
    }

    /**
     * The traversals running right now, with an estimate where one can honestly be made.
     *
     * <p>{@code etaReliable} is false when the estimate cannot be trusted — too few nodes
     * processed to measure a rate, an unknown total, or a task that overtook the queue by running
     * inline. It is reported rather than hidden because the overload case, where the estimate
     * breaks, is exactly the case an operator is most likely to be looking at.
     */
    /**
     * Lock acquisition orders observed to be unsafe, with the two objects named.
     *
     * <p>A thread dump taken after a repository has stopped shows which LINES are parked but not
     * which OBJECTS — and because the locks are striped, the pair need not be related, so the
     * identities cannot be inferred from the code. This reports them as they are observed, before
     * anything has to hang.
     */
    @GetMapping("/lock-order")
    public ResponseEntity<?> lockOrder() {
        if (!isAdmin()) {
            return forbidden();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("upgrades", jp.aegif.nemaki.util.lock.LockOrderMonitor.upgradeCount());
        body.put("inversions", jp.aegif.nemaki.util.lock.LockOrderMonitor.inversionCount());
        // Acquisitions only PARTIALLY edge-checked because more than the comparison window was
        // already held. Zero inversions is only as strong as this number is small: the detector
        // compares a new acquisition against the OLDEST holds (the outer scopes, which is the
        // shape that took the repository down), so what truncation drops is inner-set pairs —
        // but a reader of "0" deserves to know how much was not looked at.
        body.put("depthLimitedAcquisitions",
                jp.aegif.nemaki.util.lock.LockOrderMonitor.depthLimitedCount());
        // unlock() calls that had nothing to release. Nonzero after acquisition timeouts is
        // normal (the caller's finally still runs); growth WITHOUT timeouts means some code path
        // unlocks through a different wrapper than the one that locked — which silently LEAKS
        // the hold, and this counter is that bug's only symptom.
        body.put("unlocksWithoutHold",
                jp.aegif.nemaki.util.lock.LockOrderMonitor.unlockWithoutHoldCount());
        body.put("findingsCapReached",
                jp.aegif.nemaki.util.lock.LockOrderMonitor.findingsCapReached());
        List<Map<String, Object>> findings = jp.aegif.nemaki.util.lock.LockOrderMonitor.findings();
        body.put("findings", findings);
        body.put("note", (findings.isEmpty()
                ? "No unsafe lock order has been observed on this replica since it started."
                : "Each finding names one STRIPE pair (with the first observed objects as the"
                        + " example) and the two orders seen. An 'upgrade' was refused before it"
                        + " could self-deadlock; an 'inversion' can deadlock, which the"
                        + " acquisition bounds turn into a failed request rather than a stopped"
                        + " repository.")
                + " All data is per-JVM since process start; an inversion recorded once stays"
                + " listed even if the code path that produced it no longer runs.");
        return ResponseEntity.ok(body);
    }

    @GetMapping("/propagation")
    public ResponseEntity<?> propagation() {
        if (!isAdmin()) {
            return forbidden();
        }
        List<Map<String, Object>> running = new ArrayList<>();
        for (PropagationProgress p : PropagationProgress.active()) {
            running.add(p.toMap());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("running", running);
        body.put("count", running.size());
        body.put("note", running.isEmpty()
                ? "No search-index ACL propagation is in flight on this replica."
                : "Descendant readers are refreshed one node at a time on a single thread;"
                        + " concurrent propagations queue behind each other.");
        return ResponseEntity.ok(body);
    }

    // Same shape as the sibling admin controllers — deliberately, so the gate cannot drift
    // between endpoints that expose the same machinery.
    private boolean isAdmin() {
        if (httpRequest == null) return false;
        CallContext ctx = (CallContext) httpRequest.getAttribute("CallContext");
        if (ctx == null) return false;
        Boolean admin = (Boolean) ctx.get(CallContextKey.IS_ADMIN);
        return admin != null && admin;
    }

    private ResponseEntity<?> forbidden() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Only administrators can read search-index observability data");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }
}
