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
 * Access Control Entry (ACE).
 */
public interface Ace extends ExtensionsData {

    /**
     * Returns the ACE principal.
     * 
     * @return the principal
     */
    Principal getPrincipal();

    /**
     * Returns the ACE principal ID.
     * <p>
     * Shortcut for <code>getPrincipal().getId()</code>.
     * 
     * @return the principal ID or {@code null} if no principal is set
     */
    String getPrincipalId();

    /**
     * Returns the permissions granted to the principal.
     * 
     * @return the list of permissions, not {@code null}
     */
    List<String> getPermissions();

    /**
     * Indicates if the ACE was directly applied to the object or has been
     * inherited from another object (for example from the folder it resides
     * in).
     * 
     * @return {@code true} if it is direct ACE, {@code false} if it is
     *         non-direct ACE
     */
    boolean isDirect();
}
