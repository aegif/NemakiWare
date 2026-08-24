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

import jp.aegif.nemaki.model.Aspect;
import jp.aegif.nemaki.model.Property;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The metadata hash formula, frozen (p1-1d-metadata-hash.md §2.2, §6).
 *
 * <p>The golden values are computed OUTSIDE this codebase (python hashlib over the canonical
 * string), so a formula change cannot re-derive its own expectation. They are pure-string
 * SHA-256 — deterministic across JDK builds, unlike the environment digests that proved
 * build-sensitive.
 */
class EvidenceMetadataHashTest {

    private static final String CHAT = "nemaki:chatContextMetadata";
    private static final String INTEGRATION = "nemaki:externalIntegration";

    private static Aspect aspect(String name, Map<String, Object> values) {
        Aspect a = new Aspect();
        a.setName(name);
        List<Property> props = new ArrayList<>();
        values.forEach((k, v) -> props.add(new Property(k, v)));
        a.setProperties(props);
        return a;
    }

    private static final String MAIL = "nemaki:messageMetadata";
    private static final String NOTE = "nemaki:noteMetadata";

    @Test
    @DisplayName("golden vector: the archetype hash of a fixed mail fixture")
    void goldenArchetypeVector() {
        // Computed OUTSIDE this codebase (python hashlib over the canonical form), like every
        // other golden value here — a vector produced by the code it pins proves nothing.
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("nemaki:internetMessageId", "<abc@example.com>");
        values.put("nemaki:mailSubject", "Q3 review");
        values.put("nemaki:mailFrom", "otsuka@example.com");
        // Stored shape: epoch millis as a Double, which is what the CouchDB round trip yields.
        values.put("nemaki:mailSentAt", 1.7839944E12d);

        String hash = EvidenceMetadataHash.hashOf(List.of(aspect(MAIL, values)),
                EvidenceMetadataHash.ARCHETYPE_ASPECT_NAMES,
                jp.aegif.nemaki.patch.Patch_ArchetypeMetadataEvidenceReadOnly
                        .EVIDENCE_PROPERTIES);

        assertEquals("mh1:4d729a2389b0d94021f1f94816b50ab64c1a092fd89f5414e4ab9a0e78d2de31", hash,
                "the canonical form moved. If this is deliberate, the formula version must move "
                        + "too (mh2), or rows recorded under mh1 become unverifiable without "
                        + "saying so.");
    }

    @Test
    @DisplayName("golden vector: two archetype aspects on one object union deterministically")
    void goldenArchetypeUnionVector() {
        // The product applies at most one archetype aspect per object, but the hash must not
        // silently pick one if two ever arrive. The union is what the design promises, and its
        // value is pinned so "pick the first" cannot pass.
        Map<String, Object> mail = new LinkedHashMap<>();
        mail.put("nemaki:internetMessageId", "<abc@example.com>");
        mail.put("nemaki:mailSubject", "Q3 review");
        mail.put("nemaki:mailFrom", "otsuka@example.com");
        mail.put("nemaki:mailSentAt", 1.7839944E12d);
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("nemaki:notePageId", "page-1");
        note.put("nemaki:noteCreatedAt", 1.7839944E12d);

        String hash = EvidenceMetadataHash.hashOf(
                List.of(aspect(MAIL, mail), aspect(NOTE, note)),
                EvidenceMetadataHash.ARCHETYPE_ASPECT_NAMES,
                jp.aegif.nemaki.patch.Patch_ArchetypeMetadataEvidenceReadOnly
                        .EVIDENCE_PROPERTIES);

        assertEquals("mh1:03df624aaf794f128451303fb10f59ccae7cfe1174cfd3d3ed4a0e92a093d027", hash);
    }

