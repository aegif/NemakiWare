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
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The purge ledger in the lineage database — same database, same strict-IO rules.
 *
 * <p>One document per subject, id derived from the subject digest, so recording a purge twice
 * cannot produce two marks and a reader never has to choose between them.
 */
public final class CouchLineagePurgeLedger implements LineagePurgeLedger {

    private static final Logger logger = LoggerFactory.getLogger(CouchLineagePurgeLedger.class);

    private final LineageStoreSupport support;

    public CouchLineagePurgeLedger(LineageStoreSupport support) {
        this.support = support;
    }

    @Override
    public boolean available() {
        return support != null;
    }

    /**
     * The kinds a lifecycle hook actually records purges for today.
     *
     * <p>{@code ContentServiceImpl.destroyArchive} / {@code restoreArchive} cover the three
     * repository kinds. The external and artifact kinds are owned by their own connectors, and
     * until each one records its purges here the ledger cannot answer for them — so they are
     * NOT listed, and readiness refuses to go green while they are still emittable.
     *
     * <p>Deliberately a hard-coded statement of fact rather than something derived: a derived
     * answer would say "yes" for any kind whose hook exists in some build, and the question is
     * whether it exists in this one.
     */
    @Override
    public java.util.Set<EndpointKind> lifecycleCoveredKinds() {
        return java.util.Set.of(EndpointKind.CMIS_DOCUMENT, EndpointKind.CMIS_FOLDER,
                EndpointKind.ARCHIVE);
    }

    /**
     * Recorded once. A second purge of the same subject keeps the first mark rather than
     * overwriting it — the first is the one whose incarnation and revision were observed at the
     * moment the object went away.
     *
     * <p>Any failure is swallowed and logged. The alternative is a lineage concern aborting a
     * repository operation, and a mark that was not written degrades to {@code UNKNOWN}, which
     * refuses to publish a tombstone. Losing the mark costs an unresolvable obligation; failing
     * the caller costs the user their delete.
     */
    @Override
    public void recordPurge(String repositoryId, EndpointKind kind, String subjectDigest,
            String incarnation, String revision, long purgedAtMs) {
        if (repositoryId == null || kind == null || subjectDigest == null
                || subjectDigest.isBlank() || incarnation == null || incarnation.isBlank()
                || revision == null || revision.isBlank()) {
            // A mark that cannot name what was destroyed cannot authorise anything later.
            logger.warn("Refusing an incomplete purge mark for a {} subject", kind);
            return;
        }
        try {
            support.ensureDatabase();
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("_id", DOCUMENT_ID_PREFIX + subjectDigest);
            raw.put("type", DOCUMENT_TYPE);
            raw.put("repositoryId", repositoryId);
            raw.put("endpointKind", kind.name());
            raw.put("subjectDigest", subjectDigest);
            raw.put("incarnation", incarnation);
            raw.put("revision", revision);
            raw.put("purgedAtMs", purgedAtMs);
            support.client().create(DOCUMENT_ID_PREFIX + subjectDigest, raw);
        } catch (com.ibm.cloud.sdk.core.service.exception.ConflictException alreadyRecorded) {
            // The first mark stands. Nothing to reconcile: the object was already gone.
            logger.debug("Purge mark already recorded for a {} subject", kind);
        } catch (RuntimeException e) {
            logger.warn("Could not record a purge mark for a {} subject: {} — the subject will"
                    + " read as UNKNOWN rather than PURGED", kind, e.getClass().getSimpleName());
        }
    }

    /**
     * Supersede, do not delete.
     *
     * <p>Deleting would erase the fact that the object was ever purged, and that fact is what
     * explains an existing tombstone to whoever finds it. Superseding stops the mark
     * authorising anything new while leaving the history readable.
     */
    @Override
    public void invalidateOnRestore(String repositoryId, EndpointKind kind, String subjectDigest,
            long restoredAtMs) {
        if (subjectDigest == null || subjectDigest.isBlank()) {
            return;
        }
        try {
            support.ensureDatabase();
            Map<String, Object> raw = support.readRawStrict(DOCUMENT_ID_PREFIX + subjectDigest);
            if (raw == null) {
                return;
            }
            if (raw.get("invalidatedAtMs") != null) {
                return;
            }
            raw.put("invalidatedAtMs", restoredAtMs);
            if (!support.updateStrictCas(raw)) {
                // Someone else wrote it. Re-read once: the only concurrent writer is another
                // restore, which sets the same field, so a lost CAS is not a lost invalidation.
                Map<String, Object> reread =
                        support.readRawStrict(DOCUMENT_ID_PREFIX + subjectDigest);
                if (reread != null && reread.get("invalidatedAtMs") == null) {
                    reread.put("invalidatedAtMs", restoredAtMs);
                    support.updateStrictCas(reread);
                }
            }
        } catch (RuntimeException e) {
            logger.warn("Could not invalidate a purge mark for a {} subject: {} — a restored"
                    + " object could still read as PURGED until this succeeds",
                    kind, e.getClass().getSimpleName());
        }
    }

    /**
     * Read, strictly.
     *
     * <p>Unlike the write paths this one propagates: a reader that swallowed the failure would
     * return "no mark", and "no mark" is indistinguishable from "not purged". The caller turns
     * an exception into {@code UNKNOWN}; it must not turn it into a verdict.
     */
    @Override
    public Optional<PurgeMark> find(String repositoryId, EndpointKind kind,
            String subjectDigest) {
        if (subjectDigest == null || subjectDigest.isBlank()) {
            return Optional.empty();
        }
        support.ensureDatabase();
        Map<String, Object> raw = support.readRawStrict(DOCUMENT_ID_PREFIX + subjectDigest);
        if (raw == null) {
            return Optional.empty();
        }
        if (!DOCUMENT_TYPE.equals(raw.get("type"))) {
            throw new IllegalStateException("a document at a purge mark's id is not a purge mark");
        }
        // The mark must agree with what is being asked about. A digest collision, or a caller
        // asking with the wrong repository or kind, must not be answered from another subject's
        // mark — that is precisely how one object's destruction tombstones another.
        if (!subjectDigest.equals(raw.get("subjectDigest"))
                || (repositoryId != null && !repositoryId.equals(raw.get("repositoryId")))
                || (kind != null && !kind.name().equals(raw.get("endpointKind")))) {
            throw new IllegalStateException("a purge mark describes a different subject than the"
                    + " one asked about");
        }
        Long invalidated = raw.get("invalidatedAtMs") instanceof Number n ? n.longValue() : null;
        long purgedAt = raw.get("purgedAtMs") instanceof Number n ? n.longValue() : 0L;
        return Optional.of(new PurgeMark(String.valueOf(raw.get("repositoryId")), kind,
                subjectDigest, String.valueOf(raw.get("incarnation")),
                String.valueOf(raw.get("revision")), purgedAt, invalidated));
    }
}
