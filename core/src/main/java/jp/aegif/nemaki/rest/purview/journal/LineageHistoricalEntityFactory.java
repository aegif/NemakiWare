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
package jp.aegif.nemaki.rest.purview.journal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import jp.aegif.nemaki.rest.purview.payload.CatalogSecretBoundary;
import jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory;

/**
 * The one place a historical entity's payload and its digest are decided.
 *
 * <h2>Why publisher, read-back and republisher all come here</h2>
 *
 * <p>If the writer built the entity one way and the read-back rebuilt it another, they could
 * agree by coincidence and disagree by coincidence, and neither result would mean anything. The
 * read-back's whole job is to answer "is what is in the catalog the thing this plan intended to
 * write" — which is only answerable if both sides are the same function of the same input.
 *
 * <h2>The digest is over what is written, not over the request</h2>
 *
 * <p>Canonical: type name, qualified name and every attribute, sorted, hashed with the shared
 * length-prefixed encoder. So the digest a plan records before writing is exactly the digest a
 * later read of the catalog produces, and a difference is a real difference rather than a map
 * iteration order.
 */
public final class LineageHistoricalEntityFactory {

    /** Frozen: it identifies the operation a stored intent refers to. */
    public static final String OPERATION_DOMAIN = "LINEAGE_HISTORICAL_ENTITY_V1";

    /** The two names the Atlas types actually use to say what state a subject is in. */
    static final String SOURCE_STATE = "sourceState";
    static final String LIFECYCLE_STATE = "lifecycleState";

    /**
     * Where a historical entity records that its source is gone — per type, because the types
     * disagree.
     *
     * <h2>Why this is not one constant</h2>
     *
     * <p>{@code nemaki_document} and {@code nemaki_archive} carry {@code lifecycleState};
     * {@code nemaki_folder_dataset} carries {@code sourceState}; and the external and artifact
     * types carry neither. Atlas <em>silently drops</em> an attribute a type does not declare,
     * so writing the wrong name produced an entity that looked exactly like a live one — a
     * tombstone with nothing marking it as a tombstone. A mock cannot see that: only a real
     * catalog drops the attribute.
     *
     * @return null for a type with nowhere to record it, which is a refusal to publish rather
     *         than a licence to publish something indistinguishable from a live object
     */
    public static String tombstoneMarkerAttribute(EndpointKind kind) {
        // nemaki_folder_dataset declares sourceState; every other type declares lifecycleState.
        // The three that declared neither gained it additively and optionally in v2.3.58, so a
        // well-formed snapshot of ANY kind can now be tombstoned. Before that, those kinds sent
        // every correct snapshot to SNAPSHOT_INCOMPLETE for ever.
        return kind == EndpointKind.CMIS_FOLDER ? SOURCE_STATE : LIFECYCLE_STATE;
    }

    /**
     * How a key is tagged when the catalog has it, and when it does not.
     *
     * <h2>Why the flag is on the key rather than a sentinel value</h2>
     *
     * <p>The first version used a magic string for "absent", which meant absence collided with
     * any attribute whose value happened to equal it — safety resting on nobody ever writing
     * that string. The literal also contained raw NUL bytes, so the source file was not text
     * and tools that expect UTF-8 could not read it.
     *
     * <p>Tagging the key instead makes absence structurally distinct: a key the catalog does
     * not have hashes under a different name than the same key holding {@code null}, and no
     * attribute value can reach either tag because values are hashed in a different position.
     */
    private static final String KEY_PRESENT = "=";
    private static final String KEY_ABSENT = "!";

    private LineageHistoricalEntityFactory() {
    }

    /**
     * The entity payload for a purged subject.
     *
     * @return an entity map in the shape the bulk endpoint takes: {@code typeName} plus
     *         {@code attributes}
     */
    public static Map<String, Object> entityFor(HistoricalEntitySnapshot historical) {
        LineageWaitingSnapshot snapshot = historical.snapshot();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("qualifiedName", snapshot.catalogQualifiedName());
        // The type's own mandatory identity attributes, which the snapshot's per-kind allowlist
        // deliberately does not carry — they are not observations, they are what the entity IS.
        // Atlas rejects the whole write without them, so an entity built without them could
        // never be published at all. Derived, never guessed: everything here comes from the
        // repository id or from parsing the qualified name the identity is a function of.
        attributes.putAll(identityAttributes(snapshot));
        // The snapshot's attributes are already allowlisted per kind and already through the
        // secret boundary; putting them through again is cheap and keeps this method safe on
        // its own rather than safe because of where it happens to be called from.
        attributes.putAll(CatalogSecretBoundary.sealed(snapshot.attributes()));
        // The one attribute this factory adds, under whichever name this type declares.
        // PURGED, not ARCHIVED: an archived object still exists and belongs to the
        // authoritative publisher.
        String marker = tombstoneMarkerAttribute(snapshot.endpointKind());
        if (marker != null) {
            attributes.put(marker, PurviewEntityPayloadFactory.SOURCE_STATE_PURGED);
        }

        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("typeName", snapshot.endpointKind().atlasTypeName());
        entity.put("attributes", attributes);
        return entity;
    }

