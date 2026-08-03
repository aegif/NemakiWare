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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * §2's attribute-length rule, applied where a producer builds an endpoint.
 *
 * <h2>Why this is not in the constructor</h2>
 *
 * <p>Both codecs rebuild endpoints through {@link LineageEndpoint}'s canonical constructor
 * ({@code LineageSpoolCodec.endpoints}, {@code CouchLineageEventV2.endpointsFromList}).
 * Normalizing there would silently rewrite a STORED record as it was read, after which its
 * persisted {@code payloadDigest} / {@code creationPayloadDigest} would no longer verify — a
 * corruption, not a migration. So this runs only at the producer factories, the boundary where
 * raw domain data first becomes an endpoint. Records already on disk are never touched.
 *
 * <h2>What it does not do</h2>
 *
 * <p>It never drops an attribute and never shortens a {@link EndpointAttribute.Policy#PRESERVE}
 * one — which is every required attribute, every identity attribute, every {@code COUNT}, and
 * the handful of optional values that turned out to be identifiers or machine state rather than
 * display text. An oversized value of that kind flows on whole, and if the fact then cannot be
 * stored, {@code LineageChunkPlanner} makes it a durable {@code UNRESOLVED(OVERSIZE)} carrying
 * the endpoint's record hash. That is §2's own rule for the case, and stepping in front of it
 * here would lose the whole fact: {@code LineageFactEmission.emitSafely} swallows construction
 * failures. (Further out still, {@code LineageFactSpool} refuses an encoded record above its
 * 32 MiB limit before the planner runs at all.)
 *
 * <h2>Idempotence</h2>
 *
 * <p>Producers re-emit, and a second pass over an already-normalized map must produce the same
 * map — otherwise one business fact would digest two ways. Hence: the digest is always of the
 * ORIGINAL value; a long value arriving with a companion must agree with it; a short value
 * arriving with a companion keeps both (it is the re-emit case, and its original is gone, so
 * re-truncating is not even possible); and companions are themselves never truncated.
 */
final class EndpointAttributeLimits {

    private EndpointAttributeLimits() {
    }

    /**
     * The attributes as they should be stored, with §2's limits applied.
     *
     * @throws IllegalArgumentException when a companion contradicts the value it describes, or
     *         is not a well-formed digest — a caller that supplies someone else's evidence is
     *         making a claim about content it did not measure
     */
    static Map<String, Object> normalize(EndpointKind kind, Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return attributes;
        }
        // Every supplied companion is checked on its own, not merely as a side effect of
        // processing the value it describes: a map carrying `versionLabelOriginalSha256`
        // without `versionLabel` would otherwise sail through as ordinary TEXT, asserting
        // that something was shortened when nothing was.
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            if (!EndpointAttribute.isEvidenceName(entry.getKey())) {
                continue;
            }
            String described = entry.getKey().substring(0, entry.getKey().length()
                    - EndpointAttribute.EVIDENCE_SUFFIX.length());
            EndpointAttribute primary = kind.attribute(described);
            if (primary == null
                    || primary.policy() != EndpointAttribute.Policy.TRUNCATE_WITH_EVIDENCE) {
                throw new IllegalArgumentException("'" + entry.getKey() + "' describes '"
                        + described + "', which is not a truncatable attribute of " + kind);
            }
            if (!attributes.containsKey(described)) {
                throw new IllegalArgumentException("'" + entry.getKey() + "' is evidence for a"
                        + " value that is not here — a truncation marker without its value"
                        + " claims something was shortened when nothing was (kind=" + kind + ")");
            }
            if (!EndpointAttribute.isEvidenceDigest(entry.getValue())) {
                throw new IllegalArgumentException("'" + entry.getKey() + "' must be 64"
                        + " lowercase hex characters (kind=" + kind + ")");
            }
        }
        Map<String, Object> normalized = new LinkedHashMap<>(attributes);
        boolean changed = false;
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            EndpointAttribute declared = kind.attribute(entry.getKey());
            if (declared == null
                    || declared.policy() != EndpointAttribute.Policy.TRUNCATE_WITH_EVIDENCE
                    || !(entry.getValue() instanceof String value)) {
                continue;
            }
            String evidenceName = declared.evidenceName();
            Object existingEvidence = attributes.get(evidenceName);
            if (value.length() <= EndpointAttribute.MAX_DISPLAY_CODE_UNITS) {
                // Already normalized (the companion was validated above), or never needed it.
                // Its original is gone, so re-truncating is not even possible — this is the
                // re-emit case and the fixed point depends on leaving both alone.
                continue;
            }
            String digest = EndpointAttribute.evidenceDigest(value);
            if (existingEvidence != null && !digest.equals(existingEvidence)) {
                throw new IllegalArgumentException("'" + evidenceName + "' does not describe"
                        + " the value of '" + declared.name() + "' — refusing to record"
                        + " evidence for content it did not measure (kind=" + kind + ")");
            }
            normalized.put(declared.name(), value.substring(0,
                    EndpointAttribute.truncationLength(value,
                            EndpointAttribute.MAX_DISPLAY_CODE_UNITS)));
            normalized.put(evidenceName, digest);
            changed = true;
        }
        return changed ? normalized : attributes;
    }
}
