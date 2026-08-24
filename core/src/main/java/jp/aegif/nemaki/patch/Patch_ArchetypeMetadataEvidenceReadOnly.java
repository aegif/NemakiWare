package jp.aegif.nemaki.patch;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionCore;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionDetail;

import org.apache.chemistry.opencmis.commons.enums.Updatability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Makes the mail / note / business-record capture evidence read-only through CMIS.
 *
 * <h2>Why this exists, three types at a time</h2>
 *
 * <p>{@code Patch_ChatContextEvidenceReadOnly} did this for chat in 2026-08-21, and P1-1(c) §8
 * deliberately stopped there: the roadmap named the chat patch, and moving all four types at
 * once would have blurred "what did we decide is evidence". The boundary is now written down —
 * (c) §8.1, clause by clause — so the remaining three move together, under one criterion:
 *
 * <blockquote>An evidence type is the home of facts the ingest OBSERVED at the source. Losing or
 * rewriting them either removes the backing for "where this document came from", or breaks the
 * idempotence of re-ingest. That is a different test from READONLY, which only means the server
 * owns the value ({@code cmis:createdBy} is READONLY and is not evidence).</blockquote>
 *
 * <p>All three types are entirely evidence under that test: {@code internetMessageId} is RFC
 * 2822's Message-ID — the canonical identity of an email, the same position chat's
 * {@code chatMessageId} holds — and {@code notePageId} / {@code recordId} are the source
 * system's identity for a page and a record.
 *
 * <h2>What read-only means, and what it does not</h2>
 *
 * <p>Same as the chat patch: CMIS {@code READONLY} means the server owns the value, so
 * {@code injectPropertyValue} SKIPS a client's value rather than rejecting it, while the ingest
 * — which writes through the model, not through {@code modifyProperties} — keeps working. It is
 * an application-layer rule: direct CouchDB access is unaffected, and values edited before this
 * patch ran are unchanged and indistinguishable from ones never edited.
 *
 * <p>READONLY alone is not enough, which is why this patch ships with the three type ids being
 * added to {@code EvidenceTypes.PROTECTED} in the same commit: the detach-by-type route
 * (evidence-types §1) walks past per-property updatability entirely.
 */
public class Patch_ArchetypeMetadataEvidenceReadOnly extends AbstractNemakiPatch {

    private static final Log log =
            LogFactory.getLog(Patch_ArchetypeMetadataEvidenceReadOnly.class);

    /**
     * The three rosters, keyed by their type.
     *
     * <p>DERIVED from each type patch's own declarations rather than re-listed here. The chat
     * patch had to re-list them because its properties were method-local literals at the time;
     * that is exactly the drift {@code EvidenceProtectionRosterTest} was written to catch, and
     * there is no reason to reproduce it now that the lists are public constants.
     */
    public static final Map<String, List<String>> EVIDENCE_PROPERTIES_BY_TYPE = buildRosters();

    private static Map<String, List<String>> buildRosters() {
        Map<String, List<String>> rosters = new LinkedHashMap<>();
        rosters.put(Patch_MessageMetadataSecondaryType.TYPE_ID,
                joined(Patch_MessageMetadataSecondaryType.STRING_PROPERTY_IDS,
                        Patch_MessageMetadataSecondaryType.DATETIME_PROPERTY_IDS));
        rosters.put(Patch_NoteMetadataSecondaryType.TYPE_ID,
                joined(Patch_NoteMetadataSecondaryType.STRING_PROPERTY_IDS,
                        Patch_NoteMetadataSecondaryType.DATETIME_PROPERTY_IDS));
        rosters.put(Patch_BusinessRecordMetadataSecondaryType.TYPE_ID,
                joined(Patch_BusinessRecordMetadataSecondaryType.STRING_PROPERTY_IDS,
                        Patch_BusinessRecordMetadataSecondaryType.DATETIME_PROPERTY_IDS));
        return Map.copyOf(rosters);
    }

