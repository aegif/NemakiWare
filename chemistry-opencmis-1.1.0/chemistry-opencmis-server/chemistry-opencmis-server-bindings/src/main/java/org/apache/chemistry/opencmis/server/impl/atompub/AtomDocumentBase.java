/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.chemistry.opencmis.server.impl.atompub;

import java.math.BigInteger;
import java.util.GregorianCalendar;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.apache.chemistry.opencmis.commons.impl.Base64;
import org.apache.chemistry.opencmis.commons.impl.Constants;
import org.apache.chemistry.opencmis.commons.impl.DateTimeHelper;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;
import org.apache.chemistry.opencmis.commons.impl.XMLConstants;
import org.apache.chemistry.opencmis.commons.impl.XMLUtils;

/**
 * Atom base class.
 */
public abstract class AtomDocumentBase extends XMLDocumentBase {

    private static final String ID_PREFIX = "http://chemistry.apache.org/";
    private static final String ID_DUMMY = "http://chemistry.apache.org/no-id";

    /**
     * Generates a valid Atom id.
     */
    public String generateAtomId(String input) {
        if (input == null) {
            return ID_DUMMY;
        }

        return ID_PREFIX + Base64.encodeBytes(IOUtils.toUTF8Bytes(input));
    }

    /**
     * Writes an Atom id tag.
     */
    public void writeId(String id) throws XMLStreamException {
        XMLUtils.write(getWriter(), XMLConstants.PREFIX_ATOM, XMLConstants.NAMESPACE_ATOM, "id", id);
    }

    /**
     * Writes an Atom title tag.
     */
    public void writeTitle(String title) throws XMLStreamException {
        XMLUtils.write(getWriter(), XMLConstants.PREFIX_ATOM, XMLConstants.NAMESPACE_ATOM, "title", title);
    }

    /**
     * Writes an Atom author tag.
     */
    public void writeAuthor(String author) throws XMLStreamException {
        XMLStreamWriter xsw = getWriter();

        xsw.writeStartElement(XMLConstants.PREFIX_ATOM, "author", XMLConstants.NAMESPACE_ATOM);
        XMLUtils.write(xsw, XMLConstants.PREFIX_ATOM, XMLConstants.NAMESPACE_ATOM, "name", author);
        xsw.writeEndElement();
    }

    /**
     * Writes an Atom updated tag.
     */
    public void writeUpdated(GregorianCalendar updated) throws XMLStreamException {
        XMLUtils.write(getWriter(), XMLConstants.PREFIX_APP, XMLConstants.NAMESPACE_APP, "edited", updated);
        XMLUtils.write(getWriter(), XMLConstants.PREFIX_ATOM, XMLConstants.NAMESPACE_ATOM, "updated", updated);
    }

    /**
     * Writes an Atom updated tag.
     */
    public void writeUpdated(long updated) throws XMLStreamException {
        String updatedStr = DateTimeHelper.formatXmlDateTime(updated);
        XMLUtils.write(getWriter(), XMLConstants.PREFIX_APP, XMLConstants.NAMESPACE_APP, "edited", updatedStr);
        XMLUtils.write(getWriter(), XMLConstants.PREFIX_ATOM, XMLConstants.NAMESPACE_ATOM, "updated", updatedStr);
    }

    /**
     * Writes an Atom published tag.
     */
    public void writePublished(GregorianCalendar published) throws XMLStreamException {
        XMLUtils.write(getWriter(), XMLConstants.PREFIX_ATOM, XMLConstants.NAMESPACE_ATOM, "published", published);
    }

    /**
     * Writes an Atom published tag.
     */
    public void writePublished(long published) throws XMLStreamException {
        XMLUtils.write(getWriter(), XMLConstants.PREFIX_ATOM, XMLConstants.NAMESPACE_ATOM, "published",
                DateTimeHelper.formatXmlDateTime(published));
    }

    /**
     * Writes a CMIS pathSegment tag.
     */
    public void writePathSegment(String pathSegment) throws XMLStreamException {
        XMLUtils.write(getWriter(), XMLConstants.PREFIX_RESTATOM, XMLConstants.NAMESPACE_RESTATOM, "pathSegment",
                pathSegment);
    }

    /**
     * Writes a CMIS relativePathSegment tag.
     */
    public void writeRelativePathSegment(String relativePathSegment) throws XMLStreamException {
        XMLUtils.write(getWriter(), XMLConstants.PREFIX_RESTATOM, XMLConstants.NAMESPACE_RESTATOM,
                "relativePathSegment", relativePathSegment);
    }

    /**
     * Writes an Atom collection.
     */
    public void writeCollection(String href, String collectionType, String text, String... accept)
            throws XMLStreamException {
        XMLStreamWriter xsw = getWriter();

        xsw.writeStartElement(XMLConstants.PREFIX_APP, "collection", XMLConstants.NAMESPACE_APP);
        xsw.writeAttribute("href", href);

        if (collectionType != null) {
            XMLUtils.write(xsw, XMLConstants.PREFIX_RESTATOM, XMLConstants.NAMESPACE_RESTATOM, "collectionType",
                    collectionType);
        }

        xsw.writeStartElement(XMLConstants.PREFIX_ATOM, "title", XMLConstants.NAMESPACE_ATOM);
        xsw.writeAttribute("type", "text");
        xsw.writeCharacters(text);
        xsw.writeEndElement();

        for (String ct : accept) {
            XMLUtils.write(xsw, XMLConstants.PREFIX_APP, XMLConstants.NAMESPACE_APP, "accept", ct);
        }

        xsw.writeEndElement();
    }

