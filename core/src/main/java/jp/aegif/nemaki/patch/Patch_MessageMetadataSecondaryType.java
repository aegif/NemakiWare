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
 * Registers the nemaki:messageMetadata secondary type for mail message
 * envelope properties (subject, from, to, message-id, etc.).
 *
 * <p>Idempotent — skips creation if already exists.
 */
public class Patch_MessageMetadataSecondaryType extends AbstractNemakiPatch {

    private static final Log log = LogFactory.getLog(Patch_MessageMetadataSecondaryType.class);
    private static final String PATCH_NAME = "message-metadata-secondary-type-20260403";
    private static final String NEMAKI_NAMESPACE = "http://www.aegif.jp/NEMAKI";
    private static final String TYPE_ID = "nemaki:messageMetadata";

    @Override
    public String getName() { return PATCH_NAME; }

    @Override
    protected void applySystemPatch() { }

    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        log.info("=== MESSAGE METADATA SECONDARY TYPE PATCH for " + repositoryId + " ===");
        try {
            TypeService ts = patchUtil.getTypeService();
            if (ts == null) { log.error("TypeService not available"); return; }

            NemakiTypeDefinition existing = ts.getTypeDefinition(repositoryId, TYPE_ID);
            List<String> propIds = new ArrayList<>();

            String[][] props = {
                {"nemaki:internetMessageId", "internetMessageId", "Internet Message-ID", "RFC 2822 Message-ID header"},
                {"nemaki:mailSubject", "mailSubject", "Subject", "Email subject line"},
                {"nemaki:mailFrom", "mailFrom", "From", "Sender address"},
                {"nemaki:mailTo", "mailTo", "To", "Recipient addresses"},
                {"nemaki:mailCc", "mailCc", "Cc", "CC addresses"},
                {"nemaki:mailInReplyTo", "mailInReplyTo", "In-Reply-To", "In-Reply-To header"},
                {"nemaki:mailReferences", "mailReferences", "References", "References header"},
                {"nemaki:mailboxId", "mailboxId", "Mailbox ID", "Mailbox identifier (INBOX, folder path, etc.)"},
                {"nemaki:messageStableId", "messageStableId", "Message Stable ID", "Provider-specific stable message identifier"},
            };
            String[][] dateProps = {
                {"nemaki:mailSentAt", "mailSentAt", "Sent At", "Date the message was sent"},
                {"nemaki:mailReceivedAt", "mailReceivedAt", "Received At", "Date the message was received"},
            };

            for (String[] p : props) {
                String id = createStringProp(ts, repositoryId, p[0], p[1], p[2], p[3],
                        !"nemaki:mailTo".equals(p[0]) && !"nemaki:mailCc".equals(p[0])
                        && !"nemaki:mailInReplyTo".equals(p[0]) && !"nemaki:mailReferences".equals(p[0]));
                if (id != null) propIds.add(id);
            }
            for (String[] p : dateProps) {
                String id = createDateTimeProp(ts, repositoryId, p[0], p[1], p[2], p[3]);
                if (id != null) propIds.add(id);
            }

            if (existing != null) {
                List<String> existingProps = existing.getProperties() != null ? existing.getProperties() : new ArrayList<>();
                boolean updated = false;
                for (String pid : propIds) {
                    if (!existingProps.contains(pid)) { existingProps.add(pid); updated = true; }
                }
                if (updated) {
                    existing.setProperties(existingProps);
                    ts.updateTypeDefinition(repositoryId, existing);
                    log.info("Updated " + TYPE_ID + " with new properties");
                }
            } else {
                NemakiTypeDefinition td = new NemakiTypeDefinition();
                td.setTypeId(TYPE_ID);
                td.setLocalName("messageMetadata");
                td.setLocalNameSpace(NEMAKI_NAMESPACE);
                td.setQueryName(TYPE_ID);
                td.setDisplayName("Message Metadata");
                td.setDescription("Mail message envelope metadata (subject, from, to, message-id, etc.)");
                td.setBaseId(BaseTypeId.CMIS_SECONDARY);
                td.setParentId("cmis:secondary");
                td.setCreatable(false);
                td.setFilable(false);
                td.setQueryable(true);
                td.setFulltextIndexed(true);
                td.setIncludedInSupertypeQuery(true);
                td.setControllablePolicy(false);
                td.setControllableACL(false);
                td.setTypeMutabilityCreate(true);
                td.setTypeMutabilityUpdate(true);
                td.setTypeMutabilityDelete(true);
                td.setProperties(propIds);
                try {
                    ts.createTypeDefinition(repositoryId, td);
                    log.info("Created " + TYPE_ID);
                } catch (Exception e) {
                    if (e.getMessage() != null && e.getMessage().toLowerCase().contains("conflict")) {
                        log.info(TYPE_ID + " already exists (conflict)");
                    } else { throw e; }
                }
            }

            if (patchUtil.getTypeManager() != null) {
                patchUtil.getTypeManager().invalidateTypeCache(repositoryId);
                try { patchUtil.getTypeManager().refreshTypes(); } catch (Exception e) { log.warn("Cache refresh: " + e.getMessage()); }
            }
        } catch (Exception e) {
            log.error("Error in messageMetadata patch for " + repositoryId, e);
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
