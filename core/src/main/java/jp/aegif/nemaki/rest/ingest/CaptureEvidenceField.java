/**
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
package jp.aegif.nemaki.rest.ingest;

/**
 * Every fact an ingest records, and where each one lives in v1 and in v2.
 *
 * <h2>Why this is one list and not two builders</h2>
 *
 * <p>The v1 snapshot and the v2 endpoint attributes are two encodings of the same evidence. Kept
 * as separate code, adding a fact to one and forgetting the other is a silent loss — and it is
 * exactly the loss this work exists to prevent, since the v1 half disappears at the write flip.
 * One list, consumed by both, makes the omission impossible rather than detectable.
 *
 * <p>The list is in the product, not in a test. A table duplicated into a test is a second thing
 * to keep in step, and this session has already seen a test pass against a stale copy of a
 * product constant (external review).
 *
 * <h2>{@code V2Home.none} carries a reason, and the reason is checked</h2>
 *
 * <p>"Not carried by v2" and "we have not got to it yet" are different, and a reader six months
 * from now cannot tell them apart from an absent entry. Every {@code none} names the decision
 * and where the work went; {@code CaptureEvidenceFieldTest} rejects placeholder text, so
 * {@code none("TODO")} does not compile past the test.
 *
 * <h2>What this does NOT establish</h2>
 *
 * <p>That a fact has a v2 home says the schema can carry it. It does not say the value is
 * correct, that the delivery target declares the attribute (that is
 * {@code EndpointKindSchemaAlignmentTest}), or that anything writes v2 events yet — nothing
 * does; the v2 write path is Slice 4 and is not started.
 */
public enum CaptureEvidenceField {

    // ── The source, as identified ────────────────────────────────────────────────────────

    SOURCE_SYSTEM("sourceSystem", V2Home.inputAttribute("sourceSystem"), Assurance.CONFIGURED, Disclosure.EXTERNAL_OK),

    /**
     * Already inside {@code externalStableKey} — the source URI is built from it — so the v2
     * home is the endpoint's identity rather than an attribute beside it.
     */
    SOURCE_OBJECT_ID("sourceObjectId", V2Home.identity(
            "carried by externalStableKey: the source URI is built from this id"), Assurance.ASSERTED, Disclosure.EXTERNAL_OK),

    SOURCE_ARCHETYPE("sourceArchetype", V2Home.none(
            "delivered today as nemaki_import_process.importMode, a Process attribute fed from "
                    + "the v1 snapshot. Restoring that supply for v2 records is a separate piece "
                    + "of work — see p1-1b-v2-evidence-home.md section 3.4"), Assurance.CONFIGURED, Disclosure.EXTERNAL_OK),

    SOURCE_OBJECT_TYPE("sourceObjectType", V2Home.inputAttribute("sourceObjectType"), Assurance.ASSERTED, Disclosure.EXTERNAL_OK),

    /**
     * ASSERTED, not APPLIED, because three paths disagree and the weakest wins: a request may
     * supply {@code targetFolderOverride} (a caller's claim), a profile may supply an id or a
     * path (configuration), and only on a fresh create is this the folder the document was
     * actually put in. On every update path nothing writes a folder at all — the document stays
     * wherever it already was (external review).
     */
    TARGET_FOLDER_ID("targetFolderId", V2Home.none(
            "delivered today as nemaki_import_process.folderId, a REQUIRED Process attribute fed "
                    + "from the v1 snapshot. LineageProcessShape binds ingest to exactly one "
                    + "external asset and one document, so it cannot become an endpoint — see "
                    + "p1-1b-v2-evidence-home.md section 3.4"), Assurance.ASSERTED, Disclosure.EXTERNAL_OK),

    // ── What the repository now holds ────────────────────────────────────────────────────

    /**
     * Three values, never two. Absent {@code contentHash} does not mean "nothing stored": a
     * check-in with no stream carries the previous version's content forward, and that is
     * {@code unknown}, not {@code false}.
     */
    /**
     * APPLIED, not OBSERVED. On the ordinary capture path this is inferred from
     * {@code createDocument}/{@code checkIn} returning, with no read-back — the javadoc on
     * {@code describeStoredState} says so in as many words. Only the branches that could not
     * take that shortcut actually look (external review).
     */
    CONTENT_STORED("contentStored", V2Home.outputAttribute("contentStored"), Assurance.APPLIED, Disclosure.EXTERNAL_OK),

