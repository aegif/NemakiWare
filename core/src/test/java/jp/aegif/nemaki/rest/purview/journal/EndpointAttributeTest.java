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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The attribute declaration itself.
 *
 * <p>{@link EndpointKind} builds its whole table from these three factories inside an enum's
 * static initialiser, which runs once per JVM — so a fault in them shows up as an
 * {@code ExceptionInInitializerError} in an unrelated test rather than as anything readable.
 * Exercising them directly keeps the failure where the cause is.
 */
public class EndpointAttributeTest {

    @Test
    public void theFactoriesBuildWhatTheyName() {
        EndpointAttribute required = EndpointAttribute.requiredText("name");
        assertEquals("name", required.name());
        assertEquals(EndpointAttribute.Type.TEXT, required.type());
        assertTrue(required.required());

        EndpointAttribute optional = EndpointAttribute.text("mimeType");
        assertEquals("mimeType", optional.name());
        assertEquals(EndpointAttribute.Type.TEXT, optional.type());
        assertFalse(optional.required());

        EndpointAttribute count = EndpointAttribute.count("byteLength");
        assertEquals("byteLength", count.name());
        assertEquals(EndpointAttribute.Type.COUNT, count.type());
        assertFalse(count.required());

        EndpointAttribute requiredCount = EndpointAttribute.requiredCount("archivedAt");
        assertEquals("archivedAt", requiredCount.name());
        assertEquals(EndpointAttribute.Type.COUNT, requiredCount.type());
        assertTrue(requiredCount.required());
    }

    @Test
    public void textRejectsAnythingThatIsNotNonBlankText() {
        EndpointAttribute name = EndpointAttribute.requiredText("name");
        assertThrows(IllegalArgumentException.class,
                () -> name.validate(null, EndpointKind.CMIS_DOCUMENT));
        assertThrows(IllegalArgumentException.class,
                () -> name.validate(" ", EndpointKind.CMIS_DOCUMENT));
        assertThrows(IllegalArgumentException.class,
                () -> name.validate(1L, EndpointKind.CMIS_DOCUMENT));
        assertDoesNotThrow(() -> name.validate("contract.pdf", EndpointKind.CMIS_DOCUMENT));
    }

    @Test
    public void countRejectsAnythingThatIsNotANonNegativeWholeNumber() {
        EndpointAttribute length = EndpointAttribute.count("contentLength");
        assertThrows(IllegalArgumentException.class,
                () -> length.validate("1024", EndpointKind.CMIS_DOCUMENT));
        assertThrows(IllegalArgumentException.class,
                () -> length.validate(1.5d, EndpointKind.CMIS_DOCUMENT));
        assertThrows(IllegalArgumentException.class,
                () -> length.validate(-1L, EndpointKind.CMIS_DOCUMENT));
        assertDoesNotThrow(() -> length.validate(0, EndpointKind.CMIS_DOCUMENT));
        assertDoesNotThrow(() -> length.validate(Long.MAX_VALUE, EndpointKind.CMIS_DOCUMENT));
    }
}
