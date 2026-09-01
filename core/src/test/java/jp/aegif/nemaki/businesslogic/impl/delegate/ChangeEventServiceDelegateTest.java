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
package jp.aegif.nemaki.businesslogic.impl.delegate;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Change;

import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The CMIS change feed must not advance a client's token over changes it never received.
 *
 * <h2>Why the guard lives HERE and not only in the Purview sync</h2>
 *
 * <p>compileChangeDataList advances the client's changeLogToken over the consecutive range it
 * receives — it can guard against compile failures INSIDE the list, but a row the DAO already
 * dropped looks perfectly consecutive from there. Serving {@code [100, 102]} advances the client
 * to 102 and the change at 101 is never delivered to that client again; a dropped DELETE leaves
 * the object on the client for ever. The Purview sync got this guard one round earlier; this —
 * the path every external CMIS subscriber uses — is the sibling the review found unguarded.
 */
class ChangeEventServiceDelegateTest {

    private static final String REPO = "bedroom";

    private static Change change(String token) {
        Change c = new Change();
        c.setToken(token);
        return c;
    }

    @Test
    @DisplayName("a dropped change row refuses the feed instead of advancing the client past it")
    void aDroppedRowRefusesTheFeed() {
        ContentDaoService dao = mock(ContentDaoService.class);
        when(dao.getLatestChanges(anyString(), anyString(), anyInt()))
                .thenReturn(new ArrayList<>(List.of(change("100"), change("102"))));
        when(dao.lastUnreadableChangeCount()).thenReturn(1);
        ChangeEventServiceDelegate delegate = new ChangeEventServiceDelegate(dao);

        assertThrows(IllegalStateException.class,
                () -> delegate.getLatestChanges(REPO, null, new Holder<>("100"), false, null,
                        false, false, BigInteger.valueOf(10), null),
                "the feed served a list with a hole in it, so the client's changeLogToken "
                        + "advances past a change it never received");
    }

    @Test
    @DisplayName("skipFirst drops only the row that WAS already delivered")
    void skipFirstOnlyDropsTheDeliveredRow() {
        // The unconditional remove(0) assumed position 0 is always the startToken's own event.
        // When that row is gone (purged between calls), position 0 holds the NEXT real change,
        // and dropping it swallowed one event per resume. Purview's normalizeChanges has
        // checked the token for as long as it has existed; this sibling did not.
        ContentDaoService dao = mock(ContentDaoService.class);
        when(dao.getLatestChanges(anyString(), anyString(), anyInt()))
                .thenReturn(new ArrayList<>(List.of(change("101"), change("102"))));
        when(dao.lastUnreadableChangeCount()).thenReturn(0);
        ChangeEventServiceDelegate delegate = new ChangeEventServiceDelegate(dao);

        List<Change> served = delegate.getLatestChanges(REPO, null, new Holder<>("100"), false,
                null, false, false, BigInteger.valueOf(10), null);

        assertEquals(2, served.size(),
                "the first row was dropped even though it is NOT the already-delivered "
                        + "startToken event: " + served);
        assertEquals("101", served.get(0).getToken());
    }

    @Test
    @DisplayName("a null-token first row is NOT the delivered event and is kept")
    void aNullTokenFirstRowIsKept() {
        // The first version of the fix removed the row when its token was null too — treating
        // "this row has no token" as "this is the one we already delivered", which
        // delivered-and-dropped a change nobody had seen. Equality only, like Purview's
        // normalizeChanges: null matches nothing.
        ContentDaoService dao = mock(ContentDaoService.class);
        when(dao.getLatestChanges(anyString(), anyString(), anyInt()))
                .thenReturn(new ArrayList<>(List.of(change(null), change("102"))));
        when(dao.lastUnreadableChangeCount()).thenReturn(0);
        ChangeEventServiceDelegate delegate = new ChangeEventServiceDelegate(dao);

        List<Change> served = delegate.getLatestChanges(REPO, null, new Holder<>("100"), false,
                null, false, false, BigInteger.valueOf(10), null);

        assertEquals(2, served.size(),
                "a first row with no token was dropped as 'already delivered': " + served);
    }

