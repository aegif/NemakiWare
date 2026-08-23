package jp.aegif.nemaki.patch;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.model.NemakiPropertyDefinition;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionCore;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionDetail;
import jp.aegif.nemaki.model.NemakiTypeDefinition;

import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;

import java.util.ArrayList;
import java.util.List;

/**
 * Makes the {@code nemaki:externalIntegration} source-identity properties read-only through
 * CMIS, and gives {@code nemaki:contentHash} a declaration so its protection stops being an
 * accident.
 *
 * <h2>Why</h2>
 *
 * <p>P1-1(c) protected the chat evidence and deliberately stopped there. That left the aspect
 * holding the DEDUPE KEY writable: rewrite {@code nemaki:sourceObjectId} through CMIS and the
 * next poll sees a different identity and re-imports the object as new; rewrite
 * {@code nemaki:sourceUrl} and the provenance display lies. The applied-metadata hash records
 * these nine values per pass, but until this patch a mismatch there meant "changed since
 * capture", not "tampered" — a legitimate CMIS edit was indistinguishable from an attack.
 * This patch is the promotion the hash design calls out (p1-1d-metadata-hash.md §2.3).
 *
 * <h2>contentHash gets a DECLARATION, read-only from birth</h2>
 *
 * <p>Until now no patch declared it. The absence protected it by accident — undeclared
 * properties never pass {@code injectPropertyValue} and {@code mergeAspectProperties}
 * preserves unknown keys — and the ledgers were explicit that an accident must not be called
 * protection. Declaring it READONLY makes the protection intentional and makes the recorded
 * digest visible to CMIS readers, which it should be: it is evidence about the object.
 *
 * <h2>Same discipline as the chat patch</h2>
 *
 * <p>Runs once ({@code AbstractNemakiPatch} records it as applied on a clean return), so
 * finding NOTHING must throw rather than return: the definitions are read through views, and
 * a silent view makes every property look absent. Rewrites the DETAIL only — cores may be
 * shared across types.
 */
public class Patch_ExternalIntegrationEvidenceReadOnly extends AbstractNemakiPatch {

    private static final Log log =
            LogFactory.getLog(Patch_ExternalIntegrationEvidenceReadOnly.class);

    /** Public so the type↔property association is mechanically checkable (evidence-types §2.2). */
    public static final String TYPE_ID = "nemaki:externalIntegration";

    /** The property this patch DECLARES (read-only from birth) — the 9th source-identity id. */
    public static final String CONTENT_HASH_PROPERTY_ID = "nemaki:contentHash";

    /**
     * The eight DECLARED source-identity properties this patch rewrites to READONLY.
     *
     * <p>{@code nemaki:contentHash} is deliberately not in this list — it has no stored
     * definition to rewrite; it is CREATED read-only below. The union of this list plus
     * contentHash equals {@code EvidenceMetadataHash.SOURCE_IDENTITY_PROPERTIES}, and a test
     * pins that so the hash and the protection cannot drift apart.
     */
    public static final List<String> DECLARED_SOURCE_IDENTITY_PROPERTIES = List.of(
            "nemaki:sourceArchetype",
            "nemaki:sourceSystem",
            "nemaki:sourceObjectType",
            "nemaki:sourceObjectId",
            "nemaki:sourceUrl",
            "nemaki:ingestionRunId",
            "nemaki:externalSourceType",
            "nemaki:externalSourceId");

    @Override
    public String getName() {
        return "external-integration-evidence-readonly-20260823";
    }

