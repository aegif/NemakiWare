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
package jp.aegif.nemaki.rest.purview;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * A display URL stripped of everything that can carry a credential.
 *
 * <h2>Why this is not {@link ExternalAssetIdentity#parse}</h2>
 *
 * <p>Identity keys and display URLs are different contracts. A cloud drive legitimately addresses
 * files through query parameters — Google's {@code ?id=…}, OneDrive and SharePoint sharing links —
 * so the identity rule "no query strings" cannot be reused for URLs: it would reject the drive's
 * own spelling of a valid file. But those same query parameters are exactly where sharing tokens
 * live ({@code ?authkey=…}), and a URL the drive API accepts is not thereby safe to persist in a
 * catalog entity that many more people can read.
 *
 * <h2>What survives</h2>
 *
 * <p>Scheme, host, port and path. Userinfo, query and fragment are dropped. If the value is not
 * an http(s) URL with a host, or carries userinfo, there is no safe display form and the result
 * is {@code null} — never a "repaired" version of a credentialed URL, because a repair that keeps
 * most of a link invites trusting all of it.
 *
 * <p>A provider-canonical URL rebuilt from the file id (rather than stripped from the stored one)
 * is the increment-B upgrade; this is the floor that keeps secrets out until then.
 */
public final class SafeDisplayUrl {

    private SafeDisplayUrl() {
    }

    /** @return the stripped URL, or {@code null} when no safe display form exists. */
    public static String of(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(rawUrl.strip());
        } catch (URISyntaxException e) {
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return null;
        }
        if (uri.getUserInfo() != null || uri.getHost() == null) {
            return null;
        }
        StringBuilder safe = new StringBuilder(scheme.toLowerCase()).append("://")
                .append(uri.getHost());
        if (uri.getPort() != -1) {
            safe.append(':').append(uri.getPort());
        }
        if (uri.getRawPath() != null) {
            safe.append(uri.getRawPath());
        }
        return safe.toString();
    }
}
