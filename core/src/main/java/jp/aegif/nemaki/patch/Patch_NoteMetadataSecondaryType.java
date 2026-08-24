package jp.aegif.nemaki.patch;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.model.NemakiPropertyDefinition;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionDetail;
import jp.aegif.nemaki.model.NemakiTypeDefinition;

import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;

import java.util.ArrayList;
import java.util.List;

/**
 * Registers nemaki:noteMetadata secondary type for compound note
 * (Notion, Evernote, etc.) page metadata.
 */
public class Patch_NoteMetadataSecondaryType extends AbstractNemakiPatch {

    private static final Log log = LogFactory.getLog(Patch_NoteMetadataSecondaryType.class);
    private static final String PATCH_NAME = "note-metadata-secondary-type-20260403";
    private static final String NEMAKI_NAMESPACE = "http://www.aegif.jp/NEMAKI";
    /** Public so the type↔property association is mechanically checkable (evidence-types §2.2). */
    public static final String TYPE_ID = "nemaki:noteMetadata";

    // Class-level (were method-local) so the id lists below DERIVE from the same literals the
    // patch creates from — the same promotion the chat patch got, for the same reason: the
    // containment test cannot reach a method body (EvidenceTypes javadoc).
    private static final String[][] STRING_PROPERTY_DEFS = {
            {"nemaki:notePageId", "notePageId", "Page ID", "External page/note identifier"},
            {"nemaki:notePageUrl", "notePageUrl", "Page URL", "URL to the page in the source system"},
            {"nemaki:noteParentPageId", "noteParentPageId", "Parent Page ID", "Parent page/notebook identifier"},
            {"nemaki:noteWorkspaceId", "noteWorkspaceId", "Workspace ID", "Workspace/database identifier"},
            {"nemaki:noteAuthor", "noteAuthor", "Author", "Page author"},
            {"nemaki:noteLastEditedBy", "noteLastEditedBy", "Last Edited By", "Last editor"},
            };

    private static final String[][] DATETIME_PROPERTY_DEFS = {
            {"nemaki:noteLastEditedAt", "noteLastEditedAt", "Last Edited At", "Last edit timestamp"},
            {"nemaki:noteCreatedAt", "noteCreatedAt", "Created At", "Page creation timestamp"},
            };

    /** The string property ids this patch declares — derived, so it cannot drift. */
    public static final List<String> STRING_PROPERTY_IDS = idsOf(STRING_PROPERTY_DEFS);
    /** The datetime property ids this patch declares — derived, so it cannot drift. */
    public static final List<String> DATETIME_PROPERTY_IDS = idsOf(DATETIME_PROPERTY_DEFS);

    private static List<String> idsOf(String[][] defs) {
        List<String> ids = new ArrayList<>(defs.length);
        for (String[] def : defs) {
            ids.add(def[0]);
        }
        return List.copyOf(ids);
    }

    @Override
    public String getName() { return PATCH_NAME; }

