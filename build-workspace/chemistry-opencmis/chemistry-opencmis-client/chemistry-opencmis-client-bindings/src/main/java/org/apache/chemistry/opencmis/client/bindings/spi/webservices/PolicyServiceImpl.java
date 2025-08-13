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
import static org.apache.chemistry.opencmis.commons.impl.WSConverter.convertExtensionHolder;
import static org.apache.chemistry.opencmis.commons.impl.WSConverter.setExtensionValues;

import java.util.ArrayList;
import java.util.List;

import jakarta.xml.ws.Holder;

import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisException;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisExtensionType;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisObjectType;
import org.apache.chemistry.opencmis.commons.impl.jaxb.PolicyServicePort;
import org.apache.chemistry.opencmis.commons.spi.PolicyService;

/**
 * Policy Service Web Services client.
 */
public class PolicyServiceImpl extends AbstractWebServicesService implements PolicyService {

    private final AbstractPortProvider portProvider;

    /**
     * Constructor.
     */
    public PolicyServiceImpl(BindingSession session, AbstractPortProvider portProvider) {
        setSession(session);
        this.portProvider = portProvider;
    }

    @Override
    public void applyPolicy(String repositoryId, String policyId, String objectId, ExtensionsData extension) {
        PolicyServicePort port = portProvider.getPolicyServicePort(getCmisVersion(repositoryId), "applyPolicy");

        try {
            Holder<CmisExtensionType> portExtension = convertExtensionHolder(extension);

            port.applyPolicy(repositoryId, policyId, objectId, portExtension);

            setExtensionValues(portExtension, extension);
        } catch (CmisException e) {
            throw convertException(e);
        } catch (Exception e) {
            throw new CmisRuntimeException("Error: " + e.getMessage(), e);
        } finally {
            portProvider.endCall(port);
        }
    }

    @Override
    public void removePolicy(String repositoryId, String policyId, String objectId, ExtensionsData extension) {
        PolicyServicePort port = portProvider.getPolicyServicePort(getCmisVersion(repositoryId), "removePolicy");

        try {
            Holder<CmisExtensionType> portExtension = convertExtensionHolder(extension);

            port.removePolicy(repositoryId, policyId, objectId, portExtension);

            setExtensionValues(portExtension, extension);
        } catch (CmisException e) {
            throw convertException(e);
        } catch (Exception e) {
            throw new CmisRuntimeException("Error: " + e.getMessage(), e);
        } finally {
            portProvider.endCall(port);
        }
    }

    @Override
    public List<ObjectData> getAppliedPolicies(String repositoryId, String objectId, String filter,
            ExtensionsData extension) {
        PolicyServicePort port = portProvider.getPolicyServicePort(getCmisVersion(repositoryId), "getAppliedPolicies");

        try {
            List<CmisObjectType> policyList = port.getAppliedPolicies(repositoryId, objectId, filter,
                    convert(extension));

            List<ObjectData> result = new ArrayList<ObjectData>();

            // no list?
            if (policyList == null) {
                return result;
            }

            // convert list
            for (CmisObjectType policy : policyList) {
                result.add(convert(policy));
            }

            return result;
        } catch (CmisException e) {
            throw convertException(e);
        } catch (Exception e) {
            throw new CmisRuntimeException("Error: " + e.getMessage(), e);
        } finally {
            portProvider.endCall(port);
        }
    }
}