    CONTENT_HASH("contentHash", V2Home.outputAttribute("contentHash"), Assurance.OBSERVED, Disclosure.EXTERNAL_OK),

    CONTENT_HASH_ALGORITHM("contentHashAlgorithm", V2Home.outputAttribute("contentHashAlgorithm"), Assurance.OBSERVED, Disclosure.EXTERNAL_OK),

    /** The v1 key and the v2 attribute differ deliberately: the v1 name predates the three-state. */
    CONTENT_HASH_UNAVAILABLE("contentHashUnavailable", V2Home.outputAttribute("contentStateReason"), Assurance.OBSERVED, Disclosure.EXTERNAL_OK),

    /**
     * What {@link #CONTENT_HASH} is a digest OF (P1-1(d) R3).
     *
     * <p>{@code input} — the bytes this import fetched — or {@code input-matched-recorded} when
     * their digest also equalled the one already on the object. Never a value meaning "the bytes
     * the repository holds": nothing reads them back. Recording the digest without its subject
     * is what let "we hashed what we fetched" be read as "we verified what is stored".
     */
    CONTENT_HASH_SUBJECT("contentHashSubject", V2Home.outputAttribute("contentHashSubject"), Assurance.OBSERVED, Disclosure.EXTERNAL_OK),

    // ── Who ──────────────────────────────────────────────────────────────────────────────

    /**
     * OBSERVED covers the absence too. For a delegated or unauthenticated run the value is an
     * explicit "unknown:" / "service:" string — what we observed is that there was no
     * authenticated principal, and saying so is an observation, not a claim about who ran it.
     */
    EXECUTED_BY("executedBy", V2Home.none(
            "a field on LineageEventV2 would not be covered by creationPayloadDigest, so the "
                    + "actor could be edited without detection. Moving it into the digest changes "
                    + "the formula, and the roadmap puts execution origin in P1-1(e) — the "
                    + "formula should move once, there"), Assurance.OBSERVED, Disclosure.EXTERNAL_OK),

    ON_BEHALF_OF("onBehalfOf", V2Home.none(
            "the same digest-coverage problem as executedBy, and the same destination: the "
                    + "roadmap places execution origin in P1-1(e), where the delegated-execution "
                    + "case is decided as well"), Assurance.CONFIGURED, Disclosure.EXTERNAL_OK),

    // ── What a re-import pass did ────────────────────────────────────────────────────────

    /**
     * Why an event exists for a pass that stored nothing.
     *
     * <p>The dedupe and idempotency branches return from {@code execute} before the emit, so a
     * second poll of the same source object used to leave no trace at all while still rewriting
     * evidence through the wrapper. The rewrite is now refused; this says a pass happened and
     * what it decided (P1-1(d) D6/R5).
     */
    REIMPORT_OUTCOME("reimportOutcome", V2Home.none(
            "this describes the PASS, not either endpoint: the same external asset and the same "
                    + "document are named by the original capture event too, so an endpoint "
                    + "attribute would attach a per-run outcome to a thing that outlives the run. "
                    + "Its v2 home is a Process attribute, and Process attribute supply is the "
                    + "open prerequisite of the v2 write flip (p1-1b-v2-evidence-home.md §3.4)"), Assurance.OBSERVED, Disclosure.EXTERNAL_OK),

    /** Evidence keys this pass wrote because the object did not have them. */
    REIMPORT_FILLED("reimportFilled", V2Home.none(
            "a per-pass list, with the same problem as reimportOutcome: it belongs to the run "
                    + "rather than to the asset or the document, so it waits for Process "
                    + "attributes (p1-1b-v2-evidence-home.md §3.4)"), Assurance.APPLIED, Disclosure.EXTERNAL_OK),

    /** Evidence keys this pass was asked to change and did not. */
    REIMPORT_REFUSED("reimportRefused", V2Home.none(
            "a per-pass list, as reimportFilled. Recorded because a refusal is the more "
                    + "interesting half: it means the caller believes something different about "
                    + "this record than what was captured (p1-1b-v2-evidence-home.md §3.4)"), Assurance.OBSERVED, Disclosure.EXTERNAL_OK),

