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

    /**
     * What a fill-only pass did, so the caller can say so rather than guess.
     *
     * <p>A key the request repeats with the SAME value appears in neither list. That is the
     * ordinary case — a poller re-sending what it sent last time — and it is neither a change nor
     * a refusal, so there is nothing to report and nothing to record. Counting it as "kept" would
     * put a warning on every poll and, worse, would make the re-import event of P1-1(d) R5(B)
     * fire once per poll per object.
     *
     * @param error    the same string {@code applyArchetypeMetadata} would have returned, or null
     * @param filled   keys that were absent and have now been written
     * @param refused  keys where the request carried a DIFFERENT value that was not applied
     */
    public record FillOutcome(String error, List<String> filled, List<String> refused) {
        public boolean wroteAnything() {
            return error == null && !filled.isEmpty();
        }

        /** Whether anything happened that a record should exist for. */
        public boolean isWorthRecording() {
            return !filled.isEmpty() || !refused.isEmpty();
        }
    }

    /**
     * Writes only the archetype properties the object does not already have.
     *
     * <h2>Why a separate operation</h2>
     *
     * <p>{@code applyArchetypeMetadata} overwrites: {@code mergeAspect} puts the request's value
     * over whatever is stored. That is right for a capture, which emits a lineage event saying so.
     * It is wrong on the dedupe/idempotency skip path, because those return <b>before</b> the
     * emit, so the same call rewrites eleven properties that P1-1(c) made read-only through CMIS
     * and leaves no record that it happened — polling the same source object a second time is
     * enough (external review, P1-1(d) D6).
     *
     * <p>The rule this encodes: <b>evidence is not changed without a record.</b> Filling a gap is
     * not a change — it is the retry the fall-through exists for, and it is what lets a field that
     * failed to write the first time land on the second attempt.
     *
     * <p>A stored value that is null or blank counts as absent. A write that half-succeeded should
     * still be repairable; only a real value is protected.
     */
    /**
     * @param beforeFirstWrite runs after this method has decided, from ITS OWN read, that it
     *        will write — and before the write. The capture intent opens here, not from a
     *        separate preflight: the first shape read the object twice (a willFill preflight,
     *        then this method), and the two reads could disagree. One direction opened an
     *        intent for a pass that then wrote nothing; the other — a preflight that saw
     *        nothing missing while this method's read then did — WROTE WITHOUT AN OPEN INTENT,
     *        which is the one state the whole boundary exists to prevent (external review).
     *        One read, one decision, and the hook sits between decision and write.
     */
    public FillOutcome fillMissingArchetypeMetadata(String repositoryId, String objectId,
                                                    CallContext callContext, String secondaryTypeId,
                                                    ExternalIngestRequest request,
                                                    String[][] fieldMappings,
                                                    Runnable beforeFirstWrite) {
        return fillMissingProperties(repositoryId, objectId, callContext, secondaryTypeId,
                collectArchetypeProps(request, fieldMappings), beforeFirstWrite);
    }

    /** As above, for {@code nemaki:messageMetadata} — mail's skip pass (D-7). */
    public FillOutcome fillMissingMessageMetadata(String repositoryId, String objectId,
            CallContext callContext, ParsedMailMessage parsed, ExternalIngestRequest request,
            Runnable beforeFirstWrite) {
        return fillMissingProperties(repositoryId, objectId, callContext,
                "nemaki:messageMetadata", buildMessageProperties(parsed, request),
                beforeFirstWrite);
    }

    /** As above, for {@code nemaki:noteMetadata} — the note page's skip pass (D-7). */
    public FillOutcome fillMissingNoteMetadata(String repositoryId, String objectId,
            CallContext callContext, ExternalIngestRequest request, Runnable beforeFirstWrite) {
        return fillMissingProperties(repositoryId, objectId, callContext,
                "nemaki:noteMetadata", collectNoteProps(request), beforeFirstWrite);
    }

    /**
     * The one fill implementation every archetype's skip pass uses — extracted when D6's rule
     * ("evidence is not changed without a record") was extended beyond chat (plan D-7), so the
     * fill/refuse semantics cannot drift per archetype.
     */
    private FillOutcome fillMissingProperties(String repositoryId, String objectId,
                                              CallContext callContext, String secondaryTypeId,
                                              List<Property> requested,
                                              Runnable beforeFirstWrite) {
        try {
            Content content = contentService.getContent(repositoryId, objectId);
            if (content == null) {
                return new FillOutcome("Content not found: " + objectId, List.of(), List.of());
            }
            List<Aspect> aspects = content.getAspects();
            if (aspects == null) aspects = new ArrayList<>();

            Map<String, Object> present = presentValues(aspects, secondaryTypeId);
            List<Property> missing = new ArrayList<>();
            List<String> refused = new ArrayList<>();
            for (Property p : requested) {
                if (!present.containsKey(p.getKey())) {
                    missing.add(p);
                } else if (!Objects.equals(present.get(p.getKey()), p.getValue())) {
                    refused.add(p.getKey());
                }
                // else: same value, said twice. Nothing happened.
            }
            if (missing.isEmpty()) {
                return new FillOutcome(null, List.of(), refused);
            }
            if (beforeFirstWrite != null) {
                beforeFirstWrite.run();
            }
            mergeAspect(aspects, secondaryTypeId, missing);
            ensureSecondaryType(content, secondaryTypeId);
            content.setAspects(aspects);
            contentService.update(callContext, repositoryId, content);
            invalidateCache(repositoryId, objectId);
            return new FillOutcome(null, missing.stream().map(Property::getKey).toList(), refused);
        } catch (jp.aegif.nemaki.rest.ingest.capture.CaptureScope.CaptureIntentFailedException failClosed) {
            // The hook's refusal to open the intent is fail-closed and must stay that way.
            // Folding it into the FillOutcome error string would swallow the one exception this
            // boundary relies on — the inner catch is exactly where such a swallow hides
            // (fail-open-boundary-trap).
            throw failClosed;
        } catch (Exception e) {
            logger.warn("Failed to fill {} on {}: {}", secondaryTypeId, objectId, e.getMessage());
            return new FillOutcome(secondaryTypeId + " metadata failed: " + e.getMessage(),
                    List.of(), List.of());
        }
    }

    /** The real (non-blank) values the aspect already holds, by key. */
    private static Map<String, Object> presentValues(List<Aspect> aspects, String aspectName) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Aspect a : aspects) {
            if (!aspectName.equals(a.getName()) || a.getProperties() == null) continue;
            for (Property p : a.getProperties()) {
                Object value = p.getValue();
                if (value == null) continue;
                if (value instanceof String s && s.isBlank()) continue;
                values.put(p.getKey(), value);
            }
        }
        return values;
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
