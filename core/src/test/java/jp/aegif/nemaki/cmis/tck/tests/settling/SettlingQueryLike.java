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
package jp.aegif.nemaki.cmis.tck.tests.settling;

import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.Session;

import jp.aegif.nemaki.cmis.tck.SearchIndexSettle;

/**
 * {@code QueryLikeTest}, waiting for its 52 objects to be searchable before it queries them.
 *
 * <p>The upstream test creates a document and a folder for each letter a–z and then queries for
 * each letter, so every creation happens before every query: one wait, taken at the first query,
 * covers the whole fixture. See {@link SearchIndexSettle} for why the wait is needed at all.
 *
 * <p>The class name deliberately does not end in {@code Test}. Surefire scans our own test classes
 * by that pattern, and {@code AbstractCmisTest} carries an {@code @Test} method, so a name ending
 * in {@code Test} makes Surefire run this class standalone — with no TCK parameters, which fails
 * immediately with "SPI class entry is missing". The upstream classes escape that only because
 * they live in a jar, which Surefire does not scan.
 */
public class SettlingQueryLike extends org.apache.chemistry.opencmis.tck.tests.query.QueryLikeTest {

    private final SearchIndexSettle settle = new SearchIndexSettle();

    @Override
    public void run(Session session) {
        super.run(settle.wrap(session));
    }

    @Override
    protected Folder createTestFolder(Session session) {
        Folder folder = super.createTestFolder(session);
        settle.created("cmis:folder", folder.getId());
        return folder;
    }

    @Override
    protected Document createDocument(Session session, Folder parent, String name, String content) {
        Document document = super.createDocument(session, parent, name, content);
        settle.created("cmis:document", document.getId());
        return document;
    }

    @Override
    protected Folder createFolder(Session session, Folder parent, String name) {
        Folder folder = super.createFolder(session, parent, name);
        settle.created("cmis:folder", folder.getId());
        return folder;
    }
}
