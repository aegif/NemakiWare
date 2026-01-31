/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.rss;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

public class RssFeedGeneratorTest {
    
    private RssFeedGenerator generator;
    
    @Before
    public void setUp() {
        generator = new RssFeedGenerator();
        generator.setBaseUrl("http://localhost:8080/nemakiware");
    }
    
    @Test
    public void testGenerateRss_EmptyItems() {
        List<RssFeedItem> items = new ArrayList<>();
        
        String rss = generator.generateRss("Test Feed", "Test Description", "http://example.com", items);
        
        assertNotNull(rss);
        assertTrue(rss.contains("<?xml"));
        assertTrue(rss.contains("<rss"));
        assertTrue(rss.contains("version=\"2.0\""));
        assertTrue(rss.contains("<channel>"));
        assertTrue(rss.contains("<title>Test Feed</title>"));
        assertTrue(rss.contains("<description>Test Description</description>"));
        assertTrue(rss.contains("<link>http://example.com</link>"));
    }
    
    @Test
    public void testGenerateRss_WithItems() {
        List<RssFeedItem> items = new ArrayList<>();
        
        Calendar pubDate = Calendar.getInstance();
        
        RssFeedItem item = new RssFeedItem.Builder()
            .id("item-1")
            .title("Created: test-document.txt")
            .description("Document 'test-document.txt' was created.")
            .link("http://localhost:8080/nemakiware/ui/#/repository/repo1/document/doc1")
            .author("admin")
            .pubDate(pubDate)
            .eventType("CREATED")
            .objectId("doc1")
            .objectName("test-document.txt")
            .objectType("cmis:document")
            .build();
        
        items.add(item);
        
        String rss = generator.generateRss("Test Feed", "Test Description", "http://example.com", items);
        
        assertNotNull(rss);
        assertTrue(rss.contains("<item>"));
        assertTrue(rss.contains("<title>Created: test-document.txt</title>"));
        assertTrue(rss.contains("<description>Document 'test-document.txt' was created.</description>"));
        assertTrue(rss.contains("<author>admin</author>"));
        assertTrue(rss.contains("<guid"));
        assertTrue(rss.contains("item-1"));
        assertTrue(rss.contains("<category>CREATED</category>"));
    }
    
    @Test
    public void testGenerateAtom_EmptyItems() {
        List<RssFeedItem> items = new ArrayList<>();
        
        String atom = generator.generateAtom("Test Feed", "Test Subtitle", "urn:test:feed:1", items);
        
        assertNotNull(atom);
        assertTrue(atom.contains("<?xml"));
        assertTrue(atom.contains("<feed"));
        assertTrue(atom.contains("http://www.w3.org/2005/Atom"));
        assertTrue(atom.contains("<title>Test Feed</title>"));
        assertTrue(atom.contains("<subtitle>Test Subtitle</subtitle>"));
        assertTrue(atom.contains("<id>urn:test:feed:1</id>"));
    }
    
    @Test
    public void testGenerateAtom_WithItems() {
        List<RssFeedItem> items = new ArrayList<>();
        
        Calendar pubDate = Calendar.getInstance();
        
        RssFeedItem item = new RssFeedItem.Builder()
            .id("item-1")
            .title("Updated: test-document.txt")
            .description("Document 'test-document.txt' was updated.")
            .link("http://localhost:8080/nemakiware/ui/#/repository/repo1/document/doc1")
            .author("admin")
            .pubDate(pubDate)
            .eventType("UPDATED")
            .objectId("doc1")
            .objectName("test-document.txt")
            .objectType("cmis:document")
            .build();
        
        items.add(item);
        
        String atom = generator.generateAtom("Test Feed", "Test Subtitle", "urn:test:feed:1", items);
        
        assertNotNull(atom);
        assertTrue(atom.contains("<entry>"));
        assertTrue(atom.contains("<title>Updated: test-document.txt</title>"));
        assertTrue(atom.contains("<content"));
        assertTrue(atom.contains("<author>"));
        assertTrue(atom.contains("<name>admin</name>"));
        assertTrue(atom.contains("<category"));
        assertTrue(atom.contains("term=\"UPDATED\""));
    }
    
    @Test
    public void testGenerateRss_MultipleItems() {
        List<RssFeedItem> items = new ArrayList<>();
        
        Calendar pubDate = Calendar.getInstance();
        
        for (int i = 1; i <= 3; i++) {
            RssFeedItem item = new RssFeedItem.Builder()
                .id("item-" + i)
                .title("Item " + i)
                .description("Description " + i)
                .pubDate(pubDate)
                .build();
            items.add(item);
        }
        
        String rss = generator.generateRss("Test Feed", "Test Description", "http://example.com", items);
        
        assertNotNull(rss);
        assertTrue(rss.contains("<title>Item 1</title>"));
        assertTrue(rss.contains("<title>Item 2</title>"));
        assertTrue(rss.contains("<title>Item 3</title>"));
    }
    
    @Test
    public void testGenerateRss_ItemWithoutOptionalFields() {
        List<RssFeedItem> items = new ArrayList<>();
        
        RssFeedItem item = new RssFeedItem.Builder()
            .title("Minimal Item")
            .description("Minimal description")
            .build();
        
        items.add(item);
        
        String rss = generator.generateRss("Test Feed", "Test Description", "http://example.com", items);
        
        assertNotNull(rss);
        assertTrue(rss.contains("<title>Minimal Item</title>"));
        assertTrue(rss.contains("<description>Minimal description</description>"));
    }
}
