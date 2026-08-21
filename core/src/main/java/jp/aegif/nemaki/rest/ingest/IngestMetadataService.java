package jp.aegif.nemaki.rest.ingest;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.model.Aspect;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.rest.ingest.mail.MailMessageParser.ParsedMailMessage;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.LinkedHashMap;

/**
 * Handles secondary type attachment for archetype-specific metadata.
 *
 * <p>Extracted from CanonicalImportServiceImpl. Methods return an error
 * string (null on success) to match the existing call-site pattern.
 */
public class IngestMetadataService {

    private static final Logger logger = LoggerFactory.getLogger(IngestMetadataService.class);

    private ContentService contentService;
    private NemakiCachePool nemakiCachePool;

    public void setContentService(ContentService contentService) { this.contentService = contentService; }
    public void setNemakiCachePool(NemakiCachePool nemakiCachePool) { this.nemakiCachePool = nemakiCachePool; }

    /**
     * Apply nemaki:messageMetadata secondary type to a mail message document.
     */
    public String applyMessageMetadata(String repositoryId, String objectId, CallContext callContext,
                                       ParsedMailMessage parsed, ExternalIngestRequest request) {
        try {
            Content content = contentService.getContent(repositoryId, objectId);
            if (content == null) return "Content not found: " + objectId;

            List<Aspect> aspects = content.getAspects();
            if (aspects == null) aspects = new ArrayList<>();

            List<Property> msgProps = buildMessageProperties(parsed, request);

            mergeAspect(aspects, "nemaki:messageMetadata", msgProps);
            ensureSecondaryType(content, "nemaki:messageMetadata");
            content.setAspects(aspects);
            contentService.update(callContext, repositoryId, content);
            invalidateCache(repositoryId, objectId);
            return null;
        } catch (Exception e) {
            logger.warn("Failed to apply messageMetadata to {}: {}", objectId, e.getMessage());
            return "Message metadata failed: " + e.getMessage();
        }
    }

    /**
     * Apply nemaki:noteMetadata secondary type to a note/page document.
     */
    /**
     * Whether {@link #applyNoteMetadata} would write anything for this request.
     *
     * <p>Exists because that method returns {@code null} both when it wrote and when there was
     * nothing to write, so its caller cannot tell a success from a no-op. The capture boundary
     * needs the difference: opening an intent for an operation that changes nothing produces a
     * row that can never be completed, and recording a mutation that never happened puts a
     * fabricated entry in the evidence (design §6.10-B2, external review).
     *
     * <p>Shares the predicate with the method itself rather than restating it, so the two
     * cannot drift apart.
     */
    public boolean willWriteNoteMetadata(ExternalIngestRequest request) {
        return !collectNoteProps(request).isEmpty();
    }

    private List<Property> collectNoteProps(ExternalIngestRequest request) {
        List<Property> noteProps = new ArrayList<>();
        Map<String, Object> meta = request == null ? null : request.getMetadata();
        if (meta != null) {
            addStringProp(noteProps, "nemaki:notePageId", meta.get("pageId"));
            addStringProp(noteProps, "nemaki:notePageUrl", meta.get("pageUrl"));
            addStringProp(noteProps, "nemaki:noteParentPageId", meta.get("parentPageId"));
            addStringProp(noteProps, "nemaki:noteWorkspaceId", meta.get("workspaceId"));
            addStringProp(noteProps, "nemaki:noteAuthor", meta.get("author"));
            addStringProp(noteProps, "nemaki:noteLastEditedBy", meta.get("lastEditedBy"));
        }
        return noteProps;
    }

    /** Whether {@link #applyArchetypeMetadata} would write anything. Same reason as above. */
    public boolean willWriteArchetypeMetadata(ExternalIngestRequest request,
            String[][] fieldMappings) {
        return !collectArchetypeProps(request, fieldMappings).isEmpty();
    }

    private List<Property> collectArchetypeProps(ExternalIngestRequest request,
            String[][] fieldMappings) {
        List<Property> props = new ArrayList<>();
        Map<String, Object> meta = request == null ? null : request.getMetadata();
        if (meta != null && fieldMappings != null) {
            for (String[] mapping : fieldMappings) {
                addStringProp(props, mapping[0], meta.get(mapping[1]));
            }
        }
        return props;
    }

    public String applyNoteMetadata(String repositoryId, String objectId, CallContext callContext,
                                    ExternalIngestRequest request) {
        try {
            Content content = contentService.getContent(repositoryId, objectId);
            if (content == null) return "Content not found: " + objectId;

            List<Aspect> aspects = content.getAspects();
            if (aspects == null) aspects = new ArrayList<>();

            List<Property> noteProps = collectNoteProps(request);

            if (!noteProps.isEmpty()) {
                mergeAspect(aspects, "nemaki:noteMetadata", noteProps);
                ensureSecondaryType(content, "nemaki:noteMetadata");
                content.setAspects(aspects);
                contentService.update(callContext, repositoryId, content);
                invalidateCache(repositoryId, objectId);
            }
            return null;
        } catch (Exception e) {
            logger.warn("Failed to apply noteMetadata to {}: {}", objectId, e.getMessage());
            return "Note metadata failed: " + e.getMessage();
        }
    }