    /**
     * Which of THIS event's facts are unverified claims (P1-1(d) R2).
     *
     * <h2>Why the event repeats what the table already says</h2>
     *
     * <p>Because the table is code and the event is a record. An event read in five years must be
     * legible without the build that wrote it, and this enum will have changed by then. A
     * self-contained list is what makes "we checked this" and "someone told us this"
     * distinguishable after the fact — which is the whole of D1.
     *
     * <h2>Why only this one category has a key</h2>
     *
     * <p>{@link Assurance} has four members and this names one of them. The other three are
     * degrees of "we did it ourselves", and confusing them costs a reader little; reading an
     * unverified claim as a verified fact is the mistake with consequences. One key also keeps
     * the flat v1 map small — four lists would be four keys on every event, most of them
     * derivable by subtraction.
     */
    ASSURANCE_ASSERTED("assuranceAsserted", V2Home.none(
            "a statement about the other facts in this event rather than a fact about either "
                    + "endpoint, so an endpoint attribute would attach it to the asset or the "
                    + "document instead of to the record. Its v2 home is a Process attribute, "
                    + "which waits on Process attribute supply (p1-1b-v2-evidence-home.md §3.4)"),
            Assurance.OBSERVED, Disclosure.EXTERNAL_OK),

    /**
     * The custody stamp, carried by the event since P1-1(e)'s beforeEmit hook moved the chat
     * aspect application AHEAD of emission — the second copy D1 said the event could not repeat.
     */
    CHAT_CAPTURED_AT("chat.capturedAt", V2Home.none(
            "supplied by the beforeEmit hook, not the request: the wrapper stamps the aspect "
                    + "before emission since P1-1(e) and hands the instant to the event as a "
                    + "pass fact. Its v2 home is the journal-facts compartment of the extended "
                    + "record (p1-1e-boundary-and-origin.md §2.1), which lands with the digest "
                    + "extension"), Assurance.OBSERVED, Disclosure.EXTERNAL_OK,
            "nemaki:chatCapturedAt"),

    /**
     * The applied-metadata hash (mh1) of the chat evidence at emission — read back from the
     * stored object, never from the request (P1-1(d) D-1's event-side copy, landed in (e)).
     */
    APPLIED_CHAT_EVIDENCE_HASH("appliedChatEvidenceHash", V2Home.none(
            "computed at emission from the stored aspects; journal verification internals, so "
                    + "it must not travel to the catalogue — its v2 home is the journal-facts "
                    + "compartment (p1-1e-boundary-and-origin.md §2.1)"),
            Assurance.OBSERVED, Disclosure.INTERNAL_ONLY),

    /** As above, for the source-identity nine. */
    APPLIED_SOURCE_IDENTITY_HASH("appliedSourceIdentityHash", V2Home.none(
            "computed at emission from the stored aspects; journal verification internals — "
                    + "journal-facts compartment (p1-1e-boundary-and-origin.md §2.1)"),
            Assurance.OBSERVED, Disclosure.INTERNAL_ONLY),

    /** What the hashes are OF ("applied") — carried so rows outlive the formula's subject. */
    METADATA_HASH_SUBJECT("metadataHashSubject", V2Home.none(
            "names the hashes' subject; meaningless without them, so it lives and travels with "
                    + "them — journal-facts compartment (p1-1e-boundary-and-origin.md §2.1)"),
            Assurance.OBSERVED, Disclosure.INTERNAL_ONLY),

    /** The formula version ("mh1"), for the same reason. */
    METADATA_HASH_FORMULA("metadataHashFormula", V2Home.none(
            "names the hashes' formula; travels with them — journal-facts compartment "
                    + "(p1-1e-boundary-and-origin.md §2.1)"),
            Assurance.OBSERVED, Disclosure.INTERNAL_ONLY),

    // ── The conversation ─────────────────────────────────────────────────────────────────

    /**
     * NOT inside the stable key, despite appearances.
     *
     * <p>The key's tenant segment comes from {@code connector.getTenantId()}, while this comes
     * from the request metadata — different sources that need not agree, and the segment is
     * omitted entirely when the connector has no tenant id. A correspondence test showed the
     * key as {@code acme-chat://channels/C1/messages/…} with no workspace in it at all
     * (external review).
     */
    CHAT_WORKSPACE_ID("chat.workspaceId", V2Home.inputAttribute("chatWorkspaceId"), Assurance.ASSERTED, Disclosure.EXTERNAL_OK),

