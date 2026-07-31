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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Endpoints built the way an attacker or a legacy reader would build them — not through a factory.
 *
 * <h2>What this file is for</h2>
 *
 * <p>Every other test constructs endpoints through {@code LineageEndpoint.externalAsset} and
 * friends, and so proves only that the factories behave. The factories are not the only path: a
 * record mapped from a CouchDB document, a v1 legacy reader and the repair path all reach the
 * canonical constructor directly, and those are exactly the paths the four-layer check in the
 * design exists for.
 *
 * <p>The stable-key contract used to live in the factories alone. Because
 * {@link LineageRepositoryScope} rebuilds the qualified name <em>from the stable-key attribute</em>,
 * an endpoint carrying a dangerous key in both places was self-consistent and passed every check
 * downstream. A test that only ever calls factories cannot see that.
 */
public class LineageEndpointBypassTest {

    private static final String REPO = "bedroom";

    private static LineageEndpoint externalWith(String stableKey) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("sourceSystem", "confluence");
        attributes.put(LineageEndpoint.ATTR_EXTERNAL_STABLE_KEY, stableKey);
        return new LineageEndpoint(EndpointKind.EXTERNAL_ASSET,
                LineageEndpoint.externalAssetQualifiedName(REPO, stableKey), REPO, null, null,
                attributes);
    }

    /** The credential that used to reach the catalog through the constructor. */
    @Test
    public void aSignedUrlCannotBeSmuggledInThroughTheConstructor() {
        assertThrows(IllegalArgumentException.class,
                () -> externalWith("https://user:pass@external.example/item"));
        assertThrows(IllegalArgumentException.class,
                () -> externalWith("https://blob.example/doc?sig=SECRET"));
        assertThrows(IllegalArgumentException.class,
                () -> externalWith("https://ext/doc#fragment"));
        assertThrows(IllegalArgumentException.class,
                () -> externalWith(" https://ext/doc"));
    }

    /**
     * A traversal path is the same problem in the filesystem connector: {@code file://../secret}
     * escapes whatever directory the operator meant to expose, and it used to pass because only
     * the factory normalised.
     */
    @Test
    public void aTraversalPathCannotBeSmuggledInThroughTheConstructor() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("sourceSystem", LineageEndpoint.FILESYSTEM_SOURCE_SYSTEM);
        attributes.put(LineageEndpoint.ATTR_EXTERNAL_STABLE_KEY, "file://../secret.txt");
        assertThrows(IllegalArgumentException.class, () -> new LineageEndpoint(
                EndpointKind.EXTERNAL_ASSET,
                LineageEndpoint.externalAssetQualifiedName(REPO, "file://../secret.txt"),
                REPO, null, null, attributes));

        Map<String, Object> unnormalised = new LinkedHashMap<>();
        unnormalised.put("sourceSystem", LineageEndpoint.FILESYSTEM_SOURCE_SYSTEM);
        unnormalised.put(LineageEndpoint.ATTR_EXTERNAL_STABLE_KEY, "file:///srv/in/../secret.txt");
        assertThrows(IllegalArgumentException.class, () -> new LineageEndpoint(
                EndpointKind.EXTERNAL_ASSET,
                LineageEndpoint.externalAssetQualifiedName(REPO, "file:///srv/in/../secret.txt"),
                REPO, null, null, unnormalised));
    }

    /**
     * Consistency between name and attribute is not enough on its own — it was what made the
     * bypass invisible. A key that is merely non-canonical is rejected even when both halves
     * agree.
     */
    @Test
    public void aConsistentButNonCanonicalKeyIsStillRejected() {
        assertThrows(IllegalArgumentException.class, () -> externalWith("https://ext/doc "));
    }

    /** A key the factory would have produced is accepted through the constructor too. */
    @Test
    public void aCanonicalKeyPassesThroughTheConstructor() {
        LineageEndpoint endpoint = externalWith("https://ext/spaces/ENG/pages/1");
        LineageRepositoryScope.validateEndpoint(REPO, endpoint, "input");
    }

    // ------------------------------------------------------------------
    // Nothing prints the key, whichever way it is asked for
    // ------------------------------------------------------------------

    /**
     * The record's generated {@code toString} printed the stable key and the qualified name that
     * base64-encodes it. One log statement anywhere in the pipeline was enough.
     */
    @Test
    public void toStringCarriesNeitherTheKeyNorItsEncoding() {
        LineageEndpoint endpoint = LineageEndpoint.externalAsset(REPO,
                "https://ext/SECRET-DOCUMENT", "confluence");
        String printed = endpoint.toString();

        assertFalse(printed.contains("SECRET-DOCUMENT"),
                "the raw stable key reached toString: " + printed);
        assertFalse(printed.contains(encoded(endpoint)),
                "the encoded qualified name reached toString: " + printed);
        assertTrue(printed.contains("redacted"), printed);
        assertTrue(printed.contains("bedroom"), "the repository is safe to show: " + printed);
        assertTrue(printed.contains("sourceSystem=confluence"),
                "the attributes that are not the key stay legible: " + printed);
    }

    /** The path is the key in another spelling, so it is redacted with it. */
    @Test
    public void toStringRedactsTheFilesystemPathToo() {
        String printed = LineageEndpoint.filesystemPath(REPO, "/srv/in/SECRET-NAME.pdf").toString();
        assertFalse(printed.contains("SECRET-NAME"), printed);
        assertTrue(printed.contains("sourceSystem=filesystem"), printed);
    }

    /** Both identity fields, each present on exactly one kind, must survive into the description. */
    @Test
    public void toStringShowsWhicheverIdentityFieldTheKindUses() {
        String document = LineageEndpoint.document(REPO, "d-1", "n").toString();
        assertTrue(document.contains("objectId=d-1"), document);
        assertFalse(document.contains("operationId"), document);

        String artifact = LineageEndpoint.importArtifact(REPO, "op-1", "MANAGED", null).toString();
        assertTrue(artifact.contains("operationId=op-1"), artifact);
        assertFalse(artifact.contains("objectId"), artifact);

        String external = LineageEndpoint.externalAsset(REPO, "https://ext/1", "confluence")
                .toString();
        assertFalse(external.contains("objectId"), external);
        assertFalse(external.contains("operationId"), external);
    }

    /** A CMIS endpoint has nothing to hide, and hiding it would make logs useless. */
    @Test
    public void toStringOfANonExternalEndpointIsLegible() {
        String printed = LineageEndpoint.document(REPO, "d-1", "contract.pdf").toString();
        assertTrue(printed.contains("nemaki://bedroom/objects/d-1"), printed);
        assertTrue(printed.contains("contract.pdf"), printed);
    }

    /** The duplicate-endpoint rejection is the third place a name could have been printed. */
    @Test
    public void theDuplicateEndpointMessageCarriesNoKey() {
        LineageEndpoint endpoint = LineageEndpoint.externalAsset(REPO,
                "https://ext/SECRET-DOCUMENT", "confluence");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LineageCanonicalHash.canonicalQualifiedNames(List.of(endpoint, endpoint)));
        assertFalse(e.getMessage().contains("SECRET-DOCUMENT"), e.getMessage());
        assertFalse(e.getMessage().contains(encoded(endpoint)), e.getMessage());
        assertTrue(e.getMessage().contains("duplicate endpoint"), e.getMessage());
    }

    /** And the qualified-name mismatch, through a directly built endpoint. */
    @Test
    public void theScopeMismatchMessageCarriesNoKey() {
        LineageEndpoint mismatched = new LineageEndpoint(EndpointKind.EXTERNAL_ASSET,
                LineageEndpoint.externalAssetQualifiedName(REPO, "https://ext/NAME-SECRET"),
                REPO, null, null,
                Map.of("sourceSystem", "confluence",
                        LineageEndpoint.ATTR_EXTERNAL_STABLE_KEY, "https://ext/ATTR-SECRET"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LineageRepositoryScope.validateEndpoint(REPO, mismatched, "input"));
        assertFalse(e.getMessage().contains("NAME-SECRET"), e.getMessage());
        assertFalse(e.getMessage().contains("ATTR-SECRET"), e.getMessage());
    }

    private static String encoded(LineageEndpoint endpoint) {
        return endpoint.catalogQualifiedName().substring(
                LineageEndpoint.externalAssetQualifiedNamePrefix(REPO).length());
    }
}
