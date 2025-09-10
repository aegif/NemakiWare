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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.Constants;
import org.apache.chemistry.opencmis.commons.impl.UrlBuilder;
import org.apache.chemistry.opencmis.commons.impl.XMLConstants;
import org.apache.chemistry.opencmis.commons.impl.XMLConverter;
import org.apache.chemistry.opencmis.commons.impl.XMLUtils;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.QueryTypeImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.chemistry.opencmis.server.shared.Dispatcher;

/**
 * Discovery Service operations.
 */
public class DiscoveryService {

    /**
     * Query.
     */
    public static class Query extends AbstractAtomPubServiceCall {
        @Override
        public void serve(CallContext context, CmisService service, String repositoryId, HttpServletRequest request,
                HttpServletResponse response) throws Exception {
            assert context != null;
            assert service != null;
            assert repositoryId != null;
            assert request != null;
            assert response != null;

            // get parameters
            String statement = null;
            Boolean searchAllVersions = null;
            Boolean includeAllowableActions = null;
            IncludeRelationships includeRelationships = null;
            String renditionFilter = null;
            BigInteger maxItems = null;
            BigInteger skipCount = null;

            int statusCode = 0;

            if (Dispatcher.METHOD_POST.equals(request.getMethod())) {
                // POST -> read from stream

                QueryTypeImpl queryType = null;
                XMLStreamReader parser = null;
                try {
                    parser = XMLUtils.createParser(request.getInputStream());
                    XMLUtils.findNextStartElemenet(parser);
                    queryType = XMLConverter.convertQuery(parser);
                } catch (XMLStreamException e) {
                    throw new CmisInvalidArgumentException("Invalid query request!", e);
                } finally {
                    if (parser != null) {
                        try {
                            parser.close();
                        } catch (XMLStreamException e2) {
                            // ignore
                        }
                    }
                }

                statement = queryType.getStatement();
                searchAllVersions = queryType.getSearchAllVersions();
                includeAllowableActions = queryType.getIncludeAllowableActions();
                includeRelationships = queryType.getIncludeRelationships();
                renditionFilter = queryType.getRenditionFilter();
                maxItems = queryType.getMaxItems();
                skipCount = queryType.getSkipCount();

                statusCode = HttpServletResponse.SC_CREATED;
            } else if (Dispatcher.METHOD_GET.equals(request.getMethod())) {
                // GET -> parameters
                statement = getStringParameter(request, Constants.PARAM_Q);
                searchAllVersions = getBooleanParameter(request, Constants.PARAM_SEARCH_ALL_VERSIONS);
                includeAllowableActions = getBooleanParameter(request, Constants.PARAM_ALLOWABLE_ACTIONS);
                includeRelationships = getEnumParameter(request, Constants.PARAM_RELATIONSHIPS,
                        IncludeRelationships.class);
                // since CMIS 1.1
                renditionFilter = getStringParameter(request, Constants.PARAM_RENDITION_FILTER);
                maxItems = getBigIntegerParameter(request, Constants.PARAM_MAX_ITEMS);
                skipCount = getBigIntegerParameter(request, Constants.PARAM_SKIP_COUNT);

                statusCode = HttpServletResponse.SC_OK;
            } else {
                throw new CmisRuntimeException("Invalid HTTP method!");
            }

            // execute
            if (stopBeforeService(service)) {
                return;
            }

            ObjectList results = service.query(repositoryId, statement, searchAllVersions, includeAllowableActions,
                    includeRelationships, renditionFilter, maxItems, skipCount, null);

            if (stopAfterService(service)) {
                return;
            }

            if (results == null) {
                throw new CmisRuntimeException("Results are null!");
            }

            // set headers
            UrlBuilder baseUrl = compileBaseUrl(request, repositoryId);

            UrlBuilder pagingUrl = compileUrlBuilder(baseUrl, RESOURCE_QUERY, null);
            pagingUrl.addParameter(Constants.PARAM_Q, statement);
            pagingUrl.addParameter(Constants.PARAM_SEARCH_ALL_VERSIONS, searchAllVersions);
            pagingUrl.addParameter(Constants.PARAM_ALLOWABLE_ACTIONS, includeAllowableActions);
            pagingUrl.addParameter(Constants.PARAM_RELATIONSHIPS, includeRelationships);

            UrlBuilder location = new UrlBuilder(pagingUrl);
            location.addParameter(Constants.PARAM_MAX_ITEMS, maxItems);
            location.addParameter(Constants.PARAM_SKIP_COUNT, skipCount);

            response.setStatus(statusCode);
            response.setContentType(Constants.MEDIATYPE_FEED);

            // The Content-Location header is optional (CMIS specification
            // 3.7.2.1).
            // Since it can cause problems with long query statements it is
            // deactivated.
            // response.setHeader("Content-Location", location.toString());

            // The Location header is not optional (CMIS specification 3.7.2.1).
            response.setHeader("Location", location.toString());

            // write XML
            AtomFeed feed = new AtomFeed();
            feed.startDocument(response.getOutputStream(), getNamespaces(service));
            feed.startFeed(true);

            // write basic Atom feed elements
            GregorianCalendar now = new GregorianCalendar();
            feed.writeFeedElements("query", null, "", "Query", now, null, results.getNumItems());

            // write links
            feed.writeServiceLink(baseUrl.toString(), repositoryId);

            feed.writePagingLinks(pagingUrl, maxItems, skipCount, results.getNumItems(), results.hasMoreItems(),
                    PAGE_SIZE);

            CmisVersion cmisVersion = context.getCmisVersion();
            if (results.getObjects() != null) {
                AtomEntry entry = new AtomEntry(feed.getWriter());
                int idCounter = 0;
                for (ObjectData result : results.getObjects()) {
                    if (result == null) {
                        continue;
                    }
                    idCounter++;
                    writeQueryResultEntry(entry, result, "id-" + idCounter, now, cmisVersion);
                }
            }

            // write extensions
            feed.writeExtensions(results);

            // we are done
            feed.endFeed();
            feed.endDocument();
        }