    /**
     * Genuinely inside the stable key: both the message and the attachment URI build a
     * {@code channels/{channelId}} segment from the same metadata this reads.
     */
    CHAT_CHANNEL_ID("chat.channelId", V2Home.identity(
            "carried by externalStableKey: both the message and attachment URIs build a "
                    + "channels/{channelId} segment from this same metadata value"), Assurance.ASSERTED, Disclosure.EXTERNAL_OK),

    /**
     * NOT inside the stable key for attachments, which is the case that matters.
     *
     * <p>A chat attachment's URI is {@code channels/{channelId}/files/{fileId}} — the id in it is
     * the FILE's, and this is the parent message's. It is the only identifier tying an
     * attachment back to the message it came from, so losing it at the write flip would break
     * that link (external review).
     */
    CHAT_MESSAGE_ID("chat.messageId", V2Home.inputAttribute("chatMessageId"), Assurance.ASSERTED, Disclosure.EXTERNAL_OK),

    CHAT_THREAD_ID("chat.threadId", V2Home.inputAttribute("chatThreadId"), Assurance.ASSERTED, Disclosure.EXTERNAL_OK),

    CHAT_CHANNEL_NAME("chat.channelName", V2Home.none(
            "free text from the caller; in a direct message it is the other person's name. An "
                    + "endpoint attribute is declared on the delivery type and therefore stored "
                    + "in the catalogue, which has no retention rule of its own — see "
                    + "p1-1b-v2-evidence-home.md section 6"), Assurance.ASSERTED, Disclosure.INTERNAL_ONLY, "nemaki:chatChannelName"),

    CHAT_PARTICIPANTS("chat.participants", V2Home.none(
            "personal names, same reason as chat.channelName"), Assurance.ASSERTED, Disclosure.INTERNAL_ONLY, "nemaki:chatParticipants"),

    CHAT_SELECTION_REASON("chat.selectionReason", V2Home.none(
            "this ingest's judgement, not a property of the source. The endpoint's qualified name "
                    + "repeats on re-ingest and the delivery target upserts, so a second ingest "
                    + "would silently overwrite the first one's judgement — and LineageEndpoint "
                    + "defines attributes as captured at emission and never updated"), Assurance.ASSERTED, Disclosure.INTERNAL_ONLY, "nemaki:chatSelectionReason"),

    CHAT_EVIDENCE_SCOPE("chat.evidenceScope", V2Home.none(
            "this ingest's judgement about what the evidence covers, not a property of the "
                    + "source; a re-ingest would upsert the endpoint and overwrite it"), Assurance.ASSERTED, Disclosure.INTERNAL_ONLY, "nemaki:chatEvidenceScope"),

    CHAT_CAPTURE_WINDOW_START("chat.captureWindowStart", V2Home.none(
            "the window this ingest chose to capture, not a property of the source; a re-ingest "
                    + "would upsert the endpoint and overwrite it"), Assurance.ASSERTED, Disclosure.INTERNAL_ONLY, "nemaki:chatCaptureWindowStart"),

    CHAT_CAPTURE_WINDOW_END("chat.captureWindowEnd", V2Home.none(
            "the window this ingest chose to capture, not a property of the source; a re-ingest "
                    + "would upsert the endpoint and overwrite it"), Assurance.ASSERTED, Disclosure.INTERNAL_ONLY, "nemaki:chatCaptureWindowEnd");

