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
import jp.aegif.nemaki.rest.BuildInfoResource;
import jp.aegif.nemaki.rest.RssFeedResource;

import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An Atom feed names its generator with the version of the build that served it.
 *
 * <p>{@code <generator version="...">} was the literal 3.0.0 from 3.1.0, when the feeds arrived,
 * through 3.4.0. Nothing compared it with the build.
 *
 * <p>Driven through {@code RssFeedResource} with {@code format=atom}, for the folder feed and the
 * document feed. The version it is compared with is checked to be a filtered one first, so a
 * fallback on both sides cannot agree.
 */
class TheAtomFeedNamesTheBuildVersionTest {

    private static final String REPO = "bedroom";
    private static final String TOKEN = "the-token";

    private RssFeedResource resource;

    @BeforeEach
    void setUp() {
        RssFeedService service = new RssFeedService();
        service.setContentService(mock(ContentService.class));
        service.setContentDaoService(mock(ContentDaoService.class));
        service.setBaseUrl("http://localhost:8080/core");

        RssToken token = new RssToken();
        token.setRepositoryId(REPO);
        token.setFolderIds(List.of("folder-1"));
        token.setDocumentIds(List.of("doc-1"));
        RssTokenService tokens = mock(RssTokenService.class);
        when(tokens.validateToken(REPO, TOKEN)).thenReturn(token);

        resource = new RssFeedResource();
        resource.setRssFeedService(service);
        resource.setRssTokenService(tokens);
    }

    private static String built() {
        String built = BuildInfoResource.getVersion();
        assertTrue(built.matches("\\d+\\.\\d+\\.\\d+.*"),
                "version.properties was not filtered on this classpath (got '" + built + "'), so "
                        + "this test cannot tell the build's version from a fallback");
        return built;
    }

    private static void namesTheBuild(Response response, String feed) throws Exception {
        String built = built();
        assertEquals(200, response.getStatus(), feed + " was not served: " + response.getEntity());
        String xml = (String) response.getEntity();

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setNamespaceAware(true);
        NodeList generators = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                .getElementsByTagNameNS("http://www.w3.org/2005/Atom", "generator");
        assertEquals(1, generators.getLength(), feed + " does not name one generator:\n" + xml);
        Element generator = (Element) generators.item(0);
        assertEquals("NemakiWare", generator.getTextContent(), feed + "'s generator:\n" + xml);
        assertEquals(built, generator.getAttribute("version"),
                feed + " names its generator's version as '" + generator.getAttribute("version")
                        + "', but it was served by " + built);
    }

    @Test
    @DisplayName("the folder Atom feed names the build's version")
    void theFolderFeedNamesTheBuildVersion() throws Exception {
        namesTheBuild(resource.getFolderFeed(REPO, "folder-1", TOKEN, false, null, 10, null,
                "atom", null), "the folder Atom feed");
    }

    @Test
    @DisplayName("the document Atom feed names the build's version")
    void theDocumentFeedNamesTheBuildVersion() throws Exception {
        namesTheBuild(resource.getDocumentFeed(REPO, "doc-1", TOKEN, 10, null, "atom", null),
                "the document Atom feed");
    }
}