    /**
     * Writes a link.
     */
    public void writeLink(String rel, String href, String type, String id) throws XMLStreamException {
        XMLStreamWriter xsw = getWriter();

        xsw.writeStartElement(XMLConstants.PREFIX_ATOM, "link", XMLConstants.NAMESPACE_ATOM);

        xsw.writeAttribute("rel", rel);
        xsw.writeAttribute("href", href);
        if (type != null) {
            xsw.writeAttribute("type", type);
        }
        if (id != null) {
            xsw.writeAttribute(XMLConstants.NAMESPACE_RESTATOM, "id", id);
        }

        xsw.writeEndElement();
    }

    public void writeServiceLink(String href, String repositoryId) throws XMLStreamException {
        writeLink(Constants.REL_SERVICE, href + "?repositoryId=" + IOUtils.encodeURL(repositoryId),
                Constants.MEDIATYPE_SERVICE, null);
    }

    public void writeSelfLink(String href, String type, String id) throws XMLStreamException {
        writeLink(Constants.REL_SELF, href, type, id);
    }

    public void writeEnclosureLink(String href) throws XMLStreamException {
        writeLink(Constants.REL_ENCLOSURE, href, Constants.MEDIATYPE_ENTRY, null);
    }

    public void writeEditLink(String href) throws XMLStreamException {
        writeLink(Constants.REL_EDIT, href, Constants.MEDIATYPE_ENTRY, null);
    }

    public void writeAlternateLink(String href, String type, String kind, String title, BigInteger length)
            throws XMLStreamException {
        XMLStreamWriter xsw = getWriter();

        xsw.writeStartElement(XMLConstants.PREFIX_ATOM, "link", XMLConstants.NAMESPACE_ATOM);

        xsw.writeAttribute("rel", Constants.REL_ALTERNATE);
        xsw.writeAttribute("href", href);
        if (type != null) {
            xsw.writeAttribute("type", type);
        }
        if (kind != null) {
            xsw.writeAttribute(XMLConstants.NAMESPACE_RESTATOM, "renditionKind", kind);
        }
        if (title != null) {
            xsw.writeAttribute("title", title);
        }
        if (length != null) {
            xsw.writeAttribute("length", length.toString());
        }

        xsw.writeEndElement();
    }

    public void writeWorkingCopyLink(String href) throws XMLStreamException {
        writeLink(Constants.REL_WORKINGCOPY, href, Constants.MEDIATYPE_ENTRY, null);
    }

    public void writeUpLink(String href, String type) throws XMLStreamException {
        writeLink(Constants.REL_UP, href, type, null);
    }

    public void writeDownLink(String href, String type) throws XMLStreamException {
        writeLink(Constants.REL_DOWN, href, type, null);
    }

    public void writeVersionHistoryLink(String href) throws XMLStreamException {
        writeLink(Constants.REL_VERSIONHISTORY, href, Constants.MEDIATYPE_FEED, null);
    }

    public void writeCurrentVerionsLink(String href) throws XMLStreamException {
        writeLink(Constants.REL_CURRENTVERSION, href, Constants.MEDIATYPE_ENTRY, null);
    }

    public void writeEditMediaLink(String href, String type) throws XMLStreamException {
        writeLink(Constants.REL_EDITMEDIA, href, type, null);
    }

    public void writeDescribedByLink(String href) throws XMLStreamException {
        writeLink(Constants.REL_DESCRIBEDBY, href, Constants.MEDIATYPE_ENTRY, null);
    }

    public void writeAllowableActionsLink(String href) throws XMLStreamException {
        writeLink(Constants.REL_ALLOWABLEACTIONS, href, Constants.MEDIATYPE_ALLOWABLEACTION, null);
    }

    public void writeAclLink(String href) throws XMLStreamException {
        writeLink(Constants.REL_ACL, href, Constants.MEDIATYPE_ACL, null);
    }

    public void writePoliciesLink(String href) throws XMLStreamException {
        writeLink(Constants.REL_POLICIES, href, Constants.MEDIATYPE_FEED, null);
    }

    public void writeRelationshipsLink(String href) throws XMLStreamException {
        writeLink(Constants.REL_RELATIONSHIPS, href, Constants.MEDIATYPE_FEED, null);
    }

    public void writeRelationshipSourceLink(String href) throws XMLStreamException {
        writeLink(Constants.REL_SOURCE, href, Constants.MEDIATYPE_ENTRY, null);
    }

    public void writeRelationshipTargetLink(String href) throws XMLStreamException {
        writeLink(Constants.REL_TARGET, href, Constants.MEDIATYPE_ENTRY, null);
    }

    public void writeFolderTreeLink(String href) throws XMLStreamException {
        writeLink(Constants.REL_FOLDERTREE, href, Constants.MEDIATYPE_FEED, null);
    }

    public void writeTypeUpLink(String href, String type) throws XMLStreamException {
        writeLink(Constants.REL_UP, href, type, null);
    }

    public void writeTypeDownLink(String href, String type) throws XMLStreamException {
        writeLink(Constants.REL_DOWN, href, type, null);
    }

    public void writeViaLink(String href) throws XMLStreamException {
        writeLink(Constants.REL_VIA, href, Constants.MEDIATYPE_ENTRY, null);
    }

    public void writeFirstLink(String href) throws XMLStreamException {
        writeLink(Constants.REL_FIRST, href, Constants.MEDIATYPE_FEED, null);
    }

    public void writeLastLink(String href) throws XMLStreamException {
        writeLink(Constants.REL_LAST, href, Constants.MEDIATYPE_FEED, null);
    }

    public void writePreviousLink(String href) throws XMLStreamException {
        writeLink(Constants.REL_PREV, href, Constants.MEDIATYPE_FEED, null);
    }

    public void writeNextLink(String href) throws XMLStreamException {
        writeLink(Constants.REL_NEXT, href, Constants.MEDIATYPE_FEED, null);
    }
}