    /**
     * Generic archetype metadata applicator (business record, chat context).
     */
    public String applyArchetypeMetadata(String repositoryId, String objectId, CallContext callContext,
                                         String secondaryTypeId, ExternalIngestRequest request,
                                         String[][] fieldMappings) {
        try {
            Content content = contentService.getContent(repositoryId, objectId);
            if (content == null) return "Content not found: " + objectId;

            List<Aspect> aspects = content.getAspects();
            if (aspects == null) aspects = new ArrayList<>();

            List<Property> props = collectArchetypeProps(request, fieldMappings);
            if (!props.isEmpty()) {
                mergeAspect(aspects, secondaryTypeId, props);
                ensureSecondaryType(content, secondaryTypeId);
                content.setAspects(aspects);
                contentService.update(callContext, repositoryId, content);
                invalidateCache(repositoryId, objectId);
            }
            return null;
        } catch (Exception e) {
            logger.warn("Failed to apply {} to {}: {}", secondaryTypeId, objectId, e.getMessage());
            return secondaryTypeId + " metadata failed: " + e.getMessage();
        }
    }

    // ── Private helpers ──

    /**
     * Build the message metadata property list from parsed EML and request metadata.
     * Uses LinkedHashMap to prevent duplicate keys — metadata values override parsed values.
     */
    static List<Property> buildMessageProperties(ParsedMailMessage parsed, ExternalIngestRequest request) {
        Map<String, Property> propMap = new LinkedHashMap<>();
        if (parsed.messageId() != null) propMap.put("nemaki:internetMessageId", new Property("nemaki:internetMessageId", parsed.messageId()));
        if (parsed.subject() != null) propMap.put("nemaki:mailSubject", new Property("nemaki:mailSubject", parsed.subject()));
        if (parsed.from() != null) propMap.put("nemaki:mailFrom", new Property("nemaki:mailFrom", parsed.from()));
        if (parsed.to() != null) propMap.put("nemaki:mailTo", new Property("nemaki:mailTo", parsed.to()));
        if (parsed.cc() != null) propMap.put("nemaki:mailCc", new Property("nemaki:mailCc", parsed.cc()));
        if (parsed.inReplyTo() != null) propMap.put("nemaki:mailInReplyTo", new Property("nemaki:mailInReplyTo", parsed.inReplyTo()));
        if (parsed.references() != null) propMap.put("nemaki:mailReferences", new Property("nemaki:mailReferences", parsed.references()));
        if (parsed.sentDate() != null) {
            GregorianCalendar cal = new GregorianCalendar();
            cal.setTime(parsed.sentDate());
            propMap.put("nemaki:mailSentAt", new Property("nemaki:mailSentAt", cal));
        }
        if (parsed.receivedDate() != null) {
            GregorianCalendar cal = new GregorianCalendar();
            cal.setTime(parsed.receivedDate());
            propMap.put("nemaki:mailReceivedAt", new Property("nemaki:mailReceivedAt", cal));
        }
        String mailboxId = resolveMetadataString(request, "mailboxId");
        if (mailboxId != null) propMap.put("nemaki:mailboxId", new Property("nemaki:mailboxId", mailboxId));
        String messageStableId = resolveMetadataString(request, "messageStableId");
        if (messageStableId != null) propMap.put("nemaki:messageStableId", new Property("nemaki:messageStableId", messageStableId));
        // Override internetMessageId from metadata if present (e.g., from Graph API)
        String metaInetMsgId = resolveMetadataString(request, "internetMessageId");
        if (metaInetMsgId != null) propMap.put("nemaki:internetMessageId", new Property("nemaki:internetMessageId", metaInetMsgId));
        return new ArrayList<>(propMap.values());
    }

    /** Merge new properties into an existing aspect, or create a new one. */
    private static void mergeAspect(List<Aspect> aspects, String aspectName, List<Property> newProps) {
        Aspect existing = aspects.stream()
                .filter(a -> aspectName.equals(a.getName())).findFirst().orElse(null);
        if (existing != null) {
            Map<String, Property> merged = new LinkedHashMap<>();
            if (existing.getProperties() != null) {
                for (Property p : existing.getProperties()) merged.put(p.getKey(), p);
            }
            for (Property p : newProps) merged.put(p.getKey(), p);
            existing.setProperties(new ArrayList<>(merged.values()));
        } else {
            aspects.add(new Aspect(aspectName, newProps));
        }
    }

    /** Ensure secondaryTypeId is in content's secondaryIds list. */
    private static void ensureSecondaryType(Content content, String secondaryTypeId) {
        List<String> secondaryIds = content.getSecondaryIds();
        if (secondaryIds == null) secondaryIds = new ArrayList<>();
        if (!secondaryIds.contains(secondaryTypeId)) {
            secondaryIds.add(secondaryTypeId);
        }
        content.setSecondaryIds(secondaryIds);
    }

    private void invalidateCache(String repositoryId, String objectId) {
        if (nemakiCachePool != null) {
            try { nemakiCachePool.get(repositoryId).removeCmisAndContentCache(objectId); }
            catch (Exception e) { /* ignore */ }
        }
    }

    static void addStringProp(List<Property> props, String key, Object value) {
        if (value instanceof String s && !s.isBlank()) {
            props.add(new Property(key, s));
        }
    }

    static String resolveMetadataString(ExternalIngestRequest request, String key) {
        if (request.getMetadata() == null) return null;
        Object val = request.getMetadata().get(key);
        return (val instanceof String s && !s.isBlank()) ? s : null;
    }
}
