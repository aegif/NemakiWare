package jp.aegif.nemaki.patch;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.model.NemakiPropertyDefinition;
import jp.aegif.nemaki.model.NemakiTypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.*;
import java.util.ArrayList;
import java.util.List;

/**
 * nemaki:businessRecordMetadata — CRM/ERP/BPM record metadata.
 */
public class Patch_BusinessRecordMetadataSecondaryType extends AbstractNemakiPatch {
    private static final Log log = LogFactory.getLog(Patch_BusinessRecordMetadataSecondaryType.class);
    private static final String NS = "http://www.aegif.jp/NEMAKI";
    private static final String TYPE_ID = "nemaki:businessRecordMetadata";

    @Override public String getName() { return "business-record-metadata-20260403"; }
    @Override protected void applySystemPatch() { }

    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        try {
            TypeService ts = patchUtil.getTypeService();
            if (ts == null) return;
            NemakiTypeDefinition existing = ts.getTypeDefinition(repositoryId, TYPE_ID);
            List<String> pids = new ArrayList<>();

            String[][] sp = {
                {"nemaki:recordType", "recordType", "Record Type", "CRM/ERP record type (Account, Invoice, Case, etc.)"},
                {"nemaki:recordId", "recordId", "Record ID", "Stable record identifier in source system"},
                {"nemaki:recordUrl", "recordUrl", "Record URL", "URL to the record in the source system"},
                {"nemaki:recordStatus", "recordStatus", "Status", "Current record status"},
                {"nemaki:recordOwner", "recordOwner", "Owner", "Record owner / responsible party"},
                {"nemaki:processInstanceId", "processInstanceId", "Process Instance ID", "BPM process/case/ticket ID"},
            };
            String[][] dp = {
                {"nemaki:recordCreatedAt", "recordCreatedAt", "Record Created", "Record creation date in source"},
                {"nemaki:recordModifiedAt", "recordModifiedAt", "Record Modified", "Record last modified date"},
            };

            for (String[] p : sp) { String id = mkStr(ts, repositoryId, p); if (id != null) pids.add(id); }
            for (String[] p : dp) { String id = mkDt(ts, repositoryId, p); if (id != null) pids.add(id); }

            if (existing != null) {
                List<String> ep = existing.getProperties() != null ? existing.getProperties() : new ArrayList<>();
                boolean u = false;
                for (String pid : pids) { if (!ep.contains(pid)) { ep.add(pid); u = true; } }
                if (u) { existing.setProperties(ep); ts.updateTypeDefinition(repositoryId, existing); }
            } else {
                NemakiTypeDefinition td = new NemakiTypeDefinition();
                td.setTypeId(TYPE_ID); td.setLocalName("businessRecordMetadata"); td.setLocalNameSpace(NS);
                td.setQueryName(TYPE_ID); td.setDisplayName("Business Record Metadata");
                td.setDescription("CRM/ERP/BPM record metadata");
                td.setBaseId(BaseTypeId.CMIS_SECONDARY); td.setParentId("cmis:secondary");
                td.setCreatable(false); td.setFilable(false); td.setQueryable(true); td.setFulltextIndexed(true);
                td.setIncludedInSupertypeQuery(true); td.setControllablePolicy(false); td.setControllableACL(false);
                td.setTypeMutabilityCreate(true); td.setTypeMutabilityUpdate(true); td.setTypeMutabilityDelete(true);
                td.setProperties(pids);
                try { ts.createTypeDefinition(repositoryId, td); } catch (Exception e) {
                    if (e.getMessage() == null || !e.getMessage().toLowerCase().contains("conflict")) throw e;
                }
            }
            if (patchUtil.getTypeManager() != null) {
                patchUtil.getTypeManager().invalidateTypeCache(repositoryId);
                try { patchUtil.getTypeManager().refreshTypes(); } catch (Exception e) { /* */ }
            }
        } catch (Exception e) { log.error("businessRecordMetadata patch error for " + repositoryId, e); }
    }

    private String mkStr(TypeService ts, String r, String[] p) {
        var c = ts.getPropertyDefinitionCoreByPropertyId(r, p[0]);
        if (c != null) { var d = ts.getPropertyDefinitionDetailByCoreNodeId(r, c.getId()); return d != null && !d.isEmpty() ? d.get(0).getId() : null; }
        NemakiPropertyDefinition pd = new NemakiPropertyDefinition();
        pd.setPropertyId(p[0]); pd.setLocalName(p[1]); pd.setLocalNameSpace(NS); pd.setQueryName(p[0]);
        pd.setDisplayName(p[2]); pd.setDescription(p[3]); pd.setPropertyType(PropertyType.STRING);
        pd.setCardinality(Cardinality.SINGLE); pd.setUpdatability(Updatability.READWRITE);
        pd.setRequired(false); pd.setQueryable(true); pd.setOrderable(true);
        return ts.createPropertyDefinition(r, pd).getId();
    }

    private String mkDt(TypeService ts, String r, String[] p) {
        var c = ts.getPropertyDefinitionCoreByPropertyId(r, p[0]);
        if (c != null) { var d = ts.getPropertyDefinitionDetailByCoreNodeId(r, c.getId()); return d != null && !d.isEmpty() ? d.get(0).getId() : null; }
        NemakiPropertyDefinition pd = new NemakiPropertyDefinition();
        pd.setPropertyId(p[0]); pd.setLocalName(p[1]); pd.setLocalNameSpace(NS); pd.setQueryName(p[0]);
        pd.setDisplayName(p[2]); pd.setDescription(p[3]); pd.setPropertyType(PropertyType.DATETIME);
        pd.setCardinality(Cardinality.SINGLE); pd.setUpdatability(Updatability.READWRITE);
        pd.setRequired(false); pd.setQueryable(true); pd.setOrderable(true);
        return ts.createPropertyDefinition(r, pd).getId();
    }
}
