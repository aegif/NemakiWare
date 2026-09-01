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
package jp.aegif.nemaki.rest.importexport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.util.test.JavaSource;

/**
 * The export refusals reach the client instead of being logged over.
 *
 * <h2>Why the exporter's refusal was not enough on its own</h2>
 *
 * <p>{@code ZipExporter} was made to abort rather than finish an archive that lost something.
 * The resource that streams it wrapped three of those calls in {@code catch (Exception) →
 * log.warn}, so the refusal was written to a log file and the archive was finished anyway —
 * the same re-flattening one layer up that this batch has now hit in {@code UserController},
 * {@code DelegatedCallContextFactory}, {@code TypeServiceImpl} and
 * {@code CatalogPropertyMappingResolver}.
 *
 * <p>Asserted on the SOURCE because the three call sites live inside an anonymous
 * {@code StreamingOutput} in a Jersey resource: reaching them behaviourally means standing up
 * the resource, and what has to hold is a property of the catch blocks themselves.
 */
class ExportRefusalReachesTheClientTest {

    private static final String SOURCE =
            "src/main/java/jp/aegif/nemaki/rest/ImportExportResource.java";

    @Test
    @DisplayName("the type-definition export refusal is not swallowed")
    void theTypeDefinitionRefusalIsNotSwallowed() throws Exception {
        String source = JavaSource.withoutComments(JavaSource.read(SOURCE));
        assertFalse(source.contains("log.warn(\"Failed to export type definitions: \""),
                "the resource logs the refusal and finishes the archive: an importer then "
                        + "gets a package naming custom types it does not carry");
        assertTrue(source.contains("the type definitions this archive refers to could not be"),
                "the refusal no longer travels to the client at all");
    }

    @Test
    @DisplayName("the relationship and custom-type collection refusals are not swallowed")
    void theOtherTwoRefusalsAreNotSwallowed() throws Exception {
        String source = JavaSource.withoutComments(JavaSource.read(SOURCE));
        assertFalse(source.contains("log.warn(\"Failed to export relationships: \""),
                "a failed relationship export still finishes the archive, which then says "
                        + "the exported objects have no relationships");
        assertFalse(source.contains("log.warn(\"Failed to collect custom type definitions: \""),
                "the walk that decides which type definitions the archive needs can fail and "
                        + "still produce a package that unpacks");
    }
}
