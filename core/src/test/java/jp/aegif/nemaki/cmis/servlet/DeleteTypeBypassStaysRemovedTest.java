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
package jp.aegif.nemaki.cmis.servlet;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.util.test.JavaSource;

/**
 * Browser Binding {@code deleteType} has exactly one path: the standard CMIS pipeline.
 *
 * <h2>What was removed, and why its return matters</h2>
 *
 * <p>Two dead artefacts of the abandoned OpenCMIS 1.2.0-SNAPSHOT era used to sit beside the
 * pipeline: {@code DeleteTypeFilter} (registration commented out in web.xml, and its
 * {@code doFilter} an unconditional pass-through even when instantiated) and the servlet's
 * {@code handleDeleteTypeDirectly} (zero callers). Both called
 * {@code TypeService.deleteTypeDefinition} DIRECTLY — skipping
 * {@code TypeManager.deleteTypeDefinition} and with it {@code findChildTypes}, the dependency
 * check that refuses deleting a type whose children still reference it.
 *
 * <p>Dead code cannot fire, but scaffolding invites revival: a future "fix" re-enabling either
 * would silently reopen a type deletion without the dependency check. A remnant review judged
 * them debris rather than remnants, so they were removed; this lock is what notices them
 * coming back. The one sanctioned bypass is the REST admin API ({@code TypeResource}), which
 * owns its own, explicitly non-CMIS contract and is out of this lock's scope.
 */
class DeleteTypeBypassStaysRemovedTest {

    @Test
    @DisplayName("the dead DeleteTypeFilter does not come back")
    void theFilterStaysGone() {
        // Files.exists, not JavaSource.read: the healthy state IS the file's absence, and
        // JavaSource.read treats a missing file as harness breakage.
        assertFalse(Files.exists(Path.of(
                        "src/main/java/jp/aegif/nemaki/cmis/servlet/DeleteTypeFilter.java")),
                "DeleteTypeFilter is back. Its delete logic calls TypeService directly and "
                        + "skips findChildTypes; even as a pass-through it is the scaffold "
                        + "that invites re-enabling that bypass");
    }

    @Test
    @DisplayName("the servlet carries no direct type deletion")
    void theServletHasNoDirectTypeDeletion() throws Exception {
        String source = JavaSource.withoutComments(JavaSource.read(
                "src/main/java/jp/aegif/nemaki/cmis/servlet/NemakiBrowserBindingServlet.java"));
        assertFalse(source.contains("handleDeleteTypeDirectly"),
                "the direct deleteType handler is back in the Browser Binding servlet");
        assertFalse(source.contains("deleteTypeDefinition("),
                "the Browser Binding servlet calls a type deletion directly, skipping the "
                        + "standard pipeline's findChildTypes dependency check");
    }

    @Test
    @DisplayName("web.xml does not register the filter, not even commented out")
    void theRegistrationScaffoldIsGone() throws Exception {
        // Case-insensitive, because the two halves of the scaffold spell it differently:
        // the <filter> block names the CLASS (DeleteTypeFilter) and the <filter-mapping>
        // block names the filter-name (deleteTypeFilter). The first version checked only
        // the class spelling — and the first removal missed only the mapping block, so the
        // lock sat green on exactly the state it forbids. A review caught the pair.
        String webXml = JavaSource.read("src/main/webapp/WEB-INF/web.xml")
                .toLowerCase(java.util.Locale.ROOT);
        assertFalse(webXml.contains("deletetypefilter"),
                "the filter scaffold (registration or mapping) is back in web.xml — "
                        + "commented out is one keystroke from live");
    }
}
