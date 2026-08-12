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
package jp.aegif.nemaki.dao.impl.couch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;

/**
 * "This type has no instances" is an ordinary answer, not an error.
 *
 * <h2>Why the assertion is on the log</h2>
 *
 * <p>{@code existContent} answers false either way, so asserting the return value cannot fail.
 * The keyed {@code queryView} overload returns {@code null} when the key matches nothing — its
 * documented contract, which callers branch on — and {@code existContent} used to dereference
 * that straight away. The resulting NullPointerException was caught by its own catch block, which
 * logs at ERROR and returns false. So the answer was right, arrived at by throwing and logging an
 * error on the most ordinary input there is: a type with nothing in it.
 *
 * <p>That matters beyond noise. Ledger item V2 proposes wiring
 * {@code TypeManagerImpl.checkTypeHasInstances} to this method; wiring a method whose
 * no-instances path runs through an exception handler is how a genuine failure later becomes
 * indistinguishable from an empty result.
 *
 * <p>Restoring the dereference makes {@link #anAbsentTypeIsNotAnError()} fail.
 */
public class ExistContentNullResultTest {

    private static final String REPO = "bedroom";

    private ListAppender<ILoggingEvent> appender;
    private Logger daoLogger;

    @BeforeEach
    public void attachAppender() {
        daoLogger = (Logger) LoggerFactory.getLogger(ContentDaoServiceImpl.class);
        appender = new ListAppender<>();
        appender.start();
        daoLogger.addAppender(appender);
        daoLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    public void detachAppender() {
        daoLogger.detachAppender(appender);
    }

    private ContentDaoServiceImpl daoAnswering(ViewResult result) {
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.queryView(anyString(), anyString(), anyString())).thenReturn(result);
        CloudantClientPool pool = mock(CloudantClientPool.class);
        when(pool.getClient(anyString())).thenReturn(client);

        ContentDaoServiceImpl dao = new ContentDaoServiceImpl();
        dao.setConnectorPool(pool);
        return dao;
    }

    private long errorsLogged() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).count();
    }

    /** The case the fix is about: no instances, so null, so false — quietly. */
    @Test
    public void anAbsentTypeIsNotAnError() {
        ContentDaoServiceImpl dao = daoAnswering(null);

        assertFalse(dao.existContent(REPO, "nemaki:typeWithNoInstances"));
        assertEquals(0, errorsLogged(),
                "a type with no instances is the ordinary case; reaching the answer by "
                        + "dereferencing null and catching the NPE logs an error every time");
    }

    /** A type that does have instances still answers true. */
    @Test
    public void aPopulatedTypeIsStillReported() {
        ViewResult result = mock(ViewResult.class);
        when(result.getRows()).thenReturn(List.of(mock(ViewResultRow.class)));

        assertTrue(daoAnswering(result).existContent(REPO, "cmis:document"));
        assertEquals(0, errorsLogged());
    }

    /**
     * A non-null result whose row list is null is also "no instances" — the reduce view can only
     * produce rows or nothing, but the guard has to cover both shapes or it just moves the NPE.
     */
    @Test
    public void aResultWithNoRowListIsAlsoNotAnError() {
        ViewResult result = mock(ViewResult.class);
        when(result.getRows()).thenReturn(null);

        assertFalse(daoAnswering(result).existContent(REPO, "nemaki:whatever"));
        assertEquals(0, errorsLogged());
    }

    /** An empty row list means the key matched nothing. Still false, still not an error. */
    @Test
    public void anEmptyRowListIsNotAnError() {
        ViewResult result = mock(ViewResult.class);
        when(result.getRows()).thenReturn(List.of());

        assertFalse(daoAnswering(result).existContent(REPO, "nemaki:whatever"));
        assertEquals(0, errorsLogged());
    }
}
