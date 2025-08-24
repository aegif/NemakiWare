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

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.Constants;
import org.apache.chemistry.opencmis.commons.impl.UrlBuilder;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.apache.chemistry.opencmis.commons.server.ObjectInfo;
import org.apache.chemistry.opencmis.server.shared.TempStoreOutputStreamFactory;

/**
 * Policy Service operations.
 */
public class PolicyService {

    public abstract static class AbstractPoliciesServiceCall extends AbstractAtomPubServiceCall {
        /**
         * Writes an entry that is attached to an object.
         */
        protected void writePolicyEntry(CmisService service, AtomEntry entry, String objectId, ObjectData policy,
                String repositoryId, UrlBuilder baseUrl, CmisVersion cmisVersion) throws Exception {
            ObjectInfo info = service.getObjectInfo(repositoryId, policy.getId());
            if (info == null) {
                throw new CmisRuntimeException("Object Info not found!");
            }

            // start
            entry.startEntry(false);

            // write the object
            entry.writeObject(policy, info, null, null, null, null, cmisVersion);

            // write links
            UrlBuilder selfLink = compileUrlBuilder(baseUrl, RESOURCE_POLICIES, objectId);
            selfLink.addParameter(Constants.PARAM_POLICY_ID, info.getId());
            entry.writeSelfLink(selfLink.toString(), null);

            // we are done
            entry.endEntry();
        }
    }

    /**
     * Get applied policies.
     */
    public static class GetAppliedPolicies extends AbstractPoliciesServiceCall {
        @Override
        public void serve(CallContext context, CmisService service, String repositoryId, HttpServletRequest request,
                HttpServletResponse response) throws Exception {
            assert context != null;
            assert service != null;
            assert repositoryId != null;
            assert request != null;
            assert response != null;

            // get parameters
            String objectId = getStringParameter(request, Constants.PARAM_ID);
            String filter = getStringParameter(request, Constants.PARAM_FILTER);

            // execute
            if (stopBeforeService(service)) {
                return;
            }

            List<ObjectData> policies = service.getAppliedPolicies(repositoryId, objectId, filter, null);

            if (stopAfterService(service)) {
                return;
            }

            if (policies == null) {
                throw new CmisRuntimeException("Policies are null!");
            }

            ObjectInfo objectInfo = service.getObjectInfo(repositoryId, objectId);
            if (objectInfo == null) {
                throw new CmisRuntimeException("Object Info is missing!");
            }

            // set headers
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(Constants.MEDIATYPE_FEED);

            // write XML
            AtomFeed feed = new AtomFeed();
            feed.startDocument(response.getOutputStream(), getNamespaces(service));
            feed.startFeed(true);

            // write basic Atom feed elements
            feed.writeFeedElements(objectInfo.getId(), objectInfo.getAtomId(), objectInfo.getCreatedBy(),
                    objectInfo.getName(), objectInfo.getLastModificationDate(), null, null);

            // write links
            UrlBuilder baseUrl = compileBaseUrl(request, repositoryId);

            feed.writeServiceLink(baseUrl.toString(), repositoryId);

            UrlBuilder selfLink = compileUrlBuilder(baseUrl, RESOURCE_POLICIES, objectInfo.getId());
            selfLink.addParameter(Constants.PARAM_FILTER, filter);
            feed.writeSelfLink(selfLink.toString(), null);

            // write entries
            AtomEntry entry = new AtomEntry(feed.getWriter());
            for (ObjectData policy : policies) {
                if (policy == null) {
                    continue;
                }
                writePolicyEntry(service, entry, objectInfo.getId(), policy, repositoryId, baseUrl,
                        context.getCmisVersion());
            }

            // we are done
            feed.endFeed();
            feed.endDocument();
        }
    }

    /**
     * Apply policy.
     */
    public static class ApplyPolicy extends AbstractPoliciesServiceCall {
        @Override
        public void serve(CallContext context, CmisService service, String repositoryId, HttpServletRequest request,
                HttpServletResponse response) throws Exception {
            assert context != null;
            assert service != null;
            assert repositoryId != null;
            assert request != null;
            assert response != null;

            // get parameters
            String objectId = getStringParameter(request, Constants.PARAM_ID);

            TempStoreOutputStreamFactory streamFactory = (TempStoreOutputStreamFactory) context
                    .get(CallContext.STREAM_FACTORY);
            AtomEntryParser parser = new AtomEntryParser(request.getInputStream(), streamFactory);

            // execute
            try {
                if (stopBeforeService(service)) {
                    return;
                }

                service.applyPolicy(repositoryId, parser.getId(), objectId, null);

                if (stopAfterService(service)) {
                    return;
                }
            } finally {
                parser.release();
            }

            ObjectInfo objectInfo = service.getObjectInfo(repositoryId, objectId);
            if (objectInfo == null) {
                throw new CmisRuntimeException("Object Info is missing!");
            }

            ObjectData object = objectInfo.getObject();
            if (object == null) {
                throw new CmisRuntimeException("Object is null!");
            }

            // set headers
            UrlBuilder baseUrl = compileBaseUrl(request, repositoryId);

            response.setStatus(HttpServletResponse.SC_CREATED);
            response.setContentType(Constants.MEDIATYPE_ENTRY);
            response.setHeader("Location", compileUrl(baseUrl, RESOURCE_ENTRY, object.getId()));

            // write XML
            AtomEntry entry = new AtomEntry();
            entry.startDocument(response.getOutputStream(), getNamespaces(service));
            writeObjectEntry(service, entry, object, null, repositoryId, null, null, baseUrl, true,
                    context.getCmisVersion());
            entry.endDocument();
        }
    }

    /**
     * Remove policy.
     */
    public static class RemovePolicy extends AbstractAtomPubServiceCall {
        @Override
        public void serve(CallContext context, CmisService service, String repositoryId, HttpServletRequest request,
                HttpServletResponse response) throws Exception {
            assert context != null;
            assert service != null;
            assert repositoryId != null;
            assert request != null;
            assert response != null;

            // get parameters
            String objectId = getStringParameter(request, Constants.PARAM_ID);
            String policyId = getStringParameter(request, Constants.PARAM_POLICY_ID);

            // execute
            if (stopBeforeService(service)) {
                return;
            }

            service.removePolicy(repositoryId, policyId, objectId, null);

            if (stopAfterService(service)) {
                return;
            }

            // set headers
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        }
    }
}
