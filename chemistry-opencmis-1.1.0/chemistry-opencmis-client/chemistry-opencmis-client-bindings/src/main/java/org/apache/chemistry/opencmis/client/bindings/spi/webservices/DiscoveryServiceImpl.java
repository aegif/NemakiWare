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
package org.apache.chemistry.opencmis.client.bindings.spi.webservices;

import static org.apache.chemistry.opencmis.commons.impl.WSConverter.convert;
import static org.apache.chemistry.opencmis.commons.impl.WSConverter.convertHolder;
import static org.apache.chemistry.opencmis.commons.impl.WSConverter.setHolderValue;

import java.math.BigInteger;

import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisException;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisObjectListType;
import org.apache.chemistry.opencmis.commons.impl.jaxb.DiscoveryServicePort;
import org.apache.chemistry.opencmis.commons.impl.jaxb.EnumIncludeRelationships;
import org.apache.chemistry.opencmis.commons.spi.DiscoveryService;
import org.apache.chemistry.opencmis.commons.spi.Holder;

/**
 * Discovery Service Web Services client.
 */
public class DiscoveryServiceImpl extends AbstractWebServicesService implements DiscoveryService {

    private final AbstractPortProvider portProvider;

    /**
     * Constructor.
     */
    public DiscoveryServiceImpl(BindingSession session, AbstractPortProvider portProvider) {
        setSession(session);
        this.portProvider = portProvider;
    }

    @Override
    public ObjectList getContentChanges(String repositoryId, Holder<String> changeLogToken, Boolean includeProperties,
            String filter, Boolean includePolicyIds, Boolean includeACL, BigInteger maxItems, ExtensionsData extension) {
        DiscoveryServicePort port = portProvider.getDiscoveryServicePort(getCmisVersion(repositoryId),
                "getContentChanges");

        try {
            jakarta.xml.ws.Holder<String> portChangeLokToken = convertHolder(changeLogToken);
            jakarta.xml.ws.Holder<CmisObjectListType> portObjects = new jakarta.xml.ws.Holder<CmisObjectListType>();

            port.getContentChanges(repositoryId, portChangeLokToken, includeProperties, filter, includePolicyIds,
                    includeACL, maxItems, convert(extension), portObjects);

            setHolderValue(portChangeLokToken, changeLogToken);

            return convert(portObjects.value);
        } catch (CmisException e) {
            throw convertException(e);
        } catch (Exception e) {
            throw new CmisRuntimeException("Error: " + e.getMessage(), e);
        } finally {
            portProvider.endCall(port);
        }
    }

    @Override
    public ObjectList query(String repositoryId, String statement, Boolean searchAllVersions,
            Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter,
            BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {
        DiscoveryServicePort port = portProvider.getDiscoveryServicePort(getCmisVersion(repositoryId), "query");

        try {
            return convert(port.query(repositoryId, statement, searchAllVersions, includeAllowableActions,
                    convert(EnumIncludeRelationships.class, includeRelationships), renditionFilter, maxItems,
                    skipCount, convert(extension)));
        } catch (CmisException e) {
            throw convertException(e);
        } catch (Exception e) {
            throw new CmisRuntimeException("Error: " + e.getMessage(), e);
        } finally {
            portProvider.endCall(port);
        }
    }
}
