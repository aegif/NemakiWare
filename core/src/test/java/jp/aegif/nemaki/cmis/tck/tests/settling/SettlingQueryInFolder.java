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
 * {@code QueryInFolderTest}, waiting for its fixture to be searchable.
 *
 * <p>It did not fail in the runs that exposed this, but it has the same shape as the two that did
 * — create a small fixture, then query it — so it races on the same window and is wrapped for the
 * same reason. See {@link SearchIndexSettle}.
 */
public class SettlingQueryInFolder extends org.apache.chemistry.opencmis.tck.tests.query.QueryInFolderTest {

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
