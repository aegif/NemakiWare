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
package org.apache.chemistry.opencmis.client.runtime;

import org.apache.chemistry.opencmis.client.api.ObjectType;
import org.apache.chemistry.opencmis.client.api.OperationContext;
import org.apache.chemistry.opencmis.client.api.Policy;
import org.apache.chemistry.opencmis.client.api.PolicyType;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ObjectData;

public class PolicyImpl extends AbstractFilableCmisObject implements Policy {

    private static final long serialVersionUID = 1L;

    /**
     * Constructor.
     */
    public PolicyImpl(SessionImpl session, ObjectType objectType, ObjectData objectData, OperationContext context) {
        initialize(session, objectType, objectData, context);
    }

    @Override
    public PolicyType getPolicyType() {
        ObjectType objectType = super.getType();
        if (objectType instanceof PolicyType) {
            return (PolicyType) objectType;
        } else {
            throw new ClassCastException("Object type is not a policy type.");
        }
    }

    @Override
    public String getPolicyText() {
        return getPropertyValue(PropertyIds.POLICY_TEXT);
    }
}
