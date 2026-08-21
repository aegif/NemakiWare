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

    SOURCE_SYSTEM("sourceSystem", V2Home.inputAttribute("sourceSystem")),

    /**
     * Already inside {@code externalStableKey} — the source URI is built from it — so the v2
     * home is the endpoint's identity rather than an attribute beside it.
     */
    SOURCE_OBJECT_ID("sourceObjectId", V2Home.identity(
            "carried by externalStableKey: the source URI is built from this id")),

    SOURCE_ARCHETYPE("sourceArchetype", V2Home.none(
            "delivered today as nemaki_import_process.importMode, a Process attribute fed from "
                    + "the v1 snapshot. Restoring that supply for v2 records is a separate piece "
                    + "of work — see p1-1b-v2-evidence-home.md section 3.4")),

    SOURCE_OBJECT_TYPE("sourceObjectType", V2Home.inputAttribute("sourceObjectType")),

    TARGET_FOLDER_ID("targetFolderId", V2Home.none(
            "delivered today as nemaki_import_process.folderId, a REQUIRED Process attribute fed "
                    + "from the v1 snapshot. LineageProcessShape binds ingest to exactly one "
                    + "external asset and one document, so it cannot become an endpoint — see "
                    + "p1-1b-v2-evidence-home.md section 3.4")),

    // ── What the repository now holds ────────────────────────────────────────────────────

    /**
     * Three values, never two. Absent {@code contentHash} does not mean "nothing stored": a
     * check-in with no stream carries the previous version's content forward, and that is
     * {@code unknown}, not {@code false}.
     */
    CONTENT_STORED("contentStored", V2Home.outputAttribute("contentStored")),

    CONTENT_HASH("contentHash", V2Home.outputAttribute("contentHash")),

    CONTENT_HASH_ALGORITHM("contentHashAlgorithm", V2Home.outputAttribute("contentHashAlgorithm")),

    /** The v1 key and the v2 attribute differ deliberately: the v1 name predates the three-state. */
    CONTENT_HASH_UNAVAILABLE("contentHashUnavailable", V2Home.outputAttribute("contentStateReason")),

    // ── Who ──────────────────────────────────────────────────────────────────────────────

    EXECUTED_BY("executedBy", V2Home.none(
            "a field on LineageEventV2 would not be covered by creationPayloadDigest, so the "
                    + "actor could be edited without detection. Moving it into the digest changes "
                    + "the formula, and the roadmap puts execution origin in P1-1(e) — the "
                    + "formula should move once, there")),

    ON_BEHALF_OF("onBehalfOf", V2Home.none(
            "the same digest-coverage problem as executedBy, and the same destination: the "
                    + "roadmap places execution origin in P1-1(e), where the delegated-execution "
                    + "case is decided as well")),

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
    CHAT_WORKSPACE_ID("chat.workspaceId", V2Home.inputAttribute("chatWorkspaceId")),

    /**
     * Genuinely inside the stable key: both the message and the attachment URI build a
     * {@code channels/{channelId}} segment from the same metadata this reads.
     */
    CHAT_CHANNEL_ID("chat.channelId", V2Home.identity(
            "carried by externalStableKey: both the message and attachment URIs build a "
                    + "channels/{channelId} segment from this same metadata value")),

    /**
     * NOT inside the stable key for attachments, which is the case that matters.
     *
     * <p>A chat attachment's URI is {@code channels/{channelId}/files/{fileId}} — the id in it is
     * the FILE's, and this is the parent message's. It is the only identifier tying an
     * attachment back to the message it came from, so losing it at the write flip would break
     * that link (external review).
     */
    CHAT_MESSAGE_ID("chat.messageId", V2Home.inputAttribute("chatMessageId")),

    CHAT_THREAD_ID("chat.threadId", V2Home.inputAttribute("chatThreadId")),

    CHAT_CHANNEL_NAME("chat.channelName", V2Home.none(
            "free text from the caller; in a direct message it is the other person's name. An "
                    + "endpoint attribute is declared on the delivery type and therefore stored "
                    + "in the catalogue, which has no retention rule of its own — see "
                    + "p1-1b-v2-evidence-home.md section 6")),

    CHAT_PARTICIPANTS("chat.participants", V2Home.none(
            "personal names, same reason as chat.channelName")),

    CHAT_SELECTION_REASON("chat.selectionReason", V2Home.none(
            "this ingest's judgement, not a property of the source. The endpoint's qualified name "
                    + "repeats on re-ingest and the delivery target upserts, so a second ingest "
                    + "would silently overwrite the first one's judgement — and LineageEndpoint "
                    + "defines attributes as captured at emission and never updated")),

    CHAT_EVIDENCE_SCOPE("chat.evidenceScope", V2Home.none(
            "this ingest's judgement about what the evidence covers, not a property of the "
                    + "source; a re-ingest would upsert the endpoint and overwrite it")),

    CHAT_CAPTURE_WINDOW_START("chat.captureWindowStart", V2Home.none(
            "the window this ingest chose to capture, not a property of the source; a re-ingest "
                    + "would upsert the endpoint and overwrite it")),

    CHAT_CAPTURE_WINDOW_END("chat.captureWindowEnd", V2Home.none(
            "the window this ingest chose to capture, not a property of the source; a re-ingest "
                    + "would upsert the endpoint and overwrite it"));

    private final String v1Key;
    private final V2Home v2Home;

    CaptureEvidenceField(String v1Key, V2Home v2Home) {
        this.v1Key = v1Key;
        this.v2Home = v2Home;
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
