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
package jp.aegif.nemaki.cmis.aspect.impl;

import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.GregorianCalendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A datetime property read back from CouchDB must still render through CMIS.
 *
 * <h2>What went wrong</h2>
 *
 * <p>{@code coerceElement}'s DATETIME branch accepted {@code GregorianCalendar}, {@code String}
 * and {@code Long} — but the CouchDB round trip deserializes a JSON number into a
 * <b>{@code Double}</b>. So an aspect datetime rendered correctly while the object sat in the
 * write-path cache and became "not set" the moment it was read back from storage: intact on
 * disk, invisible to every CMIS client and to the UI.
 *
 * <p>Found by the first restore drill (2026-08-24) on {@code nemaki:chatCapturedAt}. The
 * evidence hash normalizes both numeric shapes (P1-1(d) D-1), which is why it kept reporting
 * MATCH while the compiled view reported nothing — the two disagreeing is what made it visible.
 */
class DatetimeAspectPropertySurvivesTheRoundTripTest {

    private static final long STAMP = 1787561714244L;

    private static Object coerce(Object stored) throws Exception {
        CompileServiceImpl service = new CompileServiceImpl();
        Method m = CompileServiceImpl.class.getDeclaredMethod("coerceElement",
                Object.class, PropertyType.class, String.class);
        m.setAccessible(true);
        return m.invoke(service, stored, PropertyType.DATETIME, "nemaki:chatCapturedAt");
    }

    @Test
    @DisplayName("a Double from the CouchDB round trip coerces to the same instant as a Long")
    void aDoubleCoercesLikeALong() throws Exception {
        Object fromCache = coerce(STAMP);                    // Long: the write-path shape
        Object fromStorage = coerce((double) STAMP);         // Double: the read-back shape

        assertInstanceOf(GregorianCalendar.class, fromCache, "control: Long already worked");
        assertInstanceOf(GregorianCalendar.class, fromStorage,
                "a datetime read back from CouchDB rendered as 'not set' — the value is on "
                        + "disk and no CMIS client can see it");
        assertEquals(((GregorianCalendar) fromCache).getTimeInMillis(),
                ((GregorianCalendar) fromStorage).getTimeInMillis(),
                "the two shapes of the same stored instant produced different times");
    }

    @Test
    @DisplayName("the bounds checks still reject garbage, whichever numeric shape it arrives in")
    void boundsChecksStillApply() throws Exception {
        // The counterweight: accepting every Number must not mean accepting nonsense. A rule
        // that coerced anything would pass the test above just as well.
        assertNull(coerce(-1.0d), "a negative instant must still be refused");
        assertNull(coerce(-1L), "…in either shape");
        double farFuture = System.currentTimeMillis() + (200D * 365 * 24 * 60 * 60 * 1000);
        assertNull(coerce(farFuture), "an instant 200 years out must still be refused");
    }

    @Test
    @DisplayName("a GregorianCalendar passes through untouched — the shape the cache holds")
    void calendarPassesThrough() throws Exception {
        GregorianCalendar cal = new GregorianCalendar();
        cal.setTimeInMillis(STAMP);

        assertEquals(cal, coerce(cal));
    }
}
