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
package jp.aegif.nemaki.rss;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Change;
import jp.aegif.nemaki.model.Folder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An RSS/Atom feed is never served SHORT over rows the store could not decode.
 *
 * <h2>Why a feed refuses instead of degrading</h2>
 *
 * <p>The CMIS change feed advances a client token, so a hole there means the client's cursor
 * moves past an undelivered event — that path throws (round 26). A feed has no cursor at all:
 * subscribers poll the top-N window, and an event missing from the window is simply never seen
 * — same permanent non-delivery, without even a token to argue about. §47 recorded this as the
 * remaining consumer of the same read; this closes it. The resource layer maps the refusal to
 * HTTP 500, which feed readers treat as "try again later" — they keep their last-known items.
 *
 * <p>The folder filter has the same property one hop away: {@code collectChildFolderIds} builds
 * the set of folders whose events the feed SHOWS, so a silently short child listing drops whole
 * subtrees from the feed with no signal.
 */
class RssFeedsAreNeverSilentlyShortTest {

    private static final String REPO = "bedroom";

    private ContentService contentService;
    private ContentDaoService contentDaoService;
    private RssFeedService service;

    @BeforeEach
    void setUp() {
        contentService = mock(ContentService.class);
        contentDaoService = mock(ContentDaoService.class);
        service = new RssFeedService();
        service.setContentService(contentService);
        service.setContentDaoService(contentDaoService);
        service.setBaseUrl("http://localhost:8080/core");
    }

    private static Change change(String token, String objectId) {
        Change c = new Change();
        c.setToken(token);
        c.setObjectId(objectId);
        c.setType("cmis:document");
        return c;
    }

    @Test
    @DisplayName("a folder feed with an undecodable change row refuses instead of serving short")
    void aFolderFeedRefusesAShortChangeWindow() {
        when(contentDaoService.getLatestChanges(anyString(), isNull(), anyInt()))
                .thenReturn(new ArrayList<>(List.of(change("100", "doc-1"))));
        when(contentDaoService.lastUnreadableChangeCount()).thenReturn(1);

        assertThrows(IllegalStateException.class,
                () -> service.generateFolderRssFeed(REPO, "folder-1", false, null, 10, null, null),
                "the feed window has a hole in it — a subscriber polling this feed misses "
                        + "the dropped event permanently, with no cursor to ever rewind");
    }

    @Test
    @DisplayName("a document feed refuses the same hole — the twin")
    void aDocumentFeedRefusesAShortChangeWindow() {
        when(contentDaoService.getLatestChanges(anyString(), isNull(), anyInt()))
                .thenReturn(new ArrayList<>(List.of(change("100", "doc-1"))));
        when(contentDaoService.lastUnreadableChangeCount()).thenReturn(1);

        assertThrows(IllegalStateException.class,
                () -> service.generateDocumentRssFeed(REPO, "doc-1", 10, null, null),
                "the folder feed refuses this arm but the document twin served short");
    }

    @Test
    @DisplayName("a short child listing refuses — the feed's folder filter must not shrink")
    void aShortChildListingRefusesTheFolderFilter() {
        Folder folder = new Folder();
        folder.setId("folder-1");
        folder.setType("cmis:folder");
        when(contentService.getContent(REPO, "folder-1")).thenReturn(folder);
        when(contentService.getChildren(REPO, "folder-1")).thenReturn(List.of());
        when(contentService.lastUnreadableChildCount()).thenReturn(1);

        assertThrows(IllegalStateException.class,
                () -> service.generateFolderRssFeed(REPO, "folder-1", true, 2, 10, null, null),
                "a subtree left the feed's folder filter silently — every event under it "
                        + "vanishes from the feed with no signal");
    }

    @Test
    @DisplayName("a DEEP short child listing refuses — the check lives at every recursion level")
    void aDeepShortChildListingRefuses() {
        // The round-32 discrimination review's point: the depth-0 fixture could not tell a
        // per-level check from one hoisted to the top. Here the ROOT listing is clean and
        // only the SUBFOLDER's is short; a depth-0-only check would pass this feed.
        Folder root = new Folder();
        root.setId("folder-1");
        root.setType("cmis:folder");
        Folder sub = new Folder();
        sub.setId("sub-1");
        sub.setType("cmis:folder");
        when(contentService.getContent(REPO, "folder-1")).thenReturn(root);
        when(contentService.getContent(REPO, "sub-1")).thenReturn(sub);
        when(contentService.getChildren(REPO, "folder-1"))
                .thenReturn(List.of((jp.aegif.nemaki.model.Content) sub));
        when(contentService.getChildren(REPO, "sub-1")).thenReturn(List.of());
        when(contentService.lastUnreadableChildCount()).thenReturn(0, 1);

        assertThrows(IllegalStateException.class,
                () -> service.generateFolderRssFeed(REPO, "folder-1", true, 3, 10, null, null),
                "a deep subtree's short listing was not checked at its own level");
    }

