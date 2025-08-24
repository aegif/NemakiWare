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
package org.apache.chemistry.opencmis.client.api;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URL;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.endpoints.CmisAuthentication;
import org.apache.chemistry.opencmis.commons.endpoints.CmisEndpointsDocument;

public interface CmisEndpointDocumentReader {

    /**
     * Reads a CMIS Endpoint Document from a URL.
     */
    CmisEndpointsDocument read(URL url) throws IOException;

    /**
     * Reads a CMIS Endpoint Document from a file.
     */
    CmisEndpointsDocument read(File file) throws IOException;

    /**
     * Reads a CMIS Endpoint Document from a stream.
     */
    CmisEndpointsDocument read(InputStream in) throws IOException;

    /**
     * Reads a CMIS Endpoint Document from a reader.
     */
    CmisEndpointsDocument read(Reader in) throws IOException;

    /**
     * Reads a CMIS Endpoint Document from a String.
     */
    CmisEndpointsDocument read(String in);

    /**
     * Prepares session parameters based on the provides authentication
     * information.
     * 
     * @param authentication
     *            a CMIS authentication object
     * 
     * @return an incomplete set of session parameters, never {@code null}
     */
    Map<String, String> pepareSessionParameters(CmisAuthentication authentication);

}
