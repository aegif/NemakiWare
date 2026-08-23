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
 * nemaki:chatContextMetadata — Slack/Teams/chat conversation context metadata.
 */
public class Patch_ChatContextMetadataSecondaryType extends AbstractNemakiPatch {
    private static final Log log = LogFactory.getLog(Patch_ChatContextMetadataSecondaryType.class);
    private static final String NS = "http://www.aegif.jp/NEMAKI";

    /** Public so the type↔property association is mechanically checkable (evidence-types §2.2). */
    public static final String TYPE_ID = "nemaki:chatContextMetadata";

    // Class-level (were method-local) so the id lists below DERIVE from the same literals the
    // patch creates from — the association used to live only in this method's body, which is
    // exactly what made the containment test unwritable (EvidenceTypes javadoc, plan §6).
    private static final String[][] STRING_PROPERTY_DEFS = {
        {"nemaki:chatWorkspaceId", "chatWorkspaceId", "Workspace ID", "Slack/Teams workspace/tenant identifier"},
        {"nemaki:chatChannelId", "chatChannelId", "Channel ID", "Channel identifier"},
        {"nemaki:chatChannelName", "chatChannelName", "Channel Name", "Channel display name"},
        {"nemaki:chatThreadId", "chatThreadId", "Thread ID", "Thread/conversation identifier"},
        {"nemaki:chatMessageId", "chatMessageId", "Message ID", "Anchor message identifier"},
        {"nemaki:chatParticipants", "chatParticipants", "Participants", "Comma-separated participant names/IDs"},
        {"nemaki:chatSelectionReason", "chatSelectionReason", "Selection Reason", "Why this conversation was captured"},
        {"nemaki:chatEvidenceScope", "chatEvidenceScope", "Evidence Scope", "Scope of captured evidence: message, thread, channel_window"},
    };
    private static final String[][] DATETIME_PROPERTY_DEFS = {
        {"nemaki:chatCapturedAt", "chatCapturedAt", "Captured At", "When the conversation was captured"},
        {"nemaki:chatCaptureWindowStart", "chatCaptureWindowStart", "Capture Window Start", "Start of evidence capture time window"},
        {"nemaki:chatCaptureWindowEnd", "chatCaptureWindowEnd", "Capture Window End", "End of evidence capture time window"},
    };

    /** The 8 string property ids this patch declares — derived, so it cannot drift. */
    public static final List<String> STRING_PROPERTY_IDS = idsOf(STRING_PROPERTY_DEFS);
    /** The 3 datetime property ids this patch declares — derived, so it cannot drift. */
    public static final List<String> DATETIME_PROPERTY_IDS = idsOf(DATETIME_PROPERTY_DEFS);

    private static List<String> idsOf(String[][] defs) {
        List<String> ids = new ArrayList<>(defs.length);
        for (String[] def : defs) {
            ids.add(def[0]);
        }
        return List.copyOf(ids);
    }

    @Override public String getName() { return "chat-context-metadata-20260403"; }
    @Override protected void applySystemPatch() { }

    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        try {
            TypeService ts = patchUtil.getTypeService();
            if (ts == null) return;
            NemakiTypeDefinition existing = ts.getTypeDefinition(repositoryId, TYPE_ID);
            List<String> pids = new ArrayList<>();

            for (String[] p : STRING_PROPERTY_DEFS) { String id = mkStr(ts, repositoryId, p); if (id != null) pids.add(id); }
            for (String[] p : DATETIME_PROPERTY_DEFS) { String id = mkDt(ts, repositoryId, p); if (id != null) pids.add(id); }

            if (existing != null) {
                List<String> ep = existing.getProperties() != null ? existing.getProperties() : new ArrayList<>();
                boolean u = false;
                for (String pid : pids) { if (!ep.contains(pid)) { ep.add(pid); u = true; } }
                if (u) { existing.setProperties(ep); ts.updateTypeDefinition(repositoryId, existing); }
            } else {
                NemakiTypeDefinition td = new NemakiTypeDefinition();
                td.setTypeId(TYPE_ID); td.setLocalName("chatContextMetadata"); td.setLocalNameSpace(NS);
                td.setQueryName(TYPE_ID); td.setDisplayName("Chat Context Metadata");
                td.setDescription("Chat/messaging conversation context metadata (Slack, Teams, etc.)");
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
        } catch (Exception e) { log.error("chatContextMetadata patch error for " + repositoryId, e); }
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
