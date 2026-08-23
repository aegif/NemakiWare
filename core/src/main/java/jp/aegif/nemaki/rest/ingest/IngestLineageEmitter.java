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
    /**
     * @param content what this import can say about the bytes now held — see
     *        {@link CapturedContent}. Recording a digest is what lets a later reader tie the
     *        provenance to a specific set of bytes rather than to an object id, which can be
     *        updated afterwards (P1-1(b)).
     * @param executedBy the authenticated principal that ran this import; null for webhook and
     *        other unauthenticated ingest. A DELEGATED scheduled profile is not null — its context
     *        is synthesized from the creator, so the caller passes an explicit "unknown:" string
     *        rather than that person's name. Recorded either way: an absent agent is itself a
     *        fact, and leaving the key out made it look like a delegated import with a missing
     *        name.
     * @param onBehalfOf the authority the import ran under when it differs from the actor — for
     *        a delegated scheduled profile this is the profile creator. The InterPARES A.1
     *        identity attributes ask for a responsible agent, and one field cannot answer both
     *        "who ran it" and "on whose authority".
     */
    public String emitLineageEvent(String repositoryId, String objectId, String targetFolderId,
                                   String documentName, String operationId,
                                   ConnectorDefinition connector, ExternalIngestRequest request,
                                   CapturedContent content, String executedBy, String onBehalfOf) {
        return emitLineageEvent(repositoryId, objectId, targetFolderId, documentName, operationId,
                connector, request, content, executedBy, onBehalfOf, java.util.Map.of());
    }

    /**
     * @param passOutcome extra v1 snapshot entries describing what THIS pass did, for a pass that
     *        stored nothing. Empty for an ordinary capture, where the event's own existence and
     *        its endpoints already say what happened. See {@code CaptureEvidenceField
     *        .REIMPORT_OUTCOME} for why these are not endpoint attributes.
     */
    public String emitLineageEvent(String repositoryId, String objectId, String targetFolderId,
                                   String documentName, String operationId,
                                   ConnectorDefinition connector, ExternalIngestRequest request,
                                   CapturedContent content, String executedBy, String onBehalfOf,
                                   java.util.Map<CaptureEvidenceField, String> passOutcome) {
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
                            executedBy, onBehalfOf, passOutcome);

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
                        java.util.List.of(buildInputEndpoint(repositoryId, sourceUri,
                                connector, request)),
                        java.util.List.of(buildOutputEndpoint(repositoryId, objectId,
                                documentName, content)),
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
     * What this import can say about the bytes the repository now holds.
     *
     * <p>Three states, not two. "Stored with a digest" and "nothing stored" are the easy ones;
     * the third exists because a check-in with no stream carries the previous version's content
     * forward, so the object goes on REFERENCING content this import neither supplied nor read.
     * Reporting that as "no content" would describe the repository wrongly, and reporting it as
     * hashed would be a lie — but "stored" is not available either: the reference is logical,
     * and nothing cheap here establishes that the bytes are physically present and readable
     * (external review). So it is its own state, "undetermined", with the reason attached.
     * Establishing it is fixity work, P1-2.
     */
    public record CapturedContent(ContentState state, String digest, String reason,
                                  DigestSubject digestSubject) {

        /** Whether bytes are held — and the case where we could not find out. */
        public enum ContentState { STORED, NONE, UNKNOWN }

        /**
         * What a recorded digest is a digest OF (P1-1(d) R3).
         *
         * <p>There is no {@code STORED} member, and that is the point. The digest is computed
         * from the bytes the connector fetched, immediately before they are handed to
         * {@code createDocument} — nothing reads back what the repository ended up holding. A
         * bare {@code contentHash} beside a {@code contentStored=true} reads as though it did,
         * which is the substitution this whole work item exists to avoid. Adding a member here
         * requires a path that actually re-reads and re-hashes: fixity, P1-2.
         */
        public enum DigestSubject {
            /** The bytes this import fetched and supplied. */
            INPUT("input"),
            /**
             * Those bytes, AND their digest equalled the one already recorded for this object.
             *
             * <p>Weaker than fixity — the comparison is against a mutable aspect property, not
             * against the stored bytes — but strictly more than {@link #INPUT}, and it used to be
             * thrown away: the unchanged-content branch reported "neither supplied nor verified"
             * about a pass that had done both (external review, P1-1(d) D2).
             */
            INPUT_MATCHED_RECORDED("input-matched-recorded");

            private final String wireValue;

            DigestSubject(String wireValue) {
                this.wireValue = wireValue;
            }

            public String wireValue() {
                return wireValue;
            }
        }

        public static CapturedContent hashed(String digest) {
            return new CapturedContent(ContentState.STORED, digest, null, DigestSubject.INPUT);
        }

        /**
         * Adds "and its digest equalled the one already recorded" to whatever was observed.
         *
         * <p>Deliberately NOT a {@code hashed(...)} sibling. On the branch that reaches this,
         * nothing was stored by this pass, so claiming {@code STORED} would be an inference; the
         * observed state — read back, possibly {@code NONE}, possibly {@code UNKNOWN} — is kept
         * exactly as it was and only the digest and its subject are added. An earlier attempt
         * short-circuited the read-back entirely and turned an observed "no content" into an
         * inferred "undetermined", which traded evidence for a claim (external review).
         */
        public CapturedContent withMatchedInputDigest(String digest, String note) {
            if (digest == null || digest.isBlank()) {
                return this;
            }
            String combined = reason == null ? note : reason + " " + note;
            return new CapturedContent(state, digest, combined,
                    DigestSubject.INPUT_MATCHED_RECORDED);
        }

        public static CapturedContent none() {
            return new CapturedContent(ContentState.NONE, null, null, null);
        }

        // storedWithoutDigest(reason) was here: "bytes ARE held, we just did not hash them".
        // It is gone because nothing could establish it. The only caller was the carried-forward
        // case, and every cheap way to confirm those bytes turned out not to confirm anything
        // (see CanonicalImportServiceImpl.describeCapturedContent). Leaving the factory in place
        // would invite the claim back without the evidence — that case is unknown(), and proving
        // it is fixity work (P1-2).


        /**
         * We could not find out. Kept distinct from {@link #none()} because "nothing is stored"
         * is a positive claim: a transient read failure followed by a successful check-in would
         * otherwise assert emptiness over bytes that are actually there (external review).
         */
        public static CapturedContent unknown(String reason) {
            return new CapturedContent(ContentState.UNKNOWN, null, reason, null);
        }
    }

    /**
     * The wire value for a digest's subject, defaulting to the weakest true one.
     *
     * <p>A digest with no subject recorded would be exactly the bare digest R3 forbids, so the
     * absence of an explicit subject means {@code input} — never nothing, and never "stored".
     */
    static String digestSubjectValue(CapturedContent captured) {
        CapturedContent.DigestSubject subject = captured.digestSubject();
        return (subject == null ? CapturedContent.DigestSubject.INPUT : subject).wireValue();
    }

    /**
     * The v1 keys present in this snapshot whose values nothing verified.
     *
     * <p>Sorted, not in declaration order. The point of putting this on the event is that it
     * stays legible when the enum has changed; ordering by the enum would make a future
     * reordering silently rewrite the string on every event and show diffs that are not changes
     * (external review).
     */
    static String assertedKeysIn(java.util.Map<String, String> v1Snapshot) {
        java.util.SortedSet<String> keys = new java.util.TreeSet<>();
        for (CaptureEvidenceField field : CaptureEvidenceField.values()) {
            if (field.assurance() != CaptureEvidenceField.Assurance.ASSERTED) continue;
            String value = v1Snapshot.get(field.v1Key());
            // Blank counts as absent, as it does everywhere else that reads this map. Naming a
            // key whose value is empty would report a claim the caller never made.
            if (value == null || value.isBlank()) continue;
            keys.add(field.v1Key());
        }
        return String.join(",", keys);
    }

    /** Test hook: the retained state is otherwise unobservable, so it cannot be asserted on. */
    void clearLastEmissionFailureForTest() {
        lastFailure.remove();
    }

    /**
     * The event-level snapshot, extracted so it can be asserted on directly.
     *
     * <p><b>v1 only, and that is a real limitation.</b> This map rides the legacy v1 projection,
     * which {@code LineageFact} documents as having no v2 home. So the facts added here —
     * content state, digest, actor, chat context — are carried by v1 events and would be lost
     * at the v2 write flip. Giving them a typed v2 representation is outstanding work, recorded
     * in authenticity-roadmap.md under P1-1(b); until then an evidence report must not be built
     * on this schema as though it were durable (external review).
     *
     * <p>Testing this through {@code emitLineageEvent} needs a resolved emitter, and an unwired
     * one fails long before the snapshot is built — which is exactly how an earlier test in this
     * area passed while proving nothing. This is the production builder, called by production.
     */
    /**
     * The input endpoint: what was ingested, as identified, plus the source facts its identity
     * does not already state.
     *
     * <p>Extracted for the same reason {@code buildV1Snapshot} is: the correspondence between
     * the two encodings can only be asserted if both can be built from the same inputs. Left
     * inline in the emitter's lambda, a test would have to resolve an emitter first, and an
     * unwired one fails long before either is built — which is how an earlier test in this area
     * passed while proving nothing (P1-1(b)).
     */
    LineageEndpoint buildInputEndpoint(String repositoryId, String sourceUri,
                                       ConnectorDefinition connector,
                                       ExternalIngestRequest request) {
        java.util.Map<String, Object> extra = new java.util.LinkedHashMap<>();
        putIfPresent(extra, CaptureEvidenceField.SOURCE_OBJECT_TYPE,
                request.getSourceObjectType());
        putIfPresent(extra, CaptureEvidenceField.CHAT_WORKSPACE_ID,
                resolveMetadataString(request, "workspaceId"));
        putIfPresent(extra, CaptureEvidenceField.CHAT_MESSAGE_ID,
                resolveMetadataString(request, "messageId"));
        putIfPresent(extra, CaptureEvidenceField.CHAT_THREAD_ID,
                resolveMetadataString(request, "threadId"));
        return LineageEndpoint.externalAsset(repositoryId, sourceUri,
                connector.getSourceSystem(), extra);
    }

    /** The output endpoint: the document, and what this ingest can say about its content. */
    LineageEndpoint buildOutputEndpoint(String repositoryId, String objectId, String documentName,
                                        CapturedContent content) {
        CapturedContent captured = content == null
                ? CapturedContent.unknown("the caller supplied no content state") : content;
        java.util.Map<String, Object> attributes = new java.util.LinkedHashMap<>();
        attributes.put("name", documentName == null || documentName.isBlank()
                ? objectId : documentName);
        putIfPresent(attributes, CaptureEvidenceField.CONTENT_STORED,
                contentStoredValue(captured));
        if (captured.digest() != null && !captured.digest().isBlank()) {
            putIfPresent(attributes, CaptureEvidenceField.CONTENT_HASH, captured.digest());
            putIfPresent(attributes, CaptureEvidenceField.CONTENT_HASH_ALGORITHM, "SHA-256");
            // Never emitted without its subject: a digest that does not say what it is of reads
            // as a digest of the stored bytes, which nothing here checked (P1-1(d) R3).
            putIfPresent(attributes, CaptureEvidenceField.CONTENT_HASH_SUBJECT,
                    digestSubjectValue(captured));
        }
        // Outside the else, not inside it. A digest and an undetermined content state can now
        // occur together — the matched-digest case has both — and putting the reason in the else
        // dropped it exactly there (external review).
        putIfPresent(attributes, CaptureEvidenceField.CONTENT_HASH_UNAVAILABLE, captured.reason());
        return LineageEndpoint.document(repositoryId, objectId, attributes);
    }

    /**
     * Writes a fact under the v2 attribute name the table gives it.
     *
     * <p>Going through the table rather than a literal is the point: a fact added to the v1
     * snapshot and forgotten here is what this work exists to prevent, and the table is what
     * makes the two sides impossible to drift apart rather than merely testable.
     */
    private static void putIfPresent(java.util.Map<String, Object> target,
                                     CaptureEvidenceField field, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        CaptureEvidenceField.V2Home home = field.v2Home();
        if (home.attributeName() == null) {
            throw new IllegalStateException(field + " has no v2 attribute name; the table says "
                    + home.placement() + ". Writing it as an endpoint attribute would contradict "
                    + "the table that the correspondence test reads.");
        }
        target.put(home.attributeName(), value);
    }

    /** The three-state, as the string both encodings use. Never inferred from the digest. */
    static String contentStoredValue(CapturedContent captured) {
        return switch (captured.state()) {
            case STORED -> "true";
            case NONE -> "false";
            case UNKNOWN -> "unknown";
        };
    }

    java.util.Map<String, String> buildV1Snapshot(ConnectorDefinition connector,
                                                  ExternalIngestRequest request,
                                                  String targetFolderId, CapturedContent content,
                                                  String executedBy, String onBehalfOf) {
        return buildV1Snapshot(connector, request, targetFolderId, content, executedBy, onBehalfOf,
                java.util.Map.of());
    }

    /**
     * @param passOutcome facts about THIS pass rather than about the request — what a re-import
     *        filled or refused. Built here rather than merged by the caller so that every key the
     *        evidence table declares is produced by this one method, which is what
     *        {@code IngestEvidenceCorrespondenceTest} quantifies over.
     */
    java.util.Map<String, String> buildV1Snapshot(ConnectorDefinition connector,
                                                  ExternalIngestRequest request,
                                                  String targetFolderId, CapturedContent content,
                                                  String executedBy, String onBehalfOf,
                                                  java.util.Map<CaptureEvidenceField, String> passOutcome) {
        java.util.Map<String, String> v1Snapshot = new java.util.LinkedHashMap<>();
        // Keys come from CaptureEvidenceField, not from literals: the same table drives the v2
        // endpoint attributes, so a fact cannot be added to one encoding and forgotten in the
        // other (P1-1(b)). Presence conditions are unchanged — this rides the legacy projection
        // verbatim and a key that used to be absent must stay absent.
        v1Snapshot.put(CaptureEvidenceField.SOURCE_SYSTEM.v1Key(), connector.getSourceSystem());
        v1Snapshot.put(CaptureEvidenceField.SOURCE_ARCHETYPE.v1Key(),
                connector.getSourceArchetype() != null ? connector.getSourceArchetype().name() : "");
        v1Snapshot.put(CaptureEvidenceField.SOURCE_OBJECT_ID.v1Key(), request.getSourceObjectId());
        if (request.getSourceObjectType() != null) {
            v1Snapshot.put(CaptureEvidenceField.SOURCE_OBJECT_TYPE.v1Key(),
                    request.getSourceObjectType());
        }
        if (targetFolderId != null) {
            v1Snapshot.put(CaptureEvidenceField.TARGET_FOLDER_ID.v1Key(), targetFolderId);
        }
        // What was actually captured, so the event stands on its own. An object id alone can
        // be updated later; a digest cannot be, and an agent is required by A.1 (P1-1(b)).
        // contentStored says whether there was content; contentHash carries ONLY a digest.
        // Putting prose in the digest field would make a future consumer either fail validation
        // or invent undocumented prefix parsing, and the evidence report's schema requires hex
        // (external review, P1-1(b)).
        CapturedContent captured = content == null
                ? CapturedContent.unknown("the caller supplied no content state") : content;
        v1Snapshot.put(CaptureEvidenceField.CONTENT_STORED.v1Key(), contentStoredValue(captured));
        if (captured.digest() != null && !captured.digest().isBlank()) {
            v1Snapshot.put(CaptureEvidenceField.CONTENT_HASH.v1Key(), captured.digest());
            v1Snapshot.put(CaptureEvidenceField.CONTENT_HASH_ALGORITHM.v1Key(), "SHA-256");
            v1Snapshot.put(CaptureEvidenceField.CONTENT_HASH_SUBJECT.v1Key(),
                    digestSubjectValue(captured));
        }
        if (captured.reason() != null) {
            v1Snapshot.put(CaptureEvidenceField.CONTENT_HASH_UNAVAILABLE.v1Key(),
                    captured.reason());
        }
        // Two different questions, so two fields. getUsername() on a delegated context returns
        // the profile creator — the authority the import ran UNDER — not the actor that ran it,
        // and unauthenticated ingest has no actor to name at all. Collapsing those into one
        // "ingestedBy" made an absent agent and a delegated one look alike (external review,
        // P1-1(b)).
        if (executedBy == null || executedBy.isBlank()) {
            // P1-1(e) retired the "service: no authenticated context" fallback: every entry
            // path now resolves a LineageExecutionAttribution (the scheduler and webhook are
            // named actors, not absences), so a blank here is a caller bug — and a THIRD
            // vocabulary for the same fact was itself the D7 defect (Codex H2).
            throw new IllegalArgumentException("executedBy must be resolved before emission —"
                    + " use resolveExecutionAttribution");
        }
        v1Snapshot.put(CaptureEvidenceField.EXECUTED_BY.v1Key(), executedBy);
        if (onBehalfOf != null && !onBehalfOf.isBlank()) {
            v1Snapshot.put(CaptureEvidenceField.ON_BEHALF_OF.v1Key(), onBehalfOf);
        }
        // participants was stored on the object but left out of the evidence, so the record
        // said which channel a message came from without saying who was in it (external review).
        // NOT here: nemaki:chatCapturedAt. It is stamped AFTER the import returns, so at this
        // point it does not exist yet — the one of the eleven this event cannot repeat.
        //
        // Moving the stamp ahead of emission does NOT fix that; it was retracted in
        // p1-1b-v2-evidence-home.md §8, because the aspect it stamps into is created by the
        // wrapper after execute() returns, so an earlier stamp hits nothing. What the other ten
        // below carry is the REQUESTED value, read from the same request metadata the wrapper
        // reads — not a confirmation that it reached the object (P1-1(d) D1).
        for (CaptureEvidenceField field : new CaptureEvidenceField[]{
                CaptureEvidenceField.CHAT_WORKSPACE_ID, CaptureEvidenceField.CHAT_CHANNEL_ID,
                CaptureEvidenceField.CHAT_CHANNEL_NAME, CaptureEvidenceField.CHAT_THREAD_ID,
                CaptureEvidenceField.CHAT_MESSAGE_ID, CaptureEvidenceField.CHAT_PARTICIPANTS,
                CaptureEvidenceField.CHAT_SELECTION_REASON,
                CaptureEvidenceField.CHAT_EVIDENCE_SCOPE,
                CaptureEvidenceField.CHAT_CAPTURE_WINDOW_START,
                CaptureEvidenceField.CHAT_CAPTURE_WINDOW_END}) {
            // The metadata key is the v1 key without the "chat." prefix — the same string the
            // caller supplies.
            String metadataKey = field.v1Key().substring("chat.".length());
            String value = resolveMetadataString(request, metadataKey);
            if (value != null && !value.isBlank()) {
                v1Snapshot.put(field.v1Key(), value);
            }
        }

        // Last, and from the argument rather than from the request: these describe what the pass
        // decided, not what the caller asked for.
        passOutcome.forEach((field, value) -> {
            if (value != null && !value.isBlank()) {
                v1Snapshot.put(field.v1Key(), value);
            }
        });

        // Finally, the event says which of its own facts nothing verified (P1-1(d) R2). Derived
        // from what is actually in this snapshot, not from the table's full list: a key that is
        // absent was not claimed, and naming it would assert a claim nobody made.
        String asserted = assertedKeysIn(v1Snapshot);
        if (!asserted.isEmpty()) {
            v1Snapshot.put(CaptureEvidenceField.ASSURANCE_ASSERTED.v1Key(), asserted);
        }

        return v1Snapshot;
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
                // DISABLED is benign ONLY when we could actually read the configuration. The read
                // swallows every failure and returns an empty configuration, which falls through
                // to the startup default — so a CouchDB outage produces the same answer as a
                // deliberate switch-off, and this is the one branch that says nothing at all.
                // Worse, the cached layer used to keep that empty configuration for the life of
                // the JVM (external review).
                if (config.configurationReadFailed()) {
                    return new EmitterResolution(null,
                            "lineage is reported as disabled, but the configuration could not be "
                                    + "read — this does NOT establish that lineage was switched "
                                    + "off");
                }
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