    @Test
    @DisplayName("the archetype hash escapes LF too — a value cannot swallow a line")
    void archetypeEscapingIsInjective() {
        Map<String, Object> forged = new LinkedHashMap<>();
        forged.put("nemaki:internetMessageId",
                "<abc@example.com>\nnemaki:mailSubject=FORGED");
        Map<String, Object> honest = new LinkedHashMap<>();
        honest.put("nemaki:internetMessageId", "<abc@example.com>");
        honest.put("nemaki:mailSubject", "FORGED");

        String forgedHash = EvidenceMetadataHash.hashOf(List.of(aspect(MAIL, forged)),
                EvidenceMetadataHash.ARCHETYPE_ASPECT_NAMES,
                jp.aegif.nemaki.patch.Patch_ArchetypeMetadataEvidenceReadOnly
                        .EVIDENCE_PROPERTIES);
        String honestHash = EvidenceMetadataHash.hashOf(List.of(aspect(MAIL, honest)),
                EvidenceMetadataHash.ARCHETYPE_ASPECT_NAMES,
                jp.aegif.nemaki.patch.Patch_ArchetypeMetadataEvidenceReadOnly
                        .EVIDENCE_PROPERTIES);

        assertEquals("mh1:5283633d4626a1834c6b1af95b0df7bf95cc1bb28fe9b7d5f21611d6caf4284f",
                forgedHash);
        assertEquals("mh1:a9451c5aba3a874f96ec8a8dd122d864baf4fd3fc08428c4db656a87175b3b90",
                honestHash);
        assertTrue(!forgedHash.equals(honestHash),
                "a value swallowed a line boundary: a forged object would verify as MATCH");
    }

    @Test
    @DisplayName("golden vector: the chat hash of a fixed fixture is this exact value")
    void goldenChatVector() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("nemaki:chatChannelId", "C123");
        values.put("nemaki:chatParticipants", "otsuka,ishii");
        // Stored shape: epoch millis as a Double, which is what the CouchDB round trip yields.
        values.put("nemaki:chatCapturedAt", 1.7839944E12d);

        String hash = EvidenceMetadataHash.hashOf(List.of(aspect(CHAT, values)), CHAT,
                jp.aegif.nemaki.patch.Patch_ChatContextEvidenceReadOnly.EVIDENCE_PROPERTIES);

