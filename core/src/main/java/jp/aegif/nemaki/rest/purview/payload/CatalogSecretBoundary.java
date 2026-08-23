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

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import jp.aegif.nemaki.rest.purview.journal.LineageEndpoint;

/**
 * The last gate before an entity payload leaves for the catalog (増分 B, §4).
 *
 * <h2>Why a gate and not a convention</h2>
 *
 * <p>§4 fixed that no stored URL crosses into Atlas, because a SharePoint sharing link keeps its
 * token in the <em>path</em> — stripping a query does not make one safe, and no transformation of
 * a stored URL can be shown to be. Until now that was enforced by each producer choosing not to
 * set one: {@code cloudFileUrl} is assigned {@code null}, the mapping resolver refuses
 * {@code nemaki:cloudFileUrl} as a source, and the endpoint allowlists have nowhere to put a URL.
 * Every one of those is a place someone can add a line.
 *
 * <p>This is the place they cannot. Every entity payload this factory builds passes through
 * {@link #sealed}, and a value shaped like a location or a credential is refused rather than
 * logged — a warning would leave the payload on its way.
 *
 * <h2>What it refuses</h2>
 *
 * <ul>
 *   <li>Any {@code scheme://…} value except our own {@code nemaki://} identity scheme, which has
 *       no host, no query and no credential. That covers {@code https}, {@code file},
 *       {@code s3} and anything else, so a new provider needs no new rule.</li>
 *   <li>A {@code nemaki://} value that nonetheless carries {@code ?}, {@code #} or userinfo —
 *       our own scheme is not a way around the rule.</li>
 *   <li>A Windows drive path ({@code C:\…}), and a bare {@code file:} scheme.</li>
 *   <li>Any value at all under an attribute whose <em>name</em> says secret — token, secret,
 *       credential, password, sas, signature, apikey. The name is the producer's own admission.</li>
 * </ul>
 *
 * <h2>What it deliberately does not refuse</h2>
 *
 * <p>A POSIX absolute path such as {@code /mnt/import/a.txt} is not refused by shape, because a
 * CMIS {@code folderPath} is also {@code /a/b} and nothing in the string distinguishes them.
 * That case is handled where it can be: no artifact, folder-companion or external-asset
 * attribute accepts a local path, and {@code EndpointKindSchemaAlignmentTest} refuses to let one
 * be declared. This class does not pretend to cover it.
 *
 * <h2>Reporting</h2>
 *
 * <p>The refusal names the attribute and a 12-hex redaction digest of the value, never the value.
 * An exception message is a log line, a dead letter and a bug report; a secret that reaches one
 * has been retained just as surely as if it had reached Atlas.
 */
public final class CatalogSecretBoundary {

    /** {@code scheme://} — RFC 3986's scheme grammar, followed by an authority. */
    private static final Pattern SCHEME_WITH_AUTHORITY =
            Pattern.compile("\\b[a-zA-Z][a-zA-Z0-9+.-]*://");

    /** Our own identity scheme, the one {@code qualifiedName} is built from. */
    private static final String OWN_SCHEME = "nemaki://";

    /** {@code C:\} or {@code C:/} at the start — a Windows absolute path. */
    private static final Pattern WINDOWS_DRIVE = Pattern.compile("^[A-Za-z]:[\\\\/]");

    /** A scheme that names a local file even without an authority. */
    private static final Pattern FILE_SCHEME = Pattern.compile("(?i)^file:");

    /** Attribute names that admit to holding a secret, whatever the value looks like. */
    private static final List<String> SECRET_NAMES =
            List.of("token", "secret", "credential", "password", "apikey", "sas", "signature");

    /**
     * The four attributes whose value <em>is</em> an external identity, and predates this gate.
     *
     * <p>{@code externalStableKey} legitimately holds {@code s3://archive-bucket/…} for a cold
     * storage reference and {@code filesystem:/managed/imports/team-a} for a managed mount, and
     * {@code externalPath} / {@code targetDescription} / {@code sourceDescription} carry the same
     * value. Both shapes are operator-configured infrastructure locations, not user-supplied
     * links, and both are the asset's identity — refusing them would not remove a secret, it
     * would break the identity every archive's lineage already resolves through.
     *
     * <p>The exemption is only for the scheme and the path. Query, fragment and userinfo are
     * still refused here, because those are where a credential rides. And it does not reopen the
     * sharing-link case §4 was written for: a cloud object's stable key is
     * {@code {provider}:{fileId}} with no authority at all, so a drive URL has no attribute to
     * arrive in whether or not this list exists.
     */
    private static final List<String> IDENTITY_ATTRIBUTES = List.of(
            "externalStableKey", "externalPath", "targetDescription", "sourceDescription",
            // Decision (P1-1 §5 「CatalogSecretBoundary 統合」, 2026-08-24): the lineage sinks'
            // qualifiedName IS the asset's identity, and for a v1 legacy external asset it is a
            // canonical source URI ({system}://org/{workspace}/channels/... — CONSTRUCTED from
            // connector + ids by ExternalSourceUri.build, never a stored/pasted link). Refusing
            // it would not remove a secret; it would break the identity every process reference
            // resolves through — the same reasoning externalStableKey has carried all along.
            // The credential-bearing parts (query / fragment / userinfo) stay refused — and so
            // do http(s) values (see the qualified-name branch in check): a sharing link is an
            // http(s) URL with its token in the PATH, and no legitimate sink identity is ever
            // http(s), so the §4 threat class stays closed for exactly these two names.
            "qualifiedName",
            // Dataplex's identity form: "nemakiware:{repo}:{qualifiedName}" — embeds a scheme
            // mid-string, which the stored-URL check would refuse; same identity reasoning.
            "fullyQualifiedName");

