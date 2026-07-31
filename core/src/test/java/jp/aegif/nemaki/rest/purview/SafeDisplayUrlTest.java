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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * The display-URL contract: what may be shown of a stored cloud URL.
 *
 * <p>Deliberately not {@link ExternalAssetIdentity#parse}: a drive addresses files through query
 * parameters, so "no query string" cannot be an acceptance rule for URLs — it is a stripping rule.
 */
public class SafeDisplayUrlTest {

    /** The finding's case: a sharing token in the query string of a stored URL. */
    @Test
    public void aSharingTokenIsStripped() {
        assertEquals("https://tenant.sharepoint.com/personal/u/Documents/plan.docx",
                SafeDisplayUrl.of(
                        "https://tenant.sharepoint.com/personal/u/Documents/plan.docx?authkey=SECRET"));
    }

    /** Google's own spelling is query-addressed; the identity rule would reject it outright. */
    @Test
    public void aQueryAddressedUrlIsStrippedNotRejected() {
        assertEquals("https://drive.google.com/open",
                SafeDisplayUrl.of("https://drive.google.com/open?id=abc123"));
    }

    @Test
    public void aFragmentIsStripped() {
        assertEquals("https://host/p", SafeDisplayUrl.of("https://host/p#section"));
    }

    /** A credentialed URL is not repaired: keeping most of a link invites trusting all of it. */
    @Test
    public void userinfoMeansNoSafeFormExists() {
        assertNull(SafeDisplayUrl.of("https://user:pw@host/p"));
    }

    /** Only http(s) URLs with a host have a display form at all. */
    @Test
    public void nonHttpValuesHaveNoDisplayForm() {
        assertNull(SafeDisplayUrl.of("file-1"));
        assertNull(SafeDisplayUrl.of("ftp://host/p"));
        assertNull(SafeDisplayUrl.of("onedrive:file-9"));
        assertNull(SafeDisplayUrl.of(null));
        assertNull(SafeDisplayUrl.of(" "));
        assertNull(SafeDisplayUrl.of("https://exa mple/x"));
        assertNull(SafeDisplayUrl.of("https:///no-host"));
    }

    /** A clean URL passes through unchanged, so existing secretless links keep working. */
    @Test
    public void aCleanUrlIsUnchanged() {
        assertEquals("https://drive.example/doc-001",
                SafeDisplayUrl.of("https://drive.example/doc-001"));
        assertEquals("https://host:8443/deep/path",
                SafeDisplayUrl.of("https://host:8443/deep/path?x=1"));
        assertEquals("http://host/p", SafeDisplayUrl.of("HTTP://host/p"));
    }
}
