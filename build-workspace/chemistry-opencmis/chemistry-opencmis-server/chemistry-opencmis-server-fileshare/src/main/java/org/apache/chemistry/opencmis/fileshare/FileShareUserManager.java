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
package org.apache.chemistry.opencmis.fileshare;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.server.CallContext;

/**
 * Manages users for the FileShare repository.
 */
public class FileShareUserManager {

    private final Map<String, String> logins;

    public FileShareUserManager() {
        logins = new HashMap<String, String>();
    }

    /**
     * Returns all logins.
     */
    public synchronized Collection<String> getLogins() {
        return logins.keySet();
    }

    /**
     * Adds a login.
     */
    public synchronized void addLogin(String username, String password) {
        if (username == null || password == null) {
            return;
        }

        logins.put(username.trim(), password);
    }

    /**
     * Takes user and password from the CallContext and checks them.
     */
    public synchronized String authenticate(CallContext context) {
        // try to get the remote user first
        // HttpServletRequest request = (HttpServletRequest)
        // context.get(CallContext.HTTP_SERVLET_REQUEST);
        // if (request != null && request.getRemoteUser() != null) {
        // return request.getRemoteUser();
        // }

        // check user and password
        if (!authenticate(context.getUsername(), context.getPassword())) {
            throw new CmisPermissionDeniedException("Invalid username or password.");
        }

        return context.getUsername();
    }

    /**
     * Authenticates a user against the configured logins.
     */
    private synchronized boolean authenticate(String username, String password) {
        String pwd = logins.get(username);
        if (pwd == null) {
            return false;
        }

        return pwd.equals(password);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(128);

        for (String user : logins.keySet()) {
            sb.append('[');
            sb.append(user);
            sb.append(']');
        }

        return sb.toString();
    }
}