    /**
     * The identity attributes Atlas requires and the snapshot does not carry.
     *
     * <p>{@code repositoryId} and {@code objectId} on the CMIS types, the archive's own
     * repository and lifecycle on {@code nemaki_archive}. The external and artifact types
     * declare their mandatory attributes inside the per-kind allowlist, so nothing is added
     * for them.
     *
     * <p>{@code lifecycleState} is PURGED rather than ARCHIVED: an archived object still
     * exists, and this entity exists precisely because its source does not.
     */
    static Map<String, Object> identityAttributes(LineageWaitingSnapshot snapshot) {
        Map<String, Object> identity = new LinkedHashMap<>();
        String objectId = RepositorySourceDispositionResolver.objectIdOf(
                snapshot.repositoryId(), snapshot.endpointKind(),
                snapshot.catalogQualifiedName());
        switch (snapshot.endpointKind()) {
            case CMIS_DOCUMENT, CMIS_FOLDER -> {
                identity.put("repositoryId", snapshot.repositoryId());
                if (objectId != null) {
                    identity.put("objectId", objectId);
                }
            }
            case ARCHIVE -> identity.put("archiveRepositoryId", snapshot.repositoryId());
            default -> {
                // Nothing to add: these types' mandatory attributes are in the snapshot's own
                // allowlist, so a snapshot that lacks them is incomplete rather than missing
                // something this factory could supply.
            }
        }
        return identity;
    }

    /**
     * The attributes {@code typeName} will not accept an entity without.
     *
     * <p>Taken from the Atlas type definitions rather than inferred: a write missing one of
     * these is rejected in full, so a snapshot that cannot supply them cannot reconstruct the
     * entity at all — which is {@code SNAPSHOT_INCOMPLETE}, the one terminal publish outcome.
     * Guessing a value instead would put a fabricated identity into a permanent record.
     */
    public static List<String> mandatoryAttributes(EndpointKind kind) {
        return switch (kind) {
            case CMIS_DOCUMENT, CMIS_FOLDER -> List.of("repositoryId", "objectId");
            case ARCHIVE -> List.of("originalObjectId", "archiveRepositoryId", "lifecycleState");
            case EXTERNAL_ASSET, CLOUD_OBJECT, COLD_STORAGE ->
                    List.of("externalStableKey", "sourceSystem");
            case IMPORT_ARTIFACT -> List.of("importMode");
            case EXPORT_ARTIFACT -> List.of("artifactKind");
        };
    }

    /**
     * Which mandatory attributes this entity cannot supply. Empty means it can be published.
     *
     * @return the missing names, in declaration order — names only, never values
     */
    public static List<String> missingMandatoryAttributes(Map<String, Object> entity,
            EndpointKind kind) {
        List<String> missing = new ArrayList<>();
        if (tombstoneMarkerAttribute(kind) == null) {
            // Nowhere to say the source is gone. Publishing anyway would put an entity in the
            // catalog that is indistinguishable from a live object's — which is worse than no
            // entity at all, because a consumer would read it as evidence the object exists.
            missing.add("a tombstone marker attribute (" + kind.atlasTypeName()
                    + " declares neither lifecycleState nor sourceState)");
            return missing;
        }
        Object attributes = entity == null ? null : entity.get("attributes");
        Map<?, ?> map = attributes instanceof Map<?, ?> m ? m : Map.of();
        for (String required : mandatoryAttributes(kind)) {
            Object value = map.get(required);
            if (value == null || (value instanceof String s && s.isBlank())) {
                missing.add(required);
            }
        }
        return missing;
    }

    /**
     * The ordinary entity for an observed endpoint — no tombstone marker anywhere.
     *
     * <h2>Built directly, never by subtraction</h2>
     *
     * <p>It would be shorter to call {@link #entityFor} and remove the marker afterwards, and
     * that is exactly the implementation to avoid: the tombstone would exist for the duration of
     * one method, and any future path that returned early, logged the intermediate map or
     * reordered the steps would publish it. This assembles the entity from the same primitives
     * without the marker ever being present.
     *
     * <p>What it asserts is only that a durable event observed this endpoint. No source state,
     * no evidence of destruction, no claim that the object exists now.
     */
    public static Map<String, Object> observedEntityFor(ObservedEntitySnapshot observed) {
        return observedEntityFrom(observed.snapshot());
    }

    /** The same entity, from a snapshot whichever type authorised it. */
    public static Map<String, Object> observedEntityFrom(LineageWaitingSnapshot snapshot) {
        return observedEntity(snapshot);
    }

