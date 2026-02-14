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

import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Generator for RSS 2.0 and Atom 1.0 feeds.
 * 
 * This class converts a list of RssFeedItem objects into XML feed format.
 */
public class RssFeedGenerator {
    
    private static final Log log = LogFactory.getLog(RssFeedGenerator.class);
    
    private static final String RSS_VERSION = "2.0";
    private static final String ATOM_NAMESPACE = "http://www.w3.org/2005/Atom";
    
    private static final SimpleDateFormat RFC822_FORMAT = new SimpleDateFormat(
        "EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);
    private static final SimpleDateFormat ISO8601_FORMAT = new SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss'Z'");
    
    static {
        ISO8601_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }
    
    private String baseUrl;
    
    public RssFeedGenerator() {
    }
    
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }
    
    /**
     * Generate an RSS 2.0 feed from a list of feed items.
     * 
     * @param title The feed title
     * @param description The feed description
     * @param link The feed link
     * @param items The list of feed items
     * @return RSS 2.0 XML string
     */
    public String generateRss(String title, String description, String link, List<RssFeedItem> items) {
        try {
            StringWriter writer = new StringWriter();
            XMLOutputFactory factory = XMLOutputFactory.newInstance();
            XMLStreamWriter xml = factory.createXMLStreamWriter(writer);
            
            xml.writeStartDocument("UTF-8", "1.0");
            xml.writeStartElement("rss");
            xml.writeAttribute("version", RSS_VERSION);
            
            xml.writeStartElement("channel");
            
            writeElement(xml, "title", title);
            writeElement(xml, "description", description);
            writeElement(xml, "link", link);
            writeElement(xml, "language", "ja");
            writeElement(xml, "generator", "NemakiWare RSS Feed Generator");
            writeElement(xml, "lastBuildDate", formatRfc822(Calendar.getInstance()));
            
            for (RssFeedItem item : items) {
                writeRssItem(xml, item);
            }
            
            xml.writeEndElement();
            xml.writeEndElement();
            xml.writeEndDocument();
            
            xml.flush();
            xml.close();
            
            return writer.toString();
            
        } catch (XMLStreamException e) {
            log.error("Failed to generate RSS feed", e);
            return null;
        }
    }
    
    /**
     * Generate an Atom 1.0 feed from a list of feed items.
     * 
     * @param title The feed title
     * @param subtitle The feed subtitle
     * @param feedId The feed ID (URI)
     * @param items The list of feed items
     * @return Atom 1.0 XML string
     */
    public String generateAtom(String title, String subtitle, String feedId, List<RssFeedItem> items) {
        try {
            StringWriter writer = new StringWriter();
            XMLOutputFactory factory = XMLOutputFactory.newInstance();
            XMLStreamWriter xml = factory.createXMLStreamWriter(writer);
            
            xml.writeStartDocument("UTF-8", "1.0");
            xml.writeStartElement("feed");
            xml.writeDefaultNamespace(ATOM_NAMESPACE);
            
            writeElement(xml, "title", title);
            writeElement(xml, "subtitle", subtitle);
            writeElement(xml, "id", feedId);
            writeElement(xml, "updated", formatIso8601(Calendar.getInstance()));
            
            xml.writeEmptyElement("link");
            xml.writeAttribute("href", feedId);
            xml.writeAttribute("rel", "self");
            xml.writeAttribute("type", "application/atom+xml");
            
            xml.writeStartElement("generator");
            xml.writeAttribute("uri", "https://github.com/aegif/NemakiWare");
            xml.writeAttribute("version", "3.0.0");
            xml.writeCharacters("NemakiWare");
            xml.writeEndElement();
            
            for (RssFeedItem item : items) {
                writeAtomEntry(xml, item);
            }
            
            xml.writeEndElement();
            xml.writeEndDocument();
            
            xml.flush();
            xml.close();
            
            return writer.toString();
            
        } catch (XMLStreamException e) {
            log.error("Failed to generate Atom feed", e);
            return null;
        }
    }
    
    private void writeRssItem(XMLStreamWriter xml, RssFeedItem item) throws XMLStreamException {
        xml.writeStartElement("item");
        
        writeElement(xml, "title", item.getTitle());
        writeElement(xml, "description", item.getDescription());
        
        if (item.getLink() != null) {
            writeElement(xml, "link", item.getLink());
        }
        
        if (item.getId() != null) {
            xml.writeStartElement("guid");
            xml.writeAttribute("isPermaLink", "false");
            xml.writeCharacters(item.getId());
            xml.writeEndElement();
        }
        
        if (item.getAuthor() != null) {
            writeElement(xml, "author", item.getAuthor());
        }
        
        if (item.getPubDate() != null) {
            writeElement(xml, "pubDate", formatRfc822(item.getPubDate()));
        }
        
        if (item.getEventType() != null) {
            writeElement(xml, "category", item.getEventType());
        }
        
        xml.writeEndElement();
    }
    
    private void writeAtomEntry(XMLStreamWriter xml, RssFeedItem item) throws XMLStreamException {
        xml.writeStartElement("entry");
        
        writeElement(xml, "title", item.getTitle());
        writeElement(xml, "id", item.getId() != null ? item.getId() : "urn:uuid:" + System.currentTimeMillis());
        
        if (item.getPubDate() != null) {
            writeElement(xml, "updated", formatIso8601(item.getPubDate()));
            writeElement(xml, "published", formatIso8601(item.getPubDate()));
        } else {
            String now = formatIso8601(Calendar.getInstance());
            writeElement(xml, "updated", now);
        }
        
        if (item.getAuthor() != null) {
            xml.writeStartElement("author");
            writeElement(xml, "name", item.getAuthor());
            xml.writeEndElement();
        }
        
        if (item.getLink() != null) {
            xml.writeEmptyElement("link");
            xml.writeAttribute("href", item.getLink());
            xml.writeAttribute("rel", "alternate");
            xml.writeAttribute("type", "text/html");
        }
        
        if (item.getDescription() != null) {
            xml.writeStartElement("content");
            xml.writeAttribute("type", "html");
            xml.writeCharacters(item.getDescription());
            xml.writeEndElement();
        }
        
        if (item.getEventType() != null) {
            xml.writeStartElement("category");
            xml.writeAttribute("term", item.getEventType());
            xml.writeEndElement();
        }
        
        xml.writeEndElement();
    }
    
    private void writeElement(XMLStreamWriter xml, String name, String value) throws XMLStreamException {
        if (value != null) {
            xml.writeStartElement(name);
            xml.writeCharacters(value);
            xml.writeEndElement();
        }
    }
    
    private String formatRfc822(Calendar calendar) {
        synchronized (RFC822_FORMAT) {
            return RFC822_FORMAT.format(calendar.getTime());
        }
    }
    
    private String formatIso8601(Calendar calendar) {
        synchronized (ISO8601_FORMAT) {
            return ISO8601_FORMAT.format(calendar.getTime());
        }
    }
}
