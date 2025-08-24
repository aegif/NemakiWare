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
package org.apache.chemistry.opencmis.commons.data;

import java.util.List;

/**
 * Mutable Access Control Entry (ACE).
 */
public interface MutableAce extends Ace {

    /**
     * Sets the ACE principal.
     * 
     * @param principal
     *            the principal
     */
    void setPrincipal(Principal principal);

    /**
     * Sets the permissions granted to the principal.
     * 
     * @param permissions
     *            the list of permission
     */
    void setPermissions(List<String> permissions);

    /**
     * Sets whether this ACE is a direct ACE or not.
     * 
     * @param direct
     *            {@code true} if the ACE is a direct ACE, {@code false}
     *            otherwise
     */
    void setDirect(boolean direct);
}
