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
package org.apache.chemistry.opencmis.commons.impl.json.parser;

import java.io.IOException;

/**
 * A simplified and stoppable SAX-like content handler for stream processing of
 * JSON text.
 * 
 * (Taken from JSON.simple &lt;http://code.google.com/p/json-simple/&gt; and
 * modified for OpenCMIS.)
 * 
 * @see org.xml.sax.ContentHandler
 * @see JSONParser#parse(java.io.Reader, ContentHandler, boolean)
 * 
 * @author FangYidong&lt;fangyidong@yahoo.com.cn&gt;
 */
public interface ContentHandler {
    /**
     * Receive notification of the beginning of JSON processing. The parser will
     * invoke this method only once.
     * 
     * @throws JSONParseException
     *             - JSONParser will stop and throw the same exception to the
     *             caller when receiving this exception.
     */
    void startJSON() throws JSONParseException, IOException;

    /**
     * Receive notification of the end of JSON processing.
     * 
     * @throws JSONParseException
     */
    void endJSON() throws JSONParseException, IOException;

    /**
     * Receive notification of the beginning of a JSON object.
     * 
     * @return false if the handler wants to stop parsing after return.
     * @throws JSONParseException
     *             - JSONParser will stop and throw the same exception to the
     *             caller when receiving this exception.
     * @see #endJSON
     */
    boolean startObject() throws JSONParseException, IOException;

    /**
     * Receive notification of the end of a JSON object.
     * 
     * @return false if the handler wants to stop parsing after return.
     * @throws JSONParseException
     * 
     * @see #startObject
     */
    boolean endObject() throws JSONParseException, IOException;

    /**
     * Receive notification of the beginning of a JSON object entry.
     * 
     * @param key
     *            - Key of a JSON object entry.
     * 
     * @return false if the handler wants to stop parsing after return.
     * @throws JSONParseException
     * 
     * @see #endObjectEntry
     */
    boolean startObjectEntry(String key) throws JSONParseException, IOException;

    /**
     * Receive notification of the end of the value of previous object entry.
     * 
     * @return false if the handler wants to stop parsing after return.
     * @throws JSONParseException
     * 
     * @see #startObjectEntry
     */
    boolean endObjectEntry() throws JSONParseException, IOException;

    /**
     * Receive notification of the beginning of a JSON array.
     * 
     * @return false if the handler wants to stop parsing after return.
     * @throws JSONParseException
     * 
     * @see #endArray
     */
    boolean startArray() throws JSONParseException, IOException;

    /**
     * Receive notification of the end of a JSON array.
     * 
     * @return false if the handler wants to stop parsing after return.
     * @throws JSONParseException
     * 
     * @see #startArray
     */
    boolean endArray() throws JSONParseException, IOException;

    /**
     * Receive notification of the JSON primitive values: java.lang.String,
     * java.lang.Number, java.lang.Boolean null
     * 
     * @param value
     *            - Instance of the following: java.lang.String,
     *            java.lang.Number, java.lang.Boolean null
     * 
     * @return false if the handler wants to stop parsing after return.
     * @throws JSONParseException
     */
    boolean primitive(Object value) throws JSONParseException, IOException;
}
