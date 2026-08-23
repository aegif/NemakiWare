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
package jp.aegif.nemaki.rest.purview.payload;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.rest.purview.payload.CatalogSecretBoundary.SecretAtBoundaryException;

/**
 * The gate refuses; it does not merely decline to set things.
 *
 * <p>Every assertion here is a mutation: something a future producer could plausibly add, which
 * must fail at the boundary rather than travel. A test that only confirms the good cases would
 * pass just as well against a gate that does nothing.
 */
public class CatalogSecretBoundaryTest {

    private static Map<String, Object> attributes(String name, Object value) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("qualifiedName", "nemaki://bedroom/objects/doc-1");
        attributes.put(name, value);
        return attributes;
    }

    private static SecretAtBoundaryException refusalFor(String name, Object value) {
        return assertThrows(SecretAtBoundaryException.class,
                () -> CatalogSecretBoundary.sealed(attributes(name, value)));
    }

    @Nested
    @DisplayName("a stored URL")
    class StoredUrls {

        @Test
        @DisplayName("is refused however innocent it looks")
        void plainHttpsIsRefused() {
            refusalFor("cloudFileUrl", "https://drive.google.com/file/d/abc/view");
        }

        /** The case §4 is actually about: the credential is in the path, not the query. */
        @Test
        @DisplayName("is refused when the token is in the path, not the query")
        void sharingLinkWithTokenInPathIsRefused() {
            refusalFor("someNewAttribute",
                    "https://contoso.sharepoint.com/:x:/g/personal/a_b/EiJ8kTOKENVALUE");
        }

        @Test
        @DisplayName("is refused for any scheme, not a denylist of them")
        void anySchemeIsRefused() {
            refusalFor("someNewAttribute", "s3://bucket/key");
            refusalFor("someNewAttribute", "abfss://container@account.dfs.core.windows.net/x");
            refusalFor("someNewAttribute", "gs://bucket/object");
        }

        @Test
        @DisplayName("is refused when it names a local file")
        void fileSchemeIsRefused() {
            refusalFor("someNewAttribute", "file:///etc/passwd");
            refusalFor("someNewAttribute", "file:/etc/passwd");
        }

        @Test
        @DisplayName("is refused as a Windows path")
        void windowsPathIsRefused() {
            refusalFor("someNewAttribute", "C:\\Users\\alice\\secret.txt");
            refusalFor("someNewAttribute", "D:/data/x");
        }

        @Test
        @DisplayName("is refused inside a list, which is how a multi-valued mapping arrives")
        void refusedInsideAList() {
            refusalFor("someNewAttribute", List.of("harmless", "https://host/path"));
        }
    }

    @Nested
    @DisplayName("our own identity scheme")
    class OwnScheme {

        @Test
        @DisplayName("passes, because a qualified name has to")
        void qualifiedNamePasses() {
            assertDoesNotThrow(() -> CatalogSecretBoundary.sealed(
                    attributes("someNewAttribute", "nemaki://bedroom/folders/f-1/dataset")));
        }

        /** Otherwise the exemption would be the way around the rule. */
        @Test
        @DisplayName("is not a way to smuggle a query, fragment or userinfo")
        void ownSchemeStillRefusesCredentialParts() {
            refusalFor("someNewAttribute", "nemaki://bedroom/objects/d?token=abc");
            refusalFor("someNewAttribute", "nemaki://bedroom/objects/d#fragment");
            refusalFor("someNewAttribute", "nemaki://user:pw@bedroom/objects/d");
        }

        @Test
        @DisplayName("is not a way to smuggle a second scheme")
        void ownSchemeStillRefusesANestedUrl() {
            refusalFor("someNewAttribute", "nemaki://bedroom/objects/https://evil.example/x");
        }
    }

    @Nested
    @DisplayName("an attribute whose name admits to a secret")
    class SecretNames {

        @Test
        @DisplayName("is refused whatever the value is")
        void refusedByNameAlone() {
            refusalFor("accessToken", "not-even-url-shaped");
            refusalFor("clientSecret", "x");
            refusalFor("awsCredential", "x");
            refusalFor("userPassword", "x");
            refusalFor("sasSignature", "x");
            refusalFor("apiKey", "x");
        }
    }

    @Nested
    @DisplayName("the external identity attributes")
    class IdentityAttributes {

        /**
         * These predate the gate and are the asset's identity. Refusing them would not remove a
         * secret; it would break every archive whose lineage resolves through the key.
         */
        @Test
        @DisplayName("keep their scheme and path")
        void schemeAndPathAreKept() {
            assertDoesNotThrow(() -> CatalogSecretBoundary.sealed(
                    attributes("externalStableKey", "s3://archive-bucket/bedroom/doc-001.bin")));
            assertDoesNotThrow(() -> CatalogSecretBoundary.sealed(
                    attributes("externalStableKey", "filesystem:/managed/imports/team-a")));
            assertDoesNotThrow(() -> CatalogSecretBoundary.sealed(
                    attributes("externalPath", "s3://archive-bucket/x")));
            assertDoesNotThrow(() -> CatalogSecretBoundary.sealed(
                    attributes("targetDescription", "s3://archive-bucket/x")));
        }

        @Test
        @DisplayName("still lose the parts a credential rides in")
        void credentialPartsAreStillRefused() {
            refusalFor("externalStableKey", "s3://bucket/key?X-Amz-Signature=deadbeef");
            refusalFor("externalPath", "https://host/path#token");
            refusalFor("targetDescription", "https://user:pw@host/path");
        }

        /** An {@code @} in a path is an ordinary character and must not be mistaken for userinfo. */
        @Test
        @DisplayName("allow an @ that is in the path rather than the authority")
        void atSignInPathIsFine() {
            assertDoesNotThrow(() -> CatalogSecretBoundary.sealed(
                    attributes("externalStableKey", "filesystem:/managed/team@example/a.txt")));
        }

        /** The sharing-link case is closed by there being no attribute, not by this list. */
        @Test
        @DisplayName("do not reopen the cloud sharing-link case")
        void aCloudStableKeyIsNotAUrl() {
            assertDoesNotThrow(() -> CatalogSecretBoundary.sealed(
                    attributes("externalStableKey", "google:cloud-001")));
        }
    }

    @Nested
    @DisplayName("the refusal itself")
    class Reporting {

        /**
         * An exception message becomes a log line, a dead letter and a bug report. A secret that
         * reaches one has been retained just as surely as if it had reached the catalog.
         */
        @Test
        @DisplayName("names the attribute and never the value")
        void theValueNeverAppears() {
            String secret = "https://contoso.sharepoint.com/:x:/g/personal/EiJ8kSUPERSECRET";
            SecretAtBoundaryException refusal = refusalFor("cloudFileUrl", secret);

            assertTrue(refusal.getMessage().contains("cloudFileUrl"));
            assertFalse(refusal.getMessage().contains(secret));
            assertFalse(refusal.getMessage().contains("SUPERSECRET"));
            assertFalse(refusal.getMessage().contains("sharepoint"));
            assertTrue(refusal.getMessage().contains("<redacted:"));
        }

        @Test
        @DisplayName("distinguishes two different secrets without revealing either")
        void twoSecretsGetTwoDigests() {
            String first = refusalFor("cloudFileUrl", "https://host/a").getMessage();
            String second = refusalFor("cloudFileUrl", "https://host/b").getMessage();
            assertFalse(first.equals(second));
        }
    }

    @Nested
    @DisplayName("the user's own display text")
    class UserText {

        /**
         * A CMIS object may legally be named like a URL. Refusing it would protect nothing —
         * the name is already visible to anyone who can see the object — and would make the
         * object unsyncable.
         */
        @Test
        @DisplayName("passes, because a name is what the object is called")
        void nameAndDescriptionPass() {
            assertDoesNotThrow(() -> CatalogSecretBoundary.sealed(
                    attributes("name", "https://example.com/my-research")));
            assertDoesNotThrow(() -> CatalogSecretBoundary.sealed(
                    attributes("description", "see file:///share/readme.txt")));
        }

        /** The exemption is by exact attribute name, not by a name containing "name". */
        @Test
        @DisplayName("does not extend to every attribute with name in it")
        void exemptionIsExact() {
            refusalFor("originalFileName", "https://host/a");
            refusalFor("qualifiedName", "https://host/a");
        }
    }

    @Test
    @DisplayName("sealed returns the very map it was given, so it can wrap an assignment")
    public void sealedIsPassThrough() {
        Map<String, Object> attributes = attributes("name", "a.txt");
        assertEquals(attributes, CatalogSecretBoundary.sealed(attributes));
        assertTrue(attributes == CatalogSecretBoundary.sealed(attributes));
    }

    @Test
    @DisplayName("null attributes are not an error — some relationships have none")
    public void nullIsAllowed() {
        assertEquals(null, CatalogSecretBoundary.sealed(null));
    }
    /**
     * The 2026-08-24 decision that let the lineage sinks call this gate at all: qualifiedName /
     * fullyQualifiedName are IDENTITY — refusing them removes no secret, it breaks every
     * reference through them — but the exemption deliberately excludes http(s), because a
     * sharing link (the §4 threat, token in the PATH) is always http(s) and no legitimate sink
     * identity ever is.
     */
    @Nested
    class QualifiedNameIdentity {

        @Test
        @DisplayName("a canonical source URI passes as identity")
        void canonicalSourceUriPasses() {
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("qualifiedName",
                    "acme-chat://org/W1/channels/C1/messages/1720000000.000200");
            assertEquals(attributes, CatalogSecretBoundary.sealed(attributes));
        }

        @Test
        @DisplayName("nested refs get the same identity treatment — the sinks' real shape")
        void nestedQualifiedNamePasses() {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("uniqueAttributes",
                    Map.of("qualifiedName", "acme-chat://org/W1/channels/C1/messages/1"));
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("inputs", java.util.List.of(ref));
            assertEquals(attributes, CatalogSecretBoundary.sealed(attributes));
        }

        @Test
        @DisplayName("query on a qualified name is still a refusal — identity carries no token")
        void queryStillRefused() {
            refusalFor("qualifiedName", "acme-chat://org/W1/channels/C1?sig=abc");
        }

        @Test
        @DisplayName("Dataplex's colon form with an embedded scheme passes")
        void dataplexFullyQualifiedNamePasses() {
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("fullyQualifiedName",
                    "nemakiware:bedroom:nemaki://bedroom/objects/doc-1");
            assertEquals(attributes, CatalogSecretBoundary.sealed(attributes));
        }
    }

}