    @Test
    @DisplayName("a MAX_VALUE page with a resume token does not overflow into 'no limit'")
    void aHugeMaxItemsDoesNotOverflow() {
        // skipFirst adds one to the fetch limit; Integer.MAX_VALUE + 1 wraps negative, and a
        // non-positive limit reads as "no limit" one layer down — an accidental unbounded
        // query over a change log that is measured in the hundreds of thousands of rows.
        ContentDaoService dao = mock(ContentDaoService.class);
        when(dao.getLatestChanges(anyString(), anyString(), anyInt()))
                .thenReturn(new ArrayList<>(List.of(change("100"), change("101"))));
        when(dao.lastUnreadableChangeCount()).thenReturn(0);
        ChangeEventServiceDelegate delegate = new ChangeEventServiceDelegate(dao);

        delegate.getLatestChanges(REPO, null, new Holder<>("100"), false, null, false, false,
                BigInteger.valueOf(Integer.MAX_VALUE), null);

        org.mockito.Mockito.verify(dao).getLatestChanges(REPO, "100", Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("a maxItems beyond int range is clamped, not truncated into 'no limit'")
    void anOutOfRangeMaxItemsIsClamped() {
        // intValue() TRUNCATES: 2^31 becomes MIN_VALUE and 2^32 becomes 0, and a
        // non-positive limit used to mean an unbounded query. Found live by the round-32
        // review right after the in-range overflow was fixed — the sibling nobody asked.
        ContentDaoService dao = mock(ContentDaoService.class);
        when(dao.getLatestChanges(anyString(), anyString(), anyInt()))
                .thenReturn(new ArrayList<>(List.of(change("100"))));
        when(dao.lastUnreadableChangeCount()).thenReturn(0);
        ChangeEventServiceDelegate delegate = new ChangeEventServiceDelegate(dao);

        delegate.getLatestChanges(REPO, null, new Holder<>("100"), false, null, false, false,
                BigInteger.valueOf(Integer.MAX_VALUE).add(BigInteger.ONE), null);

        org.mockito.Mockito.verify(dao).getLatestChanges(REPO, "100", Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("maxItems=0 with a resume token still pages — not a one-row treadmill")
    void aResumedZeroAskStillPagesFully() {
        // The round-33 review's loop: limit=0 + a token computed fetchLimit=1, the single
        // fetched row was the already-delivered resume row, skipFirst removed it, and a
        // polling client received the same empty page with an unmoved token for ever —
        // while hasMoreItems stayed true. Non-positive normalises to a full page BEFORE
        // the resume adjustment.
        ContentDaoService dao = mock(ContentDaoService.class);
        when(dao.getLatestChanges(anyString(), anyString(), anyInt()))
                .thenReturn(new ArrayList<>(List.of(change("100"), change("101"))));
        when(dao.lastUnreadableChangeCount()).thenReturn(0);
        ChangeEventServiceDelegate delegate = new ChangeEventServiceDelegate(dao);

        delegate.getLatestChanges(REPO, null, new Holder<>("100"), false, null, false, false,
                BigInteger.ZERO, null);

        org.mockito.Mockito.verify(dao).getLatestChanges(REPO, "100", Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("the ordinary resume still skips the duplicate first row — the control")
    void theOrdinaryResumeStillSkips() {
        ContentDaoService dao = mock(ContentDaoService.class);
        when(dao.getLatestChanges(anyString(), anyString(), anyInt()))
                .thenReturn(new ArrayList<>(List.of(change("100"), change("101"))));
        when(dao.lastUnreadableChangeCount()).thenReturn(0);
        ChangeEventServiceDelegate delegate = new ChangeEventServiceDelegate(dao);

        List<Change> served = delegate.getLatestChanges(REPO, null, new Holder<>("100"), false,
                null, false, false, BigInteger.valueOf(10), null);

        assertEquals(1, served.size(),
                "the startToken's own event was served twice: " + served);
        assertEquals("101", served.get(0).getToken());
    }
}