    private static List<String> joined(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a.size() + b.size());
        all.addAll(a);
        all.addAll(b);
        return List.copyOf(all);
    }

    /** Every protected property across the three types — the flat view callers want. */
    public static final List<String> EVIDENCE_PROPERTIES = flatten();

    private static List<String> flatten() {
        List<String> all = new ArrayList<>();
        EVIDENCE_PROPERTIES_BY_TYPE.values().forEach(all::addAll);
        return List.copyOf(all);
    }

    @Override
    public String getName() {
        return "archetype-metadata-evidence-readonly-20260824";
    }

    @Override
    protected void applySystemPatch() {
        // Property definitions are per repository.
    }

    /**
     * Applies the protection, or throws so that it is retried.
     *
     * <p>The guard is PER TYPE and requires the WHOLE roster, which is stricter than the chat
     * patch's on both counts.
     *
     * <ul>
     *   <li>Per type, because a repository that found plenty of note properties would otherwise
     *       record the patch as applied and leave {@code messageMetadata} writable for good.</li>
     *   <li>The whole roster, because "found one of eleven" is a view answering INCOMPLETELY —
     *       the same silence the single-property lookup cannot distinguish from absence, one
     *       level up. Accepting it records partial protection as complete (external review).</li>
     * </ul>
     *
     * <p>Retrying is cheap and harmless: {@code makeReadOnly} skips definitions that are already
     * READONLY, so a repeat run rewrites nothing and bumps no revisions.
     *
     * <p><b>The creator-swallow wedge is closed</b> (roadmap §2-1/§2-2, 2026-08-24). All three
     * creator patches now call {@code reportIncomplete} from their catch blocks instead of only
     * logging, so a failed creation withholds its own history row and is retried on the next
     * start — which means this patch's retry converges instead of throwing for ever. The
     * message below still says what to do if it somehow does not, because a wedged deployment
     * needs the remedy in the log rather than in a design document.
     */
    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        TypeService typeService = patchUtil.getTypeService();
        if (typeService == null) {
            throw new IllegalStateException("no TypeService, so nothing could be protected in "
                    + repositoryId + "; retry on the next start");
        }
        int changed = 0;
        List<String> failures = new ArrayList<>();
        List<String> typesWithNothingFound = new ArrayList<>();

        for (Map.Entry<String, List<String>> roster : EVIDENCE_PROPERTIES_BY_TYPE.entrySet()) {
            List<String> absent = new ArrayList<>();
            for (String propertyId : roster.getValue()) {
                try {
                    Outcome outcome = makeReadOnly(typeService, repositoryId, propertyId);
                    if (outcome == Outcome.ABSENT) {
                        absent.add(propertyId);
                    }
                    if (outcome == Outcome.CHANGED) {
                        changed++;
                    }
                } catch (Exception e) {
                    // One property failing must not stop the rest — a partially protected type
                    // is better than an unprotected one — but it must not be forgotten either.
                    log.error("Could not make " + propertyId + " read-only in " + repositoryId, e);
                    failures.add(propertyId);
                }
            }
            if (!absent.isEmpty()) {
                // ANY absence, not just total absence: one property missing out of eleven is a
                // view answering incompletely, and recording that as applied leaves the ten
                // that were found protected and the one that was not writable, permanently.
                typesWithNothingFound.add(roster.getKey() + " (missing " + absent + ")");
            }
        }

        if (!failures.isEmpty()) {
            throw new IllegalStateException("could not protect " + failures + " in " + repositoryId
                    + "; retry on the next start");
        }
        if (!typesWithNothingFound.isEmpty()) {
            throw new IllegalStateException("evidence properties were not found for "
                    + typesWithNothingFound + " in " + repositoryId + ". Either the type patches "
                    + "have not run yet or the property views are not answering; both are "
                    + "retryable, and recording this as applied would leave that evidence "
                    + "writable for good. If this repeats on every start, the creating patch "
                    + "(Patch_MessageMetadataSecondaryType / Patch_NoteMetadataSecondaryType / "
                    + "Patch_BusinessRecordMetadataSecondaryType) swallowed its own failure and "
                    + "will not run again: delete its row from the patch history so it re-runs, "
                    + "then restart");
        }
        if (changed > 0) {
            log.info("Made " + changed + " archetype evidence property/properties read-only in "
                    + repositoryId);
            if (patchUtil.getTypeManager() != null) {
                patchUtil.getTypeManager().invalidateTypeCache(repositoryId);
                try {
                    patchUtil.getTypeManager().refreshTypes();
                } catch (Exception e) {
                    log.warn("Type cache refresh failed after the read-only patch", e);
                }
            }
        }
    }

    /** What one property's protection attempt did. */
    private enum Outcome {
        /** It was READWRITE and is now READONLY. */
        CHANGED,
        /** It was already READONLY. */
        ALREADY,
        /** No definition to protect — which may mean the view is silent, so it is not success. */
        ABSENT
    }

    private Outcome makeReadOnly(TypeService typeService, String repositoryId, String propertyId) {
        NemakiPropertyDefinitionCore core =
                typeService.getPropertyDefinitionCoreByPropertyId(repositoryId, propertyId);
        if (core == null) {
            // Absent, not success. The lookup goes through a view, and a view that is not
            // answering returns null exactly as a genuinely missing property does.
            return Outcome.ABSENT;
        }
        List<NemakiPropertyDefinitionDetail> details =
                typeService.getPropertyDefinitionDetailByCoreNodeId(repositoryId, core.getId());
        if (details == null || details.isEmpty()) {
            return Outcome.ABSENT;
        }
        boolean changed = false;
        for (NemakiPropertyDefinitionDetail detail : details) {
            if (Updatability.READONLY.equals(detail.getUpdatability())) {
                // Already protected. Rewriting it would bump the definition's revision for
                // nothing, and on a repository that is re-patched after a restore it would do so
                // for every property.
                continue;
            }
            detail.setUpdatability(Updatability.READONLY);
            // The DETAIL, never the core. TypeService's own javadoc warns that cores may be
            // shared across types, so rewriting one would reach types this patch is not about.
            typeService.updatePropertyDefinitionDetail(repositoryId, detail);
            changed = true;
        }
        return changed ? Outcome.CHANGED : Outcome.ALREADY;
    }
}
