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

import jp.aegif.nemaki.model.Configuration;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The side that PRODUCES the failed-read marker.
 *
 * <p>{@code ConfigurationReadFailureTest} and {@code IngestLineageDisabledIsNotSilentTest} both
 * mock the read, so neither would notice if the marker stopped being set here — and then the whole
 * chain would go quiet again while still passing. This covers the two branches that manufacture an
 * empty {@link Configuration} out of a failure.
 */
class ConfigurationLoadFailureMarkedTest {

    private static ContentDaoServiceImpl daoWith(CloudantClientPool pool) {
        ContentDaoServiceImpl dao = new ContentDaoServiceImpl();
        dao.setConnectorPool(pool);
        return dao;
    }

    @Test
    @DisplayName("an unavailable configuration database is marked, not returned as empty")
    void unavailableClientIsMarked() {
        // The startup case: nemaki_conf is not up yet. Returning a plain empty configuration here
        // is what let one unreachable moment be cached for the life of the JVM.
        CloudantClientPool pool = mock(CloudantClientPool.class);
        when(pool.getClient(anyString())).thenReturn(null);

        assertTrue(daoWith(pool).getConfiguration("bedroom").isLoadFailed());
    }

    @Test
    @DisplayName("a read that throws is marked")
    void throwingReadIsMarked() {
        CloudantClientPool pool = mock(CloudantClientPool.class);
        when(pool.getClient(anyString())).thenThrow(new RuntimeException("connection refused"));

        assertTrue(daoWith(pool).getConfiguration("bedroom").isLoadFailed());
    }

    @Test
    @DisplayName("a fresh Configuration is not marked")
    void unmarkedByDefault() {
        // The control: if the field defaulted to true, every one of these tests would pass while
        // meaning nothing, and every successful read would be treated as a failure.
        assertFalse(new Configuration().isLoadFailed());
    }
}