    /**
     * How strongly the recorded value is known (P1-1(d) R2/R4).
     *
     * <h2>Why this is a type and not a naming convention</h2>
     *
     * <p>An event that carries {@code chat.channelId} looks the same whether the value was read
     * off the source system or typed into the request by whoever called the endpoint. It is
     * always the latter — the ingest never contacts the chat service to confirm it — and the
     * wrapper that writes it to the object can fail and be downgraded to a warning, so the event
     * and the object can disagree. Nothing said so. A reader six months later has no way to tell
     * "we checked this" from "someone told us this" (P1-1(d) D1).
     *
     * <p>Declared here, beside the v1 key and the v2 home, because this is the one place a
     * maintainer adding a fact has to look. A convention on names would be broken by the next
     * person to add one.
     *
     * <h2>One label, several paths: take the weakest</h2>
     *
     * <p>Some facts are justified differently depending on which branch ran —
     * {@code contentStored} is read back on one path and inferred from a successful write on
     * another; {@code targetFolderId} comes from the request on one path, from the profile on a
     * second, and is the folder a document was actually created in on a third. A single static
     * label cannot be true for all of them, so <b>the label is the weakest justification any
     * path uses</b>, ordered
     * {@code ASSERTED &lt; CONFIGURED &lt; APPLIED &lt; OBSERVED}, and the field's own javadoc
     * names the paths that differ. Claiming less than is true is the safe direction; the
     * opposite is what this whole increment exists to stop (external review).
     */
    public enum Assurance {
        /**
         * The caller of this request said so, and nothing verified it.
         *
         * <p>The whole chat block, and the source ids. The capture window is the sharpest case:
         * it is {@code Instant.parse}d and otherwise unchecked, so it may be in the future or
         * exclude the very messages it claims to bound.
         */
        ASSERTED("asserted"),
        /**
         * Taken from this deployment's own configuration — a connector or profile definition.
         *
         * <p>Not {@link #ASSERTED}: a different party claimed it, at a different time, and an
         * administrator's claim about which system a connector talks to is worth separating from
         * a request body's. Still unverified: nothing proves the far end really is that system.
         */
        CONFIGURED("configured"),
        /** We wrote it and the write returned — an object id, a filled property. */
        APPLIED("applied"),
        /** We computed or read it ourselves during this pass — a digest, a read-back state. */
        OBSERVED("observed");

        // Declaration order IS the strength order the class javadoc states —
        // ASSERTED < CONFIGURED < APPLIED < OBSERVED — so ordinal comparison agrees with the
        // documented "weakest wins" rule. The first version declared OBSERVED before APPLIED,
        // which inverted the top of the lattice for anyone who wrote Collections.min over it
        // (external review). A test pins the relation.


        private final String wireValue;

