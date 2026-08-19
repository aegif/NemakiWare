package jp.aegif.nemaki.rest.ingest;

import jp.aegif.nemaki.rest.purview.journal.*;
import jp.aegif.nemaki.util.spring.SpringContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles lineage event emission for ingest operations.
 *
 * <p>Extracted from CanonicalImportServiceImpl. Contains:
 * emitLineageEvent, emitViaJournal, buildCanonicalSourceUri,
 * resolveProcessType, isAttachmentObjectType.
 */
public class IngestLineageEmitter {

    /** See {@link #lastEmissionFailure()}. Thread-scoped: ingest is request-scoped. */
    private static final ThreadLocal<String> lastFailure = new ThreadLocal<>();

    private static final Logger logger = LoggerFactory.getLogger(IngestLineageEmitter.class);

    /**
     * Emit the lineage fact for an imported document.
     *
     * <p>The return value keeps the old contract exactly: the v1 eventId of the stored journal
     * row, or null when nothing was emitted. The id is preset on the {@link
     * LineageFact.LegacyV1Projection} so the projected event carries the same id we return —
     * returning the operation id instead was rejected in review as a compat break (the ingest
     * response's {@code lineageEventId} must resolve against the journal). At the v2 flip the
     * projection dies and this becomes the operation id, which v2 events carry verbatim.
     *
     * @param documentName the created document's cmis:name — the typed CMIS_DOCUMENT endpoint
     *                     requires it, and only the caller that created the document knows it
     * @param operationId  issued by the caller at the start of the import operation (§3) —
     *                     not here, which would stamp it after the mutation already happened
     * @return the v1 eventId of the emitted event, or null when nothing was emitted
     */
    public String emitLineageEvent(String repositoryId, String objectId, String targetFolderId,
                                   String documentName, String operationId,
                                   ConnectorDefinition connector, ExternalIngestRequest request) {
        return emitLineageEvent(repositoryId, objectId, targetFolderId, documentName, operationId,
                connector, request, null, null, null);
    }

