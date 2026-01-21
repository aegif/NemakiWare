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

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.impl.XMLConstants;
import org.apache.chemistry.opencmis.commons.impl.XMLConverter;
import org.apache.chemistry.opencmis.commons.impl.XMLUtils;

/**
 * Service document class.
 */
public class ServiceDocument extends AtomDocumentBase {

    public ServiceDocument() {
    }

    public void startServiceDocument() throws XMLStreamException {
        XMLStreamWriter xsw = getWriter();
        
        xsw.writeStartElement(XMLConstants.PREFIX_APP, "service", XMLConstants.NAMESPACE_APP);

        xsw.writeNamespace(XMLConstants.PREFIX_ATOM, XMLConstants.NAMESPACE_ATOM);
        xsw.writeNamespace(XMLConstants.PREFIX_CMIS, XMLConstants.NAMESPACE_CMIS);
        xsw.writeNamespace(XMLConstants.PREFIX_RESTATOM, XMLConstants.NAMESPACE_RESTATOM);
        xsw.writeNamespace(XMLConstants.PREFIX_APP, XMLConstants.NAMESPACE_APP);

        writeAllCustomNamespace();
    }

    public void endServiceDocument() throws XMLStreamException {
        getWriter().writeEndElement();
    }

    public void startWorkspace(String title) throws XMLStreamException {
        XMLStreamWriter xsw = getWriter();

        xsw.writeStartElement(XMLConstants.PREFIX_APP, "workspace", XMLConstants.NAMESPACE_APP);
        XMLUtils.write(xsw, XMLConstants.PREFIX_ATOM, XMLConstants.NAMESPACE_ATOM, "title", title);
    }

    public void endWorkspace() throws XMLStreamException {
        getWriter().writeEndElement();
    }

    public void writeRepositoryInfo(RepositoryInfo repInfo, CmisVersion cmisVersion) throws XMLStreamException {
        XMLConverter.writeRepositoryInfo(getWriter(), cmisVersion, XMLConstants.NAMESPACE_RESTATOM, repInfo);
    }

    public void writeUriTemplate(String template, String type, String mediatype) throws XMLStreamException {
        XMLStreamWriter xsw = getWriter();

        xsw.writeStartElement(XMLConstants.PREFIX_RESTATOM, "uritemplate", XMLConstants.NAMESPACE_RESTATOM);
        XMLUtils.write(xsw, XMLConstants.PREFIX_RESTATOM, XMLConstants.NAMESPACE_RESTATOM, "template", template);
        XMLUtils.write(xsw, XMLConstants.PREFIX_RESTATOM, XMLConstants.NAMESPACE_RESTATOM, "type", type);
        XMLUtils.write(xsw, XMLConstants.PREFIX_RESTATOM, XMLConstants.NAMESPACE_RESTATOM, "mediatype", mediatype);
        xsw.writeEndElement();
    }
}