    @Override
    protected void applySystemPatch() { }

    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        log.info("=== NOTE METADATA SECONDARY TYPE PATCH for " + repositoryId + " ===");
        try {
            TypeService ts = patchUtil.getTypeService();
            if (ts == null) { log.error("TypeService not available"); return; }

            NemakiTypeDefinition existing = ts.getTypeDefinition(repositoryId, TYPE_ID);
            List<String> propIds = new ArrayList<>();


            for (String[] p : STRING_PROPERTY_DEFS) {
                String id = createStringProp(ts, repositoryId, p[0], p[1], p[2], p[3], true);
                if (id != null) propIds.add(id);
            }
            for (String[] p : DATETIME_PROPERTY_DEFS) {
                String id = createDateTimeProp(ts, repositoryId, p[0], p[1], p[2], p[3]);
                if (id != null) propIds.add(id);
            }

            if (existing != null) {
                List<String> ep = existing.getProperties() != null ? existing.getProperties() : new ArrayList<>();
                boolean updated = false;
                for (String pid : propIds) {
                    if (!ep.contains(pid)) { ep.add(pid); updated = true; }
                }
                if (updated) { existing.setProperties(ep); ts.updateTypeDefinition(repositoryId, existing); }
            } else {
                NemakiTypeDefinition td = new NemakiTypeDefinition();
                td.setTypeId(TYPE_ID);
                td.setLocalName("noteMetadata");
                td.setLocalNameSpace(NEMAKI_NAMESPACE);
                td.setQueryName(TYPE_ID);
                td.setDisplayName("Note Metadata");
                td.setDescription("Compound note/page metadata (Notion, Evernote, etc.)");
                td.setBaseId(BaseTypeId.CMIS_SECONDARY);
                td.setParentId("cmis:secondary");
                td.setCreatable(false); td.setFilable(false); td.setQueryable(true);
                td.setFulltextIndexed(true); td.setIncludedInSupertypeQuery(true);
                td.setControllablePolicy(false); td.setControllableACL(false);
                td.setTypeMutabilityCreate(true); td.setTypeMutabilityUpdate(true); td.setTypeMutabilityDelete(true);
                td.setProperties(propIds);
                try { ts.createTypeDefinition(repositoryId, td); log.info("Created " + TYPE_ID); }
                catch (Exception e) {
                    if (e.getMessage() != null && e.getMessage().toLowerCase().contains("conflict")) {
                        log.info(TYPE_ID + " already exists");
                    } else { throw e; }
                }
            }

            if (patchUtil.getTypeManager() != null) {
                patchUtil.getTypeManager().invalidateTypeCache(repositoryId);
                try { patchUtil.getTypeManager().refreshTypes(); } catch (Exception e) { /* ignore */ }
            }
        } catch (Exception e) {
            log.error("Error in noteMetadata patch for " + repositoryId, e);
        }
    }

    private String createStringProp(TypeService ts, String repoId, String propId, String localName,
                                    String displayName, String desc, boolean queryable) {
        var core = ts.getPropertyDefinitionCoreByPropertyId(repoId, propId);
        if (core != null) {
            var details = ts.getPropertyDefinitionDetailByCoreNodeId(repoId, core.getId());
            return (details != null && !details.isEmpty()) ? details.get(0).getId() : null;
        }
        NemakiPropertyDefinition pd = new NemakiPropertyDefinition();
        pd.setPropertyId(propId); pd.setLocalName(localName); pd.setLocalNameSpace(NEMAKI_NAMESPACE);
        pd.setQueryName(propId); pd.setDisplayName(displayName); pd.setDescription(desc);
        pd.setPropertyType(PropertyType.STRING); pd.setCardinality(Cardinality.SINGLE);
        pd.setUpdatability(Updatability.READWRITE); pd.setRequired(false);
        pd.setQueryable(queryable); pd.setOrderable(queryable);
        return ts.createPropertyDefinition(repoId, pd).getId();
    }

    private String createDateTimeProp(TypeService ts, String repoId, String propId, String localName,
                                      String displayName, String desc) {
        var core = ts.getPropertyDefinitionCoreByPropertyId(repoId, propId);
        if (core != null) {
            var details = ts.getPropertyDefinitionDetailByCoreNodeId(repoId, core.getId());
            return (details != null && !details.isEmpty()) ? details.get(0).getId() : null;
        }
        NemakiPropertyDefinition pd = new NemakiPropertyDefinition();
        pd.setPropertyId(propId); pd.setLocalName(localName); pd.setLocalNameSpace(NEMAKI_NAMESPACE);
        pd.setQueryName(propId); pd.setDisplayName(displayName); pd.setDescription(desc);
        pd.setPropertyType(PropertyType.DATETIME); pd.setCardinality(Cardinality.SINGLE);
        pd.setUpdatability(Updatability.READWRITE); pd.setRequired(false);
        pd.setQueryable(true); pd.setOrderable(true);
        return ts.createPropertyDefinition(repoId, pd).getId();
    }
}