    /**
     * @param content what this import can say about the bytes now held — see
     *        {@link CapturedContent}. Recording a digest is what lets a later reader tie the
     *        provenance to a specific set of bytes rather than to an object id, which can be
     *        updated afterwards (P1-1(b)).
     * @param executedBy the authenticated principal that ran this import, or null for scheduled
     *        and webhook ingest which carry no authenticated context. Recorded either way: an
     *        absent agent is itself a fact, and leaving the key out made it look like a delegated
     *        import with a missing name.
     * @param onBehalfOf the authority the import ran under when it differs from the actor — for
     *        a delegated scheduled profile this is the profile creator. The InterPARES A.1
     *        identity attributes ask for a responsible agent, and one field cannot answer both
     *        "who ran it" and "on whose authority".
     */
    public String emitLineageEvent(String repositoryId, String objectId, String targetFolderId,
                                   String documentName, String operationId,
                                   ConnectorDefinition connector, ExternalIngestRequest request,
                                   CapturedContent content, String executedBy, String onBehalfOf) {
        lastFailure.remove();
        try {
            // Two classifications on purpose. The v1 type participates in eventKey and keeps
            // its historical labels — including the CHAT_CONTEXT inversion — while the fact's
            // own type is what v2 calls the operation. See resolveFactProcessType.
            LineageProcessType legacyProcessType = resolveProcessType(connector.getSourceArchetype(), request.getSourceObjectType());
            LineageProcessType factProcessType =
                    resolveFactProcessType(connector.getSourceArchetype(), request.getSourceObjectType());
            String sourceUri = buildCanonicalSourceUri(connector, request);
            String v1EventId = java.util.UUID.randomUUID().toString();

            // The v1 event-level snapshot, conditionals preserved exactly (it rides the legacy
            // projection verbatim; most keys have no v2 home — the endpoint attributes carry
            // sourceSystem and the stable key, and targetFolderId is a §3 Process attribute).
            java.util.Map<String, String> v1Snapshot =
                    buildV1Snapshot(connector, request, targetFolderId, content,
                            executedBy, onBehalfOf);

            // emitReporting, not emitSafely: the plain form collapses "lineage is off" and
            // "we lost the evidence" into the same false, and the document is already committed
            // by the time we get here (external review, P1-1).
            EmitterResolution resolution = resolveEmitterReporting(repositoryId);
            if (resolution.failureReason() != null) {
                lastFailure.set(resolution.failureReason());
                return null;
            }
            LineageFactEmission.EmissionOutcome outcome = LineageFactEmission.emitReporting(
                    resolution.emitter(), () -> {
                String occurredAt = java.time.Instant.now().toString();
                return new LineageFact(
                        repositoryId,
                        factProcessType,
                        operationId,
                        occurredAt,
                        java.util.List.of(LineageEndpoint.externalAsset(
                                repositoryId, sourceUri, connector.getSourceSystem())),
                        java.util.List.of(LineageEndpoint.document(
                                repositoryId, objectId, documentName)),
                        lineageTargets(),
                        request.getCorrelationId(),
                        new LineageFact.LegacyV1Projection(
                                legacyProcessType,
                                java.util.List.of(sourceUri),
                                java.util.List.of(LineageEvent.qualifiedName(repositoryId, objectId)),
                                v1Snapshot,
                                v1EventId));
            }, "repo=" + repositoryId + " op=" + operationId + " type=" + factProcessType);
            if (outcome.failed()) {
                lastFailure.set(outcome.failureReason());
            }
            return outcome.handedOff() ? v1EventId : null;
        } catch (Exception e) {
            // Recorded, not swallowed. The document is already committed at this point, so a
            // lost event means content exists with no provenance — the exact split P1-1 exists
            // to close. Until the outbox lands, the least we owe the caller is the ability to
            // tell "nothing to emit" from "we failed to emit", because a null cannot.
            logger.warn("Failed to emit lineage event for {}: {}", objectId, e.getMessage());
            lastFailure.set(e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Why the most recent {@link #emitLineageEvent} on THIS thread produced no event id, or null
     * when the last call succeeded or simply had nothing to emit.
     *
     * <p>Thread-scoped because ingest is request-scoped: a caller asks about the emission it just
     * performed, and must not see another request's failure. Cleared on every call so a stale
     * failure cannot be reported against a later, successful import.
     *
     * <p>This is a stop-gap with a deliberate boundary: it makes evidence loss VISIBLE, it does
     * not make capture atomic. Atomicity is the outbox in P1-1(a) — content and evidence
     * committed together or not at all — and this method disappears when that lands.
     */
    /**
     * The event-level snapshot, extracted so it can be asserted on directly.
     *
     * <p>Testing this through {@code emitLineageEvent} needs a resolved emitter, and an unwired
     * one fails long before the snapshot is built — which is exactly how an earlier test in this
     * area passed while proving nothing. This is the production builder, called by production.
     */
    java.util.Map<String, String> buildV1Snapshot(ConnectorDefinition connector,
                                                  ExternalIngestRequest request,
                                                  String targetFolderId, CapturedContent content,
                                                  String executedBy, String onBehalfOf) {
        java.util.Map<String, String> v1Snapshot = new java.util.LinkedHashMap<>();
        v1Snapshot.put("sourceSystem", connector.getSourceSystem());
        v1Snapshot.put("sourceArchetype",
                connector.getSourceArchetype() != null ? connector.getSourceArchetype().name() : "");
        v1Snapshot.put("sourceObjectId", request.getSourceObjectId());
        if (request.getSourceObjectType() != null) {
            v1Snapshot.put("sourceObjectType", request.getSourceObjectType());
        }
        if (targetFolderId != null) {
            v1Snapshot.put("targetFolderId", targetFolderId);
        }
        // What was actually captured, so the event stands on its own. An object id alone can
        // be updated later; a digest cannot be, and an agent is required by A.1 (P1-1(b)).
        // contentStored says whether there was content; contentHash carries ONLY a digest.
        // Putting prose in the digest field would make a future consumer either fail validation
        // or invent undocumented prefix parsing, and the evidence report's schema requires hex
        // (external review, P1-1(b)).
        CapturedContent captured = content == null ? CapturedContent.none() : content;
        v1Snapshot.put("contentStored", String.valueOf(captured.stored()));
        if (captured.digest() != null && !captured.digest().isBlank()) {
            v1Snapshot.put("contentHash", captured.digest());
            v1Snapshot.put("contentHashAlgorithm", "SHA-256");
        } else if (captured.stored()) {
            v1Snapshot.put("contentHashUnavailable", captured.digestUnavailableReason() == null
                    ? "this import did not read the stored bytes"
                    : captured.digestUnavailableReason());
        }
        // Two different questions, so two fields. getUsername() on a delegated context returns
        // the profile creator — the authority the import ran UNDER — not the actor that ran it,
        // and scheduled profiles pass no context at all. Collapsing those into one "ingestedBy"
        // made an absent agent and a delegated one look alike (external review, P1-1(b)).
        v1Snapshot.put("executedBy", executedBy == null || executedBy.isBlank()
                ? "service: no authenticated context (scheduled or webhook ingest)" : executedBy);
        if (onBehalfOf != null && !onBehalfOf.isBlank()) {
            v1Snapshot.put("onBehalfOf", onBehalfOf);
        }
        for (String key : new String[]{"workspaceId", "channelId", "channelName", "threadId",
                "messageId", "selectionReason", "evidenceScope",
                "captureWindowStart", "captureWindowEnd"}) {
            String value = resolveMetadataString(request, key);
            if (value != null && !value.isBlank()) {
                v1Snapshot.put("chat." + key, value);
            }
        }

        return v1Snapshot;
    }

    /**
     * What this import can say about the bytes the repository now holds.
     *
     * <p>Three states, not two. "Stored with a digest" and "nothing stored" are the easy ones;
     * the third exists because a check-in with no stream carries the previous version's content
     * forward, so bytes are present that this import never read and therefore cannot hash.
     * Reporting that as "no content" would describe the repository wrongly, and reporting it as
     * hashed would be a lie — so it is its own state, with the reason attached
     * (external review, P1-1(b)).
     */
    public record CapturedContent(boolean stored, String digest, String digestUnavailableReason) {

        public static CapturedContent hashed(String digest) {
            return new CapturedContent(true, digest, null);
        }

        public static CapturedContent none() {
            return new CapturedContent(false, null, null);
        }

        /** Bytes are present but this import did not produce them and did not read them back. */
        public static CapturedContent storedWithoutDigest(String reason) {
            return new CapturedContent(true, null, reason);
        }
    }

    /** Test hook: the retained state is otherwise unobservable, so it cannot be asserted on. */
    void clearLastEmissionFailureForTest() {
        lastFailure.remove();
    }

    public String lastEmissionFailure() {
        return lastFailure.get();
    }

    private java.util.List<String> lineageTargets() {
        try {
            var ctx = SpringContext.getApplicationContext();
            if (ctx == null) return java.util.List.of();
            LineageConfig config = ctx.getBean(LineageConfig.class);
            return config != null ? config.getTargets() : java.util.List.of();
        } catch (Exception e) {
            return java.util.List.of();
        }
    }

    @SuppressWarnings("unchecked")
    /**
     * The emitter, or why there isn't one.
     *
     * <p>Returning a bare null conflated five different situations — lineage explicitly
     * disabled, no Spring context, bean lookup failure, mode resolution failure, emitter
     * construction failure — of which **only the first is benign**. A caller told "no emitter"
     * cannot tell a deliberate configuration from a broken one, and the document is already
     * committed by then (external review, P1-1).
     */
    record EmitterResolution(LineageEmitter emitter, String failureReason) {
        boolean disabled() {
            return emitter == null && failureReason == null;
        }
    }

    private EmitterResolution resolveEmitterReporting(String repositoryId) {
        try {
            var ctx = SpringContext.getApplicationContext();
            if (ctx == null) {
                // NOT benign here. In the ingest path an absent application context means the
                // emitter configuration could not be resolved — it does not establish that
                // lineage was deliberately switched off, and the contract above says only
                // explicit disablement is benign (external review, P1-1).
                return new EmitterResolution(null,
                        "no application context: lineage configuration could not be resolved");
            }
            LineageConfig config = ctx.getBean(LineageConfig.class);
            if (config == null) {
                return new EmitterResolution(null, "lineage configuration bean is absent");
            }
            LineageMode mode = config.getModeForRepository(repositoryId);
            if (mode == LineageMode.DISABLED) {
                return new EmitterResolution(null, null);   // the one benign case
            }
            LineageJournalStore store = ctx.getBean(LineageJournalStore.class);
            java.util.List<LineageTargetSink> sinks = (java.util.List<LineageTargetSink>)
                    (java.util.List<?>) ctx.getBeansOfType(LineageTargetSink.class).values().stream().toList();
            LineageEmitter emitter = config.createEmitterForMode(mode, store, sinks);
            if (emitter == null) {
                return new EmitterResolution(null,
                        "no emitter could be created for lineage mode " + mode);
            }
            return new EmitterResolution(emitter, null);
        } catch (Exception e) {
            logger.warn("Lineage emitter resolution failed (non-fatal): {}", e.getMessage());
            return new EmitterResolution(null,
                    "emitter resolution failed: " + e.getClass().getSimpleName()
                            + ": " + e.getMessage());
        }
    }

    // ── Static helpers ──

    static String buildCanonicalSourceUri(ConnectorDefinition connector, ExternalIngestRequest request) {
        String system = connector.getSourceSystem();
        String tenant = connector.getTenantId();
        String objectId = request.getSourceObjectId();
        SourceArchetype archetype = connector.getSourceArchetype();

        if (archetype == null) {
            return ExternalSourceUri.build(system, tenant, request.getSourceObjectType(), objectId);
        }
        boolean isAttachment = isAttachmentObjectType(request.getSourceObjectType());
        return switch (archetype) {
            case FILE_SHARE -> ExternalSourceUri.forFileShare(system, tenant, objectId);
            case COMPOUND_NOTE -> {
                if (isAttachment) yield ExternalSourceUri.build(system, tenant, "files", objectId);
                yield ExternalSourceUri.forNotePage(system, tenant, objectId);
            }
            case CHAT_CONTEXT -> {
                String channelId = resolveMetadataString(request, "channelId");
                if (isAttachment) {
                    yield ExternalSourceUri.build(system, tenant,
                            "channels/" + channelId + "/files", objectId);
                }
                yield ExternalSourceUri.forChatMessage(system, tenant, channelId, objectId);
            }
            case BUSINESS_RECORD -> ExternalSourceUri.forBusinessRecord(system, tenant,
                    request.getSourceObjectType() != null ? request.getSourceObjectType() : "record", objectId);
            case MESSAGE_CONTEXT -> {
                String mailboxId = resolveMetadataString(request, "mailboxId");
                if (isAttachment) {
                    String msgId = resolveMetadataString(request, "messageStableId");
                    yield ExternalSourceUri.forMailAttachment(system, tenant,
                            mailboxId != null ? mailboxId : "default",
                            msgId != null ? msgId : "unknown", objectId);
                }
                yield ExternalSourceUri.forMailMessage(system, tenant,
                        mailboxId != null ? mailboxId : "default", objectId);
            }
        };
    }

    static boolean isAttachmentObjectType(String sourceObjectType) {
        if (sourceObjectType == null) return false;
        String lower = sourceObjectType.toLowerCase();
        return "attachment".equals(lower) || "file".equals(lower);
    }

    /**
     * The v2 classification of this ingest — what the operation actually is.
     *
     * <p>It differs from {@link #resolveProcessType} (whose labels are frozen into every v1
     * eventKey) in exactly two places, both v2.3.13 confirmed corrections:
     * <ul>
     *   <li>null archetype, non-attachment: {@code GENERIC_EXTERNAL_INGEST}, not
     *       {@code IMPORT_UPLOADED} — unclassified connector ingest is not a user upload;</li>
     *   <li>{@code CHAT_CONTEXT}: the v1 branch is inverted (a real attachment became the
     *       generic type, a message became the attachment type). v2 classifies an attachment
     *       as {@code CHAT_ATTACHMENT_IMPORT} and a message as {@code CHAT_MESSAGE_IMPORT},
     *       matching the {@code MESSAGE_CONTEXT} pattern.</li>
     * </ul>
     */
    static LineageProcessType resolveFactProcessType(SourceArchetype archetype, String sourceObjectType) {
        boolean isAttachment = isAttachmentObjectType(sourceObjectType);
        if (archetype == null) {
            return isAttachment ? LineageProcessType.EXTERNAL_ATTACHMENT_IMPORT
                    : LineageProcessType.GENERIC_EXTERNAL_INGEST;
        }
        if (archetype == SourceArchetype.CHAT_CONTEXT) {
            return isAttachment ? LineageProcessType.CHAT_ATTACHMENT_IMPORT
                    : LineageProcessType.CHAT_MESSAGE_IMPORT;
        }
        return resolveProcessType(archetype, sourceObjectType);
    }

    static LineageProcessType resolveProcessType(SourceArchetype archetype, String sourceObjectType) {
        boolean isAttachment = isAttachmentObjectType(sourceObjectType);
        if (archetype == null) {
            return isAttachment ? LineageProcessType.EXTERNAL_ATTACHMENT_IMPORT : LineageProcessType.IMPORT_UPLOADED;
        }
        return switch (archetype) {
            case FILE_SHARE -> LineageProcessType.FILE_SHARE_SYNC_DOWNLOAD;
            case COMPOUND_NOTE -> isAttachment
                    ? LineageProcessType.EXTERNAL_ATTACHMENT_IMPORT
                    : LineageProcessType.EXTERNAL_NOTE_IMPORT;
            case CHAT_CONTEXT -> isAttachment
                    ? LineageProcessType.EXTERNAL_ATTACHMENT_IMPORT
                    : LineageProcessType.CHAT_ATTACHMENT_IMPORT;
            case BUSINESS_RECORD -> LineageProcessType.BUSINESS_RECORD_IMPORT;
            case MESSAGE_CONTEXT -> isAttachment
                    ? LineageProcessType.MAIL_ATTACHMENT_IMPORT
                    : LineageProcessType.MAIL_MESSAGE_IMPORT;
        };
    }

    private static String resolveMetadataString(ExternalIngestRequest request, String key) {
        if (request.getMetadata() != null) {
            Object val = request.getMetadata().get(key);
            if (val instanceof String s && !s.isBlank()) return s;
        }
        return null;
    }
}