    @Test
    @DisplayName("a NULL child listing refuses — 'could not enumerate' is not 'no subtree'")
    void aNullChildListingRefuses() {
        Folder folder = new Folder();
        folder.setId("folder-1");
        folder.setType("cmis:folder");
        when(contentService.getContent(REPO, "folder-1")).thenReturn(folder);
        when(contentService.getChildren(REPO, "folder-1")).thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> service.generateFolderRssFeed(REPO, "folder-1", true, 2, 10, null, null),
                "a null listing silently dropped the subtree from the feed's filter");
    }

    @Test
    @DisplayName("a non-positive limit falls back to the default instead of 'no limit'")
    void aNonPositiveLimitFallsBackToTheDefault() {
        // ?limit=-1 used to flow as effectiveLimit=-1 → getLatestChanges(-2) → a
        // non-positive limit one layer down, which was an unbounded query. The symptom was
        // an EMPTY feed (the filter loop breaks immediately at size >= -1), hiding the
        // unbounded fetch behind it.
        when(contentDaoService.getLatestChanges(anyString(), isNull(), anyInt()))
                .thenReturn(new ArrayList<>());
        when(contentDaoService.lastUnreadableChangeCount()).thenReturn(0);

        service.generateDocumentRssFeed(REPO, "doc-1", -1, null, null);

        // defaultLimit (50) * 2 — not -2.
        org.mockito.Mockito.verify(contentDaoService)
                .getLatestChanges(REPO, null, 100);
    }

    @Test
    @DisplayName("a negative maxDepth falls back to the default instead of killing recursion")
    void aNegativeMaxDepthFallsBackToTheDefault() {
        // maxDepth=-1 used to pass straight through: currentDepth >= -1 is true at once, so
        // the subtree silently left the feed's filter — the same shrunken feed as a short
        // listing, reachable by a query parameter. With the fallback the default depth (5)
        // applies and the subfolder IS walked.
        Folder root = new Folder();
        root.setId("folder-1");
        root.setType("cmis:folder");
        Folder sub = new Folder();
        sub.setId("sub-1");
        sub.setType("cmis:folder");
        when(contentService.getContent(REPO, "folder-1")).thenReturn(root);
        when(contentService.getContent(REPO, "sub-1")).thenReturn(sub);
        when(contentService.getChildren(REPO, "folder-1"))
                .thenReturn(List.of((jp.aegif.nemaki.model.Content) sub));
        when(contentService.getChildren(REPO, "sub-1")).thenReturn(List.of());
        when(contentService.lastUnreadableChildCount()).thenReturn(0);
        when(contentDaoService.getLatestChanges(anyString(), isNull(), anyInt()))
                .thenReturn(new ArrayList<>());
        when(contentDaoService.lastUnreadableChangeCount()).thenReturn(0);

        service.generateFolderRssFeed(REPO, "folder-1", true, -1, 10, null, null);

        org.mockito.Mockito.verify(contentService).getChildren(REPO, "sub-1");
    }

    @Test
    @DisplayName("an ordinary feed still generates — the control")
    void anOrdinaryFeedStillGenerates() {
        Folder folder = new Folder();
        folder.setId("folder-1");
        folder.setType("cmis:folder");
        when(contentService.getContent(REPO, "folder-1")).thenReturn(folder);
        when(contentService.getChildren(REPO, "folder-1")).thenReturn(List.of());
        when(contentService.lastUnreadableChildCount()).thenReturn(0);
        when(contentDaoService.getLatestChanges(anyString(), isNull(), anyInt()))
                .thenReturn(new ArrayList<>(List.of(change("100", "doc-1"))));
        when(contentDaoService.lastUnreadableChangeCount()).thenReturn(0);

        String feed = service.generateFolderRssFeed(REPO, "folder-1", true, 2, 10, null, null);

        assertNotNull(feed, "the refusal arms broke ordinary feed generation");
    }
}