        Assurance(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    /**
     * Whether a fact may leave for an external catalog (P1-1(d), evidence disclosure).
     *
     * <h2>Why the table decides this and not the sink</h2>
     *
     * <p>Today nothing decides it: {@code PurviewLineageSink} copies the whole v1 snapshot into
     * the outbound payload, and {@code chat.participants} is discarded only because the
     * destination type does not declare the attribute. The thing protecting personal names is
     * therefore someone else's schema — a setting away from not protecting them.
     *
     * <p>There is a second door, which is why this belongs on the table rather than in one sink:
     * an administrator can map any {@code subTypeProperties} value onto the document entity
     * through {@code catalog.sync.propertyMappings}, and the ingest writes
     * {@code nemaki:chatParticipants} onto the object as well as into the snapshot.
     * {@code CatalogPropertyMappingResolver} already refuses one property for exactly this
     * reason; driving that refusal from here is what keeps "one place to look" true.
     *
     * <h2>INTERNAL_ONLY is not deletion</h2>
     *
     * <p>The journal keeps the value either way. What is withheld is the copy that leaves for a
     * catalog with no retention rule of its own.
     */
    public enum Disclosure {
        /** Identifiers, digests, machine state. */
        EXTERNAL_OK,
        /**
         * Personal data, or caller-controlled free text that may contain any of it.
         *
         * <p>Not claimed to be exhaustive: a free-text field can hold anything, so what can be
         * declared is structure — "this field's value IS a person" or "this field is text we do
         * not constrain" — never "this field is clean".
         */
        INTERNAL_ONLY
    }

    private final String v1Key;
    private final V2Home v2Home;
    private final Assurance assurance;
    private final Disclosure disclosure;
    private final String cmisPropertyId;

    // NO three-argument constructor. An EXTERNAL_OK default would let the next fact be added
    // without anyone deciding whether it may leave the building, which is the same silence this
    // table exists to break (external review).
    CaptureEvidenceField(String v1Key, V2Home v2Home, Assurance assurance,
                         Disclosure disclosure) {
        this(v1Key, v2Home, assurance, disclosure, null);
    }

    /**
     * @param cmisPropertyId the property this fact is ALSO written to on the object, when there
     *        is one. The snapshot is not the only way out: an administrator can map any
     *        {@code subTypeProperties} value onto the catalogue entity, so a fact withheld from
     *        the sink and left mappable is not withheld at all.
     */
    CaptureEvidenceField(String v1Key, V2Home v2Home, Assurance assurance,
                         Disclosure disclosure, String cmisPropertyId) {
        this.cmisPropertyId = cmisPropertyId;
        this.v1Key = v1Key;
        this.v2Home = v2Home;
        this.assurance = assurance;
        this.disclosure = disclosure;
        if (disclosure == Disclosure.INTERNAL_ONLY
                && v2Home != null && v2Home.placement() == V2Home.Placement.IDENTITY) {
            // A fact inside the endpoint's identity travels in the qualifiedName whatever the
            // attribute map says, so withholding the attribute would hide the key and ship the
            // value — the exact fail-open this rule exists to close (external review).
            throw new IllegalStateException(v1Key + " is carried by the endpoint identity, so "
                    + "INTERNAL_ONLY cannot withhold it: the value travels in the qualified name. "
                    + "Withholding such a fact needs a change to the identity, not to disclosure.");
        }
    }

    /** Whether this fact may leave for an external catalog. */
    public Disclosure disclosure() {
        return disclosure;
    }

    /** Whether this fact must be withheld from anything leaving for an external catalog. */
    public boolean isInternalOnly() {
        return disclosure == Disclosure.INTERNAL_ONLY;
    }

    /** The CMIS property this fact is also written to on the object, or null. */
    public String cmisPropertyId() {
        return cmisPropertyId;
    }

    /**
     * The v1 snapshot keys that must not leave for an external catalog.
     *
     * <p>Derived, so a fact declared {@code INTERNAL_ONLY} is withheld without anyone having to
     * remember a second list.
     */
    public static java.util.Set<String> internalOnlyV1Keys() {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        for (CaptureEvidenceField field : values()) {
            if (field.isInternalOnly()) {
                keys.add(field.v1Key());
            }
        }
        return keys;
    }

    /**
     * The CMIS properties a custom catalogue mapping must not be allowed to read.
     *
     * <p>The second door: {@code PurviewEntityPayloadFactory} projects mapped
     * {@code subTypeProperties} onto the document entity, and the ingest writes these facts onto
     * the object as well as into the snapshot. Filtering the sink alone leaves a setting between
     * the personal data and the catalogue.
     */
    public static java.util.Set<String> internalOnlyCmisPropertyIds() {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (CaptureEvidenceField field : values()) {
            if (field.isInternalOnly() && field.cmisPropertyId != null) {
                ids.add(field.cmisPropertyId);
            }
        }
        return ids;
    }

    /** How strongly this fact's recorded value is known. */
    public Assurance assurance() {
        return assurance;
    }

    /** The key this fact uses in the v1 event-level snapshot. */
    public String v1Key() {
        return v1Key;
    }

    public V2Home v2Home() {
        return v2Home;
    }

    /** Where a fact lives in a v2 event, or why it does not live there. */
    public record V2Home(Placement placement, String attributeName, String reason) {

        public enum Placement {

            /** An attribute of the ingest's input endpoint (the external asset). */
            INPUT_ATTRIBUTE,

            /** An attribute of the ingest's output endpoint (the stored document). */
            OUTPUT_ATTRIBUTE,

            /**
             * Already inside the endpoint's identity, so an attribute would repeat it.
             *
             * <p>Distinct from {@link #NONE}: the fact IS carried by v2, just not as a field of
             * its own. Collapsing the two would read as evidence loss where there is none.
             */
            IDENTITY,

            /** Not carried by a v2 event. The reason says why, and where the work went. */
            NONE
        }

        public static V2Home inputAttribute(String name) {
            return new V2Home(Placement.INPUT_ATTRIBUTE, name, null);
        }

        public static V2Home outputAttribute(String name) {
            return new V2Home(Placement.OUTPUT_ATTRIBUTE, name, null);
        }

        public static V2Home identity(String reason) {
            return new V2Home(Placement.IDENTITY, null, reason);
        }

        public static V2Home none(String reason) {
            return new V2Home(Placement.NONE, null, reason);
        }

        /** Whether this fact reaches a v2 event at all, as an attribute or inside the identity. */
        public boolean carriedByV2() {
            return placement != Placement.NONE;
        }
    }
}
