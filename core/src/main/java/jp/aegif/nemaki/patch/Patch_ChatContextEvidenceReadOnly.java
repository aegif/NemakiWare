package jp.aegif.nemaki.patch;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionCore;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionDetail;

import org.apache.chemistry.opencmis.commons.enums.Updatability;

import java.util.ArrayList;
import java.util.List;

/**
 * Makes the chat-context evidence properties read-only through CMIS.
 *
 * <h2>Why a second patch rather than a change to the first</h2>
 *
 * <p>{@code Patch_ChatContextMetadataSecondaryType} creates each property only when it is absent
 * — {@code mkStr} and {@code mkDt} return the existing id and never touch it again. That is the
 * right behaviour for creation, and it is exactly why editing {@code setUpdatability} there
 * changes nothing on a deployment that already ran it: the code says {@code READONLY} while the
 * stored definition stays {@code READWRITE}, and two servers on the same build behave
 * differently. Rewriting an existing definition is a different operation from creating one, so
 * it is a different patch rather than a branch inside the creation path.
 *
 * <h2>What read-only means here</h2>
 *
 * <p>CMIS {@code READONLY} means the server owns the value, as it does for
 * {@code cmis:createdBy}. {@code ContentServiceImpl.injectPropertyValue} skips such properties
 * when building an update, so a CMIS client's value is <b>ignored</b> rather than rejected. The
 * ingest writes these properties through the model directly — it builds {@code Property} objects
 * and calls {@code ContentService.update} — so it never passes through that check and keeps
 * working. That asymmetry is the point: the party that captured the evidence may write it, and
 * a later editor may not.
 *
 * <h2>What this does NOT establish</h2>
 *
 * <p>Not immutability. This is an application-layer rule; anything with direct access to CouchDB
 * is unaffected, and values edited before this patch ran are unchanged and indistinguishable
 * from ones that were never edited. Nor does it make the evidence correct — only harder to
 * revise after the fact.
 */
public class Patch_ChatContextEvidenceReadOnly extends AbstractNemakiPatch {

    private static final Log log = LogFactory.getLog(Patch_ChatContextEvidenceReadOnly.class);

    /**
     * The chat-context properties, all eleven of them.
     *
     * <p>Listed here rather than read back from the type definition: the point of the patch is to
     * assert what SHOULD be protected, and deriving the list from what happens to be on the type
     * would make a missing property silently unprotected.
     */
    public static final List<String> EVIDENCE_PROPERTIES = List.of(
            "nemaki:chatWorkspaceId",
            "nemaki:chatChannelId",
            "nemaki:chatChannelName",
            "nemaki:chatThreadId",
            "nemaki:chatMessageId",
            "nemaki:chatParticipants",
            "nemaki:chatSelectionReason",
            "nemaki:chatEvidenceScope",
            "nemaki:chatCapturedAt",
            "nemaki:chatCaptureWindowStart",
            "nemaki:chatCaptureWindowEnd");

    @Override
    public String getName() {
        return "chat-context-evidence-readonly-20260821";
    }

    @Override
    protected void applySystemPatch() {
        // Property definitions are per repository.
    }

    /**
     * Applies the protection, or throws so that it is retried.
     *
     * <p><b>This patch runs once, not on every start.</b> {@code AbstractNemakiPatch.apply}
     * records it as applied as soon as this method returns without throwing, and never calls it
     * again. So "we found nothing to protect" must NOT be a normal return: the property
     * definitions are read through a view, and a view that is not answering yet makes every
     * lookup come back {@code null} — indistinguishable from "this repository has no chat type".
     * Returning quietly there would mark the repository protected while leaving every evidence
     * property writable, permanently (external review).
     *
     * <p>So the rule is: reaching the end having protected nothing AND having found nothing is
     * a failure, and the exception is what buys a retry on the next start. A repository that
     * genuinely has no chat type retries harmlessly and cheaply.
     */
    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        TypeService typeService = patchUtil.getTypeService();
        if (typeService == null) {
            throw new IllegalStateException("no TypeService, so nothing could be protected in "
                    + repositoryId + "; retry on the next start");
        }
        int changed = 0;
        int found = 0;
        List<String> failures = new ArrayList<>();
        for (String propertyId : EVIDENCE_PROPERTIES) {
            try {
                Outcome outcome = makeReadOnly(typeService, repositoryId, propertyId);
                if (outcome != Outcome.ABSENT) {
                    found++;
                }
                if (outcome == Outcome.CHANGED) {
                    changed++;
                }
            } catch (Exception e) {
                // One property failing must not stop the rest — a partially protected type is
                // better than an unprotected one — but it must not be forgotten either.
                log.error("Could not make " + propertyId + " read-only in " + repositoryId, e);
                failures.add(propertyId);
            }
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("could not protect " + failures + " in " + repositoryId
                    + "; retry on the next start");
        }
        if (found == 0) {
            throw new IllegalStateException("none of the chat evidence properties were found in "
                    + repositoryId + ". Either the type patch has not run yet or the property "
                    + "views are not answering; both are retryable, and recording this as applied "
                    + "would leave the evidence writable for good");
        }
        if (changed > 0) {
            log.info("Made " + changed + " chat-context evidence property/properties read-only in "
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