    /**
     * The ordinary entity, with its state said out loud.
     *
     * <p>Earlier this simply omitted the marker, which was wrong twice over. {@code
     * nemaki_archive} declares {@code lifecycleState} mandatory, so a live archive could never
     * be published at all — correct data terminalised as SNAPSHOT_INCOMPLETE for ever. And an
     * omitted marker is not the same statement as a present one: the read-back had to encode
     * "this key must be absent" separately, when what an ordinary entity actually asserts is
     * that the source is ACTIVE.
     *
     * <p>Saying it directly makes the read-back compare ACTIVE against PURGED, which is the
     * comparison that matters, and satisfies the type's mandatory attribute at the same time.
     */
    private static Map<String, Object> observedEntity(LineageWaitingSnapshot snapshot) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("qualifiedName", snapshot.catalogQualifiedName());
        attributes.putAll(identityAttributes(snapshot));
        attributes.putAll(CatalogSecretBoundary.sealed(snapshot.attributes()));

        String marker = tombstoneMarkerAttribute(snapshot.endpointKind());
        if (marker != null) {
            attributes.put(marker, PurviewEntityPayloadFactory.SOURCE_STATE_ACTIVE);
        }

        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("typeName", snapshot.endpointKind().atlasTypeName());
        entity.put("attributes", attributes);
        return entity;
    }

    /** The digest of what an observed materialisation intends to write. */
    public static String plannedObservedDigest(ObservedEntitySnapshot observed) {
        return observedPlannedDigest(observedEntityFor(observed),
                observed.snapshot().endpointKind());
    }

    /**
     * The digest of an ordinary entity, including its assertion that no marker is present.
     *
     * <p>Both sides of the read-back comparison must encode the same keys, so the planned side
     * tags the marker as absent exactly as the read side does when the catalog does not hold it.
     */
    public static String observedPlannedDigest(Map<String, Object> planned, EndpointKind kind) {
        // The marker is now part of the ordinary entity (as ACTIVE), so the plain digest
        // already encodes it and the read-back compares ACTIVE against whatever is there.
        return operationDigest(planned);
    }

    /** The marker keys an ordinary entity asserts are absent, for the read-back projection. */
    public static java.util.List<String> markerAbsenceAssertion(EndpointKind kind) {
        String marker = tombstoneMarkerAttribute(kind);
        return marker == null ? java.util.List.of() : java.util.List.of(marker);
    }

    /**
     * The canonical digest of an entity payload.
     *
     * <p>Sorted by attribute name, so two builds of the same content hash the same however the
     * maps were assembled. Values are rendered with {@code String.valueOf}: the attributes are
     * already scalars — the snapshot refuses lists, maps and arrays — so there is no nesting to
     * lose.
     */
    public static String operationDigest(Map<String, Object> entity) {
        if (entity == null) {
            return null;
        }
        // Everything a plan writes is by definition present, so every key carries the present
        // tag. The read-back tags per key instead — same function, same encoding, and absence
        // is the only thing that differs.
        Map<String, Object> tagged = new LinkedHashMap<>();
        tagged.put("typeName" + KEY_PRESENT, entity.get("typeName"));
        Object attributes = entity.get("attributes");
        if (attributes instanceof Map<?, ?> map) {
            Map<String, Object> attrs = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                attrs.put(String.valueOf(e.getKey()) + KEY_PRESENT, e.getValue());
            }
            tagged.put("attributes", attrs);
        }
        return digestOfTagged(tagged);
    }

    /**
     * The hash of an already-tagged entity.
     *
     * <p>Sorted by tagged key, so two builds of the same content hash the same however the maps
     * were assembled. Values are rendered with {@code String.valueOf}: the attributes are
     * already scalars — the snapshot refuses lists, maps and arrays — so there is no nesting to
     * lose.
     */
    private static String digestOfTagged(Map<String, Object> tagged) {
        List<String> parts = new ArrayList<>();
        Object typeName = tagged.containsKey("typeName" + KEY_PRESENT)
                ? tagged.get("typeName" + KEY_PRESENT) : tagged.get("typeName" + KEY_ABSENT);
        parts.add(tagged.containsKey("typeName" + KEY_PRESENT) ? KEY_PRESENT : KEY_ABSENT);
        parts.add(typeName == null ? null : String.valueOf(typeName));
        Object attributes = tagged.get("attributes");
        if (attributes instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sorted.put(String.valueOf(e.getKey()), e.getValue());
            }
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                parts.add(e.getKey());
                parts.add(e.getValue() == null ? null : String.valueOf(e.getValue()));
            }
        }
        // Varargs, not an array argument: passing a String[] would hand the encoder one
        // unhashable value instead of the list of parts it length-prefixes individually.
        List<Object> hashed = new ArrayList<>();
        hashed.add(OPERATION_DOMAIN);
        hashed.addAll(parts);
        return LineageCanonicalHash.hash(hashed.toArray());
    }

    /** The digest of what this plan intends to write. */
    public static String plannedOperationDigest(HistoricalEntitySnapshot historical) {
        return operationDigest(entityFor(historical));
    }

    /**
     * The digest of what the catalog actually holds, projected onto what the plan wrote.
     *
     * <p>Projected, because the catalog adds attributes of its own — guid, timestamps, system
     * fields — and a comparison including those would report CONFLICT for every entity that
     * was written correctly. Only the keys the plan set are compared, which is exactly the
     * question being asked: <em>did this plan's content land</em>.
     *
     * @param readEntity what the catalog returned, in its own shape
     * @param planned the entity this plan built
     * @return null when the read cannot be projected, which the caller must treat as UNKNOWN
     *         rather than as a mismatch
     */
    public static String readBackDigest(Map<String, Object> readEntity,
            Map<String, Object> planned) {
        return readBackDigest(readEntity, planned, null);
    }

    /**
     * The same, with keys whose <em>absence</em> the plan asserts.
     *
     * <h2>The false MATCH this closes</h2>
     *
     * <p>The projection compares only the keys the plan set. An observed entity deliberately
     * sets no tombstone marker — so an entity in the catalog with identical attributes
     * <em>plus</em> {@code lifecycleState=PURGED} projected to the same digest, the pre-read
     * said MATCH, and the obligation was resolved while the catalog still held a tombstone for
     * a source nobody said was gone.
     *
     * <p>Not setting a key is a claim about that key, so it has to be in the comparison. Named
     * keys are projected whether or not the plan holds them, and an absent one hashes under its
     * absent tag — which is exactly what an observed plan expects and a tombstone is not.
     *
     * @param assertedAbsent keys the plan asserts the entity must not carry; may be null
     */
    public static String readBackDigest(Map<String, Object> readEntity,
            Map<String, Object> planned, java.util.Collection<String> assertedAbsent) {
        if (readEntity == null || planned == null) {
            return null;
        }
        Object plannedAttributes = planned.get("attributes");
        if (!(plannedAttributes instanceof Map<?, ?> plannedMap)) {
            return null;
        }
        Map<String, Object> normalised = normaliseRead(readEntity);
        Map<String, Object> readAttributes = attributesOf(normalised);
        if (readAttributes == null) {
            return null;
        }
        Map<String, Object> projected = new LinkedHashMap<>();
        java.util.List<String> comparedKeys = new ArrayList<>();
        for (Map.Entry<?, ?> e : plannedMap.entrySet()) {
            comparedKeys.add(String.valueOf(e.getKey()));
        }
        if (assertedAbsent != null) {
            for (String key : assertedAbsent) {
                if (key != null && !comparedKeys.contains(key)) {
                    comparedKeys.add(key);
                }
            }
        }
        for (String key : comparedKeys) {
            // Absent is not null: a key the catalog does not have must not project to the same
            // digest as a key it holds as null. The distinction rides on the key's tag.
            boolean present = readAttributes.containsKey(key);
            projected.put(key + (present ? KEY_PRESENT : KEY_ABSENT),
                    present ? readAttributes.get(key) : null);
        }
        Map<String, Object> shaped = new LinkedHashMap<>();
        // The type name comes from the read, so an entity of the wrong type is a CONFLICT
        // rather than a match on attributes alone. A type name the read does not carry is
        // tagged rather than substituted, for the same reason as the attributes.
        Object readType = normalised.get("typeName");
        shaped.put("typeName" + (readType != null ? KEY_PRESENT : KEY_ABSENT), readType);
        shaped.put("attributes", projected);
        return digestOfTagged(shaped);
    }

    /** The attribute map inside whatever shape the client returned. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributesOf(Map<String, Object> readEntity) {
        Object direct = readEntity == null ? null : readEntity.get("attributes");
        return direct instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    /**
     * The entity inside whatever shape the client returned.
     *
     * <p>Atlas's {@code getEntityByUniqueAttribute} answers {@code {"entity": {...}}}; unwrap
     * one level rather than failing, because that is the client's normal shape.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> normaliseRead(Map<String, Object> readEntity) {
        if (readEntity == null) {
            return null;
        }
        if (readEntity.get("attributes") != null) {
            return readEntity;
        }
        Object wrapped = readEntity.get("entity");
        return wrapped instanceof Map<?, ?> entity ? (Map<String, Object>) entity : readEntity;
    }

    /** The bulk payload shape, for one entity. */
    public static Map<String, Object> bulkPayload(Map<String, Object> entity) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entities", Collections.singletonList(entity));
        return payload;
    }
}