    @Override
    protected void applySystemPatch() {
        // Property definitions are per repository.
    }

    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        TypeService typeService = patchUtil.getTypeService();
        if (typeService == null) {
            throw new IllegalStateException("no TypeService, so nothing could be protected in "
                    + repositoryId + "; retry on the next start");
        }
        NemakiTypeDefinition type = typeService.getTypeDefinition(repositoryId, TYPE_ID);
        if (type == null) {
            throw new IllegalStateException("type " + TYPE_ID + " not found — the type patches "
                    + "have not run yet; retry on the next start");
        }
        // Cores are shared by property id across ALL types, but a detail belongs to the types
        // whose definition references its id. Everything below touches ONLY details this type
        // references: rewriting a foreign type's detail because its author reused a property id
        // would silently change that type's contract (external review — the first shape
        // rewrote every detail of the core and attached details.get(0), whichever type it
        // belonged to).
        java.util.Set<String> attachedDetailIds = type.getProperties() == null
                ? java.util.Set.of() : new java.util.HashSet<>(type.getProperties());
        int found = 0;
        List<String> failures = new ArrayList<>();
        for (String propertyId : DECLARED_SOURCE_IDENTITY_PROPERTIES) {
            try {
                if (makeReadOnly(typeService, repositoryId, propertyId, attachedDetailIds)) {
                    found++;
                }
            } catch (Exception e) {
                log.error("Could not make " + propertyId + " read-only in " + repositoryId, e);
                failures.add(propertyId);
            }
        }
        try {
            declareContentHashReadOnly(typeService, repositoryId, type, attachedDetailIds);
        } catch (Exception e) {
            log.error("Could not declare nemaki:contentHash in " + repositoryId, e);
            failures.add("nemaki:contentHash");
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("could not protect " + failures + " in "
                    + repositoryId + "; retry on the next start");
        }
        if (found == 0) {
            throw new IllegalStateException("none of the source-identity properties were found "
                    + "in " + repositoryId + ". Either the type patches have not run yet or the "
                    + "property views are not answering; both are retryable, and recording this "
                    + "as applied would leave the evidence writable for good");
        }
        if (patchUtil.getTypeManager() != null) {
            patchUtil.getTypeManager().invalidateTypeCache(repositoryId);
            try {
                patchUtil.getTypeManager().refreshTypes();
            } catch (Exception e) {
                log.warn("Type cache refresh failed after the read-only patch", e);
            }
        }
    }

    /**
     * True when the property is declared ON THE EVIDENCE TYPE (already read-only counts as
     * found). A core whose details all belong to other types is "not found" — this type does
     * not declare the property, and those types keep their own contract, said out loud.
     */
    private boolean makeReadOnly(TypeService typeService, String repositoryId,
            String propertyId, java.util.Set<String> attachedDetailIds) {
        NemakiPropertyDefinitionCore core =
                typeService.getPropertyDefinitionCoreByPropertyId(repositoryId, propertyId);
        if (core == null) {
            return false;
        }
        List<NemakiPropertyDefinitionDetail> details =
                typeService.getPropertyDefinitionDetailByCoreNodeId(repositoryId, core.getId());
        if (details == null || details.isEmpty()) {
            return false;
        }
        boolean declaredOnThisType = false;
        int foreign = 0;
        for (NemakiPropertyDefinitionDetail detail : details) {
            if (!attachedDetailIds.contains(detail.getId())) {
                foreign++;
                continue;
            }
            declaredOnThisType = true;
            if (Updatability.READONLY.equals(detail.getUpdatability())) {
                continue;
            }
            detail.setUpdatability(Updatability.READONLY);
            typeService.updatePropertyDefinitionDetail(repositoryId, detail);
        }
        if (foreign > 0) {
            log.warn(propertyId + " in " + repositoryId + " is also declared by " + foreign
                    + " definition(s) outside " + TYPE_ID + "; those keep their own "
                    + "updatability — this patch protects the evidence type only");
        }
        return declaredOnThisType;
    }

    /**
     * Creates {@code nemaki:contentHash} READONLY and attaches it to the type, if absent.
     *
     * <p>Values already stored under the undeclared key stay exactly where they are — the
     * declaration changes what CMIS shows and refuses, not what CouchDB holds.
     */
    private void declareContentHashReadOnly(TypeService typeService, String repositoryId,
            NemakiTypeDefinition type, java.util.Set<String> attachedDetailIds) {
        NemakiPropertyDefinitionCore existing =
                typeService.getPropertyDefinitionCoreByPropertyId(repositoryId,
                        CONTENT_HASH_PROPERTY_ID);
        String detailId;
        if (existing != null) {
            List<NemakiPropertyDefinitionDetail> details =
                    typeService.getPropertyDefinitionDetailByCoreNodeId(repositoryId,
                            existing.getId());
            if (details == null || details.isEmpty()) {
                throw new IllegalStateException(
                        "nemaki:contentHash has a core but no detail — view silence or a "
                                + "half-created definition; retry on the next start");
            }
            // Only a detail THIS type references may be rewritten or reused. Attaching
            // details.get(0) regardless — the first shape — would have shared a foreign
            // type's detail node with the evidence type: one admin edit then changes both.
            NemakiPropertyDefinitionDetail ours = null;
            int foreign = 0;
            for (NemakiPropertyDefinitionDetail detail : details) {
                if (attachedDetailIds.contains(detail.getId())) {
                    if (ours == null) {
                        ours = detail;
                    }
                } else {
                    foreign++;
                }
            }
            if (foreign > 0) {
                log.warn("nemaki:contentHash in " + repositoryId + " is also declared by "
                        + foreign + " definition(s) outside " + TYPE_ID + "; those keep their "
                        + "own updatability — this patch declares it on the evidence type only");
            }
            if (ours != null) {
                if (!Updatability.READONLY.equals(ours.getUpdatability())) {
                    ours.setUpdatability(Updatability.READONLY);
                    typeService.updatePropertyDefinitionDetail(repositoryId, ours);
                }
                detailId = ours.getId();
            } else {
                // The core exists but belongs to other types. createPropertyDefinition would
                // dodge the id collision by minting "nemaki:contentHash_<timestamp>" — a
                // different property — so the detail is created directly on the shared core.
                NemakiPropertyDefinitionDetail fresh =
                        new NemakiPropertyDefinitionDetail(contentHashDefinition(),
                                existing.getId());
                NemakiPropertyDefinitionDetail created = patchUtil.getContentDaoService()
                        .createPropertyDefinitionDetail(repositoryId, fresh);
                if (created == null) {
                    throw new IllegalStateException("creating the nemaki:contentHash detail on "
                            + "the existing core returned nothing; retry on the next start");
                }
                detailId = created.getId();
            }
        } else {
            NemakiPropertyDefinitionDetail created =
                    typeService.createPropertyDefinition(repositoryId, contentHashDefinition());
            if (created == null) {
                throw new IllegalStateException(
                        "creating nemaki:contentHash returned nothing; retry on the next start");
            }
            detailId = created.getId();
        }

        List<String> properties = type.getProperties() == null
                ? new ArrayList<>() : new ArrayList<>(type.getProperties());
        if (!properties.contains(detailId)) {
            properties.add(detailId);
            type.setProperties(properties);
            typeService.updateTypeDefinition(repositoryId, type);
        }
    }

    /** The declaration, shared by the fresh-create and existing-core branches verbatim. */
    private static NemakiPropertyDefinition contentHashDefinition() {
        NemakiPropertyDefinition propDef = new NemakiPropertyDefinition();
        propDef.setPropertyId(CONTENT_HASH_PROPERTY_ID);
        propDef.setLocalName("contentHash");
        propDef.setLocalNameSpace("http://www.aegif.jp/Nemaki");
        propDef.setQueryName("nemaki:contentHash");
        propDef.setDisplayName("Content Hash");
        propDef.setDescription("SHA-256 of the content bytes this ingest fetched and "
                + "supplied (subject: input). Written by the capture path only.");
        propDef.setPropertyType(PropertyType.STRING);
        propDef.setCardinality(Cardinality.SINGLE);
        propDef.setUpdatability(Updatability.READONLY);
        propDef.setRequired(false);
        propDef.setQueryable(true);
        propDef.setOrderable(false);
        return propDef;
    }
}