    /**
     * The user's own display text, which this gate does not get to judge.
     *
     * <p>A CMIS object may legally be named {@code https://example.com/x}, and the catalog's
     * job is to show what the object is called. Refusing it would not protect a secret — the
     * name is already visible in the repository to anyone who can see the object — it would
     * simply make the folder unsyncable, and a rule that breaks on valid user data gets
     * removed rather than fixed.
     *
     * <p>This is not a hole to route a stored URL through. A value put here becomes the
     * entity's displayed name, which is the opposite of silent retention, and every attribute
     * that exists to hold a system-chosen location is still checked.
     */
    private static final List<String> USER_TEXT_ATTRIBUTES = List.of("name", "description");

    private CatalogSecretBoundary() {
    }

    /** Refused because a value would have carried a location or a credential to the catalog. */
    public static final class SecretAtBoundaryException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        SecretAtBoundaryException(String message) {
            super(message);
        }
    }

    /**
     * The same attributes, once nothing in them can reach the catalog that must not.
     *
     * @return {@code attributes}, unchanged, so this can wrap an existing assignment
     * @throws SecretAtBoundaryException if any value is shaped like a location or a credential
     */
    public static Map<String, Object> sealed(Map<String, Object> attributes) {
        if (attributes == null) {
            return null;
        }
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            check(entry.getKey(), entry.getKey(), entry.getValue());
        }
        return attributes;
    }

    private static void check(String name, String leafName, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof List<?> list) {
            for (Object element : list) {
                check(name, leafName, element);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                check(name + "." + entry.getKey(), String.valueOf(entry.getKey()),
                        entry.getValue());
            }
            return;
        }
        if (!(value instanceof String text) || text.isEmpty()) {
            return;
        }
        String lowerName = name.toLowerCase(Locale.ROOT);
        for (String secret : SECRET_NAMES) {
            if (lowerName.contains(secret)) {
                throw refusal(name, text, "its name says it holds a secret");
            }
        }
        // Nested maps arrive with dotted names (inputs.uniqueAttributes.qualifiedName); the
        // user-text and identity exemptions go by the LEAF attribute name, because the class of
        // the value is the leaf's, wherever the map nests it. The leaf is the KEY the traversal
        // actually took — never recomputed by splitting the dotted string, because a top-level
        // attribute is free to be NAMED "x.name" (custom property mappings accept any name) and
        // splitting would hand it the exemption. The secret-name check above stays on the FULL
        // dotted name — broader is stricter there.
        if (USER_TEXT_ATTRIBUTES.contains(leafName)) {
            return;
        }
        if (IDENTITY_ATTRIBUTES.contains(leafName)) {
            // A qualified name is never legitimately http(s) — canonical source URIs use the
            // source system as the scheme, and every other identity is nemaki:// or colon-form.
            // A sharing link, the §4 threat, IS http(s) with its token in the path; keeping
            // this refusal is what lets the rest of the exemption exist.
            if (("qualifiedName".equals(leafName) || "fullyQualifiedName".equals(leafName))
                    && (text.startsWith("http://") || text.startsWith("https://"))) {
                throw refusal(name, text, "a qualified name is never http(s); a stored URL's"
                        + " token can be in the path (§4)");
            }
            // Scheme and path allowed; the credential-bearing parts are not.
            if (text.indexOf('?') >= 0 || text.indexOf('#') >= 0 || hasUserinfo(text)) {
                throw refusal(name, text, "an external identity carries no query, fragment or"
                        + " userinfo — those are where a credential rides");
            }
            return;
        }
        if (WINDOWS_DRIVE.matcher(text).find()) {
            throw refusal(name, text, "it is a local absolute path");
        }
        if (FILE_SCHEME.matcher(text).find()) {
            throw refusal(name, text, "it names a local file");
        }
        var scheme = SCHEME_WITH_AUTHORITY.matcher(text);
        if (!scheme.find()) {
            return;
        }
        if (!text.startsWith(OWN_SCHEME) || SCHEME_WITH_AUTHORITY.matcher(
                text.substring(OWN_SCHEME.length())).find()) {
            throw refusal(name, text, "it is a stored URL, and §4 forbids one at this boundary"
                    + " because the token can be in the path");
        }
        // Our own scheme, but it must stay an identity: no query, no fragment, no userinfo.
        String rest = text.substring(OWN_SCHEME.length());
        if (rest.indexOf('?') >= 0 || rest.indexOf('#') >= 0 || rest.indexOf('@') >= 0) {
            throw refusal(name, text, "a nemaki:// identity carries no query, fragment or"
                    + " userinfo — this one does");
        }
    }

    /**
     * Whether a {@code scheme://user:pass@host/…} authority carries userinfo.
     *
     * <p>Only the authority is examined — an {@code @} later in the path is an ordinary
     * character, and a stable key such as {@code filesystem:/a/b@c} must not be refused for it.
     */
    private static boolean hasUserinfo(String text) {
        int authority = text.indexOf("://");
        if (authority < 0) {
            return false;
        }
        int start = authority + 3;
        int end = text.indexOf('/', start);
        String host = end < 0 ? text.substring(start) : text.substring(start, end);
        return host.indexOf('@') >= 0;
    }

    /** The value never appears; a redaction digest is enough to tell two occurrences apart. */
    private static SecretAtBoundaryException refusal(String name, String value, String why) {
        return new SecretAtBoundaryException("refusing to publish attribute '" + name
                + "' (value <redacted:" + LineageEndpoint.shortDigest(value) + ">): " + why);
    }
}
