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

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Represents one node in the extension tree.
 * <p>
 * An extension element can have a value or children, but not both.
 */
public interface CmisExtensionElement extends Serializable {

    /**
     * Returns the name of the extension.
     * 
     * @return the name, not {@code null}
     */
    String getName();

    /**
     * Returns the namespace of the extension.
     * <p>
     * The namespace must follow the XML rules for namespaces. Don't rely on
     * namespaces because the Browser binding does not support namespaces!
     * 
     * @return the extension namespace or {@code null} if the namespace is not
     *         set or not supported by the binding
     */
    String getNamespace();

    /**
     * Returns the value of the extension as a String.
     * 
     * @return the extension value as a String or {@code null} if the value is
     *         {@code null} or the extension has children
     */
    String getValue();

    /**
     * Returns the attributes of the extension.
     * <p>
     * The attributes must follow the XML rules for attributes. Don't rely on
     * attributes because the Browser binding does not support attributes!
     * 
     * @return the extension attributes or {@code null} if the attributes are
     *         not set or not supported by the binding
     */
    Map<String, String> getAttributes();

    /**
     * Returns the children of this extension.
     * 
     * @return the children of this extension or {@code null} if the extension
     *         has a value
     */
    List<CmisExtensionElement> getChildren();
}