        assertEquals("mh1:f7b8acc3c339e363daa51244d48e3b353d202f2ac2c9dfb6c2cadfc40e89ceb4", hash,
                "the canonical form moved. If this is deliberate, the formula version must move "
                        + "too (mh2), or rows recorded under mh1 become unverifiable without "
                        + "saying so.");
    }

    @Test
    @DisplayName("a value carrying LF cannot swallow its neighbouring lines — the injectivity pin")
    void escapingKeepsTheFormInjective() {
        // Without escaping these two DIFFERENT states canonicalize identically (verified
        // outside: the raw joined strings are equal), and a forged object would verify as
        // MATCH inside the design's own threat model (external review P1-1).
        Map<String, Object> forged = new LinkedHashMap<>();
        forged.put("nemaki:sourceObjectId", "id\nnemaki:sourceObjectType=message");
        forged.put("nemaki:sourceSystem", "acme");

        Map<String, Object> genuine = new LinkedHashMap<>();
        genuine.put("nemaki:sourceObjectId", "id");
        genuine.put("nemaki:sourceObjectType", "message");
        genuine.put("nemaki:sourceSystem", "acme");

        String forgedHash = EvidenceMetadataHash.hashOf(
                List.of(aspect(INTEGRATION, forged)), INTEGRATION,
                EvidenceMetadataHash.SOURCE_IDENTITY_PROPERTIES);
        String genuineHash = EvidenceMetadataHash.hashOf(
                List.of(aspect(INTEGRATION, genuine)), INTEGRATION,
                EvidenceMetadataHash.SOURCE_IDENTITY_PROPERTIES);

        assertNotEquals(genuineHash, forgedHash,
                "two different stored states hashed identically — the escaping is gone and the "
                        + "canonical form is forgeable again");
        // And both are the exact golden values, so the escape rule itself is frozen.
        assertEquals("mh1:6a1a46f39ddb2b283006f9b85f27f30cebd8afe7ef9ebb07c3e516359cfbc884",
                forgedHash);
        assertEquals("mh1:2dab180b000308eddec2382a182a3afba5496b388f1f957d61b12381f3465ce9",
                genuineHash);
    }

    @Test
    @DisplayName("every shape of the same stored instant hashes identically")
    void datetimeFormsAreOneValue() {
        long millis = 1_783_994_400_000L;
        GregorianCalendar calendar = new GregorianCalendar();
        calendar.setTimeInMillis(millis);

        String base = null;
        for (Object form : new Object[]{calendar, (double) millis, millis,
                "2026-07-14T02:00:00Z", Long.toString(millis)}) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("nemaki:chatChannelId", "C123");
            values.put("nemaki:chatCapturedAt", form);
            String hash = EvidenceMetadataHash.hashOf(List.of(aspect(CHAT, values)), CHAT,
                    jp.aegif.nemaki.patch.Patch_ChatContextEvidenceReadOnly.EVIDENCE_PROPERTIES);
            if (base == null) {
                base = hash;
            } else {
                assertEquals(base, hash, "the same instant hashed differently as " + form.getClass()
                        + " — the F1 shape again, this time in the verifier");
            }
        }
    }

    @Test
    @DisplayName("free text that merely looks like a date stays text")
    void freeTextIsNotNormalized() {
        // chatSelectionReason legitimately can BE an ISO string. Normalizing it would erase the
        // difference between "the caller wrote this text" and "this instant".
        assertEquals("2026-07-14T02:00:00Z",
                EvidenceMetadataHash.canonicalValue("nemaki:chatSelectionReason",
                        "2026-07-14T02:00:00Z"));
        // While the datetime property with the same string normalizes.
        assertEquals("1783994400000",
                EvidenceMetadataHash.canonicalValue("nemaki:chatCapturedAt",
                        "2026-07-14T02:00:00Z"));
    }

    @Test
    @DisplayName("insertion order does not matter; blank counts as absent")
    void orderAndBlankRules() {
        Map<String, Object> forward = new LinkedHashMap<>();
        forward.put("nemaki:chatChannelId", "C123");
        forward.put("nemaki:chatParticipants", "otsuka,ishii");
        Map<String, Object> reversed = new LinkedHashMap<>();
        reversed.put("nemaki:chatParticipants", "otsuka,ishii");
        reversed.put("nemaki:chatChannelId", "C123");
        Map<String, Object> withBlank = new LinkedHashMap<>(forward);
        withBlank.put("nemaki:chatChannelName", "  ");

        String a = EvidenceMetadataHash.hashOf(List.of(aspect(CHAT, forward)), CHAT,
                jp.aegif.nemaki.patch.Patch_ChatContextEvidenceReadOnly.EVIDENCE_PROPERTIES);
        String b = EvidenceMetadataHash.hashOf(List.of(aspect(CHAT, reversed)), CHAT,
                jp.aegif.nemaki.patch.Patch_ChatContextEvidenceReadOnly.EVIDENCE_PROPERTIES);
        String c = EvidenceMetadataHash.hashOf(List.of(aspect(CHAT, withBlank)), CHAT,
                jp.aegif.nemaki.patch.Patch_ChatContextEvidenceReadOnly.EVIDENCE_PROPERTIES);

        assertEquals(a, b, "the hash depends on insertion order — the JVM key-order trap");
        assertEquals(a, c, "a blank value counted as present; every other evidence path treats "
                + "blank as absent");
    }

    @Test
    @DisplayName("nothing to hash is null, not a hash of emptiness")
    void nothingToHashIsNull() {
        assertNull(EvidenceMetadataHash.hashOf(List.of(), CHAT,
                jp.aegif.nemaki.patch.Patch_ChatContextEvidenceReadOnly.EVIDENCE_PROPERTIES));
        assertNull(EvidenceMetadataHash.hashOf(null, CHAT,
                jp.aegif.nemaki.patch.Patch_ChatContextEvidenceReadOnly.EVIDENCE_PROPERTIES));
    }

    @Test
    @DisplayName("the hashed set and the written set are the same constant — AC9")
    void hashedSetMatchesWrittenSet() {
        // buildSourceIdentityProps is what a capture pass writes; the hash covers that set minus
        // the two declared exclusions. A twelfth put added to one side without a decision on the
        // other fails here instead of silently leaving a new fact outside the hash.
        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c1");
        connector.setSourceSystem("acme");
        connector.setSourceArchetype(SourceArchetype.CHAT_CONTEXT);

        ExternalIngestRequest request = new ExternalIngestRequest();
        request.setRepositoryId("bedroom");
        request.setSourceObjectId("m-1");
        request.setSourceObjectType("message");
        request.setSourceUrl("https://example.test/m-1");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("channelId", "C123");
        request.setMetadata(metadata);

        Map<String, Object> written = new CanonicalImportServiceImpl()
                .buildSourceIdentityProps(connector, request, "a".repeat(64));

        java.util.Set<String> expected = new java.util.TreeSet<>();
        expected.addAll(EvidenceMetadataHash.SOURCE_IDENTITY_PROPERTIES);
        expected.addAll(EvidenceMetadataHash.EXCLUDED_FROM_METADATA_HASH);

        assertEquals(expected, new java.util.TreeSet<>(written.keySet()),
                "applySourceMetadata writes a set the metadata hash does not know about (or the "
                        + "hash claims a property nothing writes). Every written key must be "
                        + "either hashed or on the declared exclusion list.");
    }

    @Test
    @DisplayName("an absent source value is OMITTED, not written as empty string")
    void absentValuesAreOmittedNotBlanked() {
        // The merge overwrites every key present in the map, so putting "" for a missing value
        // BLANKED the stored source identity on any version-up whose request lacked the field —
        // evidence destroyed by an ordinary re-import (audit #12 / plan D-7). Omission makes the
        // merge preserve the stored value: fill semantics.
        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c1");
        connector.setSourceSystem("acme");
        connector.setSourceArchetype(SourceArchetype.BUSINESS_RECORD);

        ExternalIngestRequest request = new ExternalIngestRequest();
        request.setRepositoryId("bedroom");
        request.setSourceObjectId("r-1");
        // No sourceUrl, no sourceObjectType — the version-up shape.

        Map<String, Object> written = new CanonicalImportServiceImpl()
                .buildSourceIdentityProps(connector, request, null);

        assertTrue(!written.containsKey("nemaki:sourceUrl"),
                "an absent sourceUrl was written anyway; as \"\" it erases the stored one");
        assertTrue(!written.containsKey("nemaki:sourceObjectType"), written.keySet().toString());
        assertEquals("acme", written.get("nemaki:sourceSystem"),
                "present values must still be written — this is fill, not freeze");
    }

    @Test
    @DisplayName("compute() fills both hashes from one aspect list")
    void computeCoversBothAspects() {
        Map<String, Object> chat = new LinkedHashMap<>();
        chat.put("nemaki:chatChannelId", "C123");
        Map<String, Object> integration = new LinkedHashMap<>();
        integration.put("nemaki:sourceObjectId", "m-1");

        EvidenceMetadataHash.AppliedHashes hashes = EvidenceMetadataHash.compute(List.of(
                aspect(CHAT, chat), aspect(INTEGRATION, integration)));

        assertTrue(hashes.chatEvidenceHash() != null && hashes.chatEvidenceHash().startsWith("mh1:"));
        assertTrue(hashes.sourceIdentityHash() != null
                && hashes.sourceIdentityHash().startsWith("mh1:"));
        assertNotEquals(hashes.chatEvidenceHash(), hashes.sourceIdentityHash());
    }
}