        private void writeQueryResultEntry(AtomEntry entry, ObjectData result, String id, GregorianCalendar now,
                CmisVersion cmisVersion) throws Exception {
            if (result == null) {
                return;
            }

            // start
            entry.startEntry(false);

            // write Atom base tags
            entry.writeAuthor("");
            entry.writeId(entry.generateAtomId(id));
            entry.writePublished(now);
            entry.writeTitle("Query Result " + id);
            entry.writeUpdated(now);

            // write query result object
            XMLConverter.writeObject(entry.getWriter(), cmisVersion, false, XMLConstants.TAG_OBJECT,
                    XMLConstants.NAMESPACE_RESTATOM, result);

            // we are done
            entry.endEntry();
        }
    }

    /**
     * Get content changes.
     */
    public static class GetContentChanges extends AbstractAtomPubServiceCall {
        @Override
        public void serve(CallContext context, CmisService service, String repositoryId, HttpServletRequest request,
                HttpServletResponse response) throws Exception {
            assert context != null;
            assert service != null;
            assert repositoryId != null;
            assert request != null;
            assert response != null;

            // get parameters
            String changeLogToken = getStringParameter(request, Constants.PARAM_CHANGE_LOG_TOKEN);
            Boolean includeProperties = getBooleanParameter(request, Constants.PARAM_PROPERTIES);
            String filter = getStringParameter(request, Constants.PARAM_FILTER);
            Boolean includePolicyIds = getBooleanParameter(request, Constants.PARAM_POLICY_IDS);
            Boolean includeAcl = getBooleanParameter(request, Constants.PARAM_ACL);
            BigInteger maxItems = getBigIntegerParameter(request, Constants.PARAM_MAX_ITEMS);

            // execute
            if (stopBeforeService(service)) {
                return;
            }

            Holder<String> changeLogTokenHolder = new Holder<String>(changeLogToken);
            ObjectList changes = service.getContentChanges(repositoryId, changeLogTokenHolder, includeProperties,
                    filter, includePolicyIds, includeAcl, maxItems, null);

            if (stopAfterService(service)) {
                return;
            }

            if (changes == null) {
                throw new CmisRuntimeException("Changes are null!");
            }

            // set headers
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(Constants.MEDIATYPE_FEED);

            // write XML
            AtomFeed feed = new AtomFeed();
            feed.startDocument(response.getOutputStream(), getNamespaces(service));
            feed.startFeed(true);

            // write basic Atom feed elements
            GregorianCalendar now = new GregorianCalendar();
            feed.writeFeedElements("contentChanges", null, "", "Content Change", now, null, changes.getNumItems());

            // write links
            UrlBuilder baseUrl = compileBaseUrl(request, repositoryId);

            feed.writeServiceLink(baseUrl.toString(), repositoryId);

            UrlBuilder selfLink = compileUrlBuilder(baseUrl, RESOURCE_CHANGES, null);
            selfLink.addParameter(Constants.PARAM_CHANGE_LOG_TOKEN, changeLogToken);
            selfLink.addParameter(Constants.PARAM_PROPERTIES, includeProperties);
            selfLink.addParameter(Constants.PARAM_FILTER, filter);
            selfLink.addParameter(Constants.PARAM_POLICY_IDS, includePolicyIds);
            selfLink.addParameter(Constants.PARAM_ACL, includeAcl);
            selfLink.addParameter(Constants.PARAM_MAX_ITEMS, maxItems);
            feed.writeSelfLink(selfLink.toString(), null);

            if (changeLogTokenHolder.getValue() != null) {
                if (Boolean.TRUE.equals(changes.hasMoreItems())) {
                    UrlBuilder nextLink = compileUrlBuilder(baseUrl, RESOURCE_CHANGES, null);
                    nextLink.addParameter(Constants.PARAM_CHANGE_LOG_TOKEN, changeLogTokenHolder.getValue());
                    nextLink.addParameter(Constants.PARAM_PROPERTIES, includeProperties);
                    nextLink.addParameter(Constants.PARAM_FILTER, filter);
                    nextLink.addParameter(Constants.PARAM_POLICY_IDS, includePolicyIds);
                    nextLink.addParameter(Constants.PARAM_ACL, includeAcl);
                    nextLink.addParameter(Constants.PARAM_MAX_ITEMS, maxItems);
                    feed.writeNextLink(nextLink.toString());
                }

                // The CMIS spec says that the AtomPub binding doesn't provide
                // the change log token. We are doing it anyway.
                XMLStreamWriter writer = feed.getWriter();
                writer.writeStartElement(XMLConstants.PREFIX_APACHE_CHEMISTY, "changeLogToken",
                        XMLConstants.NAMESPACE_APACHE_CHEMISTRY);
                writer.writeNamespace(XMLConstants.PREFIX_APACHE_CHEMISTY, XMLConstants.NAMESPACE_APACHE_CHEMISTRY);
                writer.writeCharacters(changeLogTokenHolder.getValue());
                writer.writeEndElement();
            }

            // write entries
            if (changes.getObjects() != null) {
                AtomEntry entry = new AtomEntry(feed.getWriter());
                for (ObjectData object : changes.getObjects()) {
                    if (object == null) {
                        continue;
                    }
                    writeContentChangesObjectEntry(service, entry, object, null, repositoryId, null, null, baseUrl,
                            false, context.getCmisVersion());
                }
            }

            // write extensions
            feed.writeExtensions(changes);

            // we are done
            feed.endFeed();
            feed.endDocument();
        }
    }
}
