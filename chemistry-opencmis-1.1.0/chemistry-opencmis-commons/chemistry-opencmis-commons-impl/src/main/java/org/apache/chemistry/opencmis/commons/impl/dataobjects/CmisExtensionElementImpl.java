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
package org.apache.chemistry.opencmis.commons.impl.dataobjects;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.data.CmisExtensionElement;

public class CmisExtensionElementImpl implements CmisExtensionElement {

    private static final long serialVersionUID = 1L;

    private final String name;
    private final String namespace;
    private final String value;
    private Map<String, String> attributes;
    private final List<CmisExtensionElement> children;

    /**
     * Constructor for a leaf.
     */
    public CmisExtensionElementImpl(String namespace, String name, Map<String, String> attributes, String value) {
        if (name == null) {
            throw new IllegalArgumentException("Name must set!");
        }

        this.name = name;
        this.namespace = namespace;
        this.value = value;
        children = null;

        if (attributes != null) {
            this.attributes = Collections.unmodifiableMap(new HashMap<String, String>(attributes));
        } else {
            this.attributes = null;
        }
    }

    /**
     * Constructor for a node.
     */
    public CmisExtensionElementImpl(String namespace, String name, Map<String, String> attributes,
            List<CmisExtensionElement> children) {
        if (name == null) {
            throw new IllegalArgumentException("Name must set!");
        }

        this.name = name;
        this.namespace = namespace;
        this.value = null;

        if (children != null) {
            this.children = Collections.unmodifiableList(new ArrayList<CmisExtensionElement>(children));
        } else {
            this.children = null;
        }

        if (attributes != null) {
            this.attributes = Collections.unmodifiableMap(new HashMap<String, String>(attributes));
        } else {
            this.attributes = null;
        }
    }

    /**
     * Copy constructor.
     */
    public CmisExtensionElementImpl(CmisExtensionElement element) {
        if (element == null) {
            throw new IllegalArgumentException("Element must set!");
        }
        if (element.getName() == null) {
            throw new IllegalArgumentException("Name must set!");
        }

        this.name = element.getName();
        this.namespace = element.getNamespace();
        this.value = element.getValue();
        this.children = element.getChildren();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getNamespace() {
        return namespace;
    }

    @Override
    public String getValue() {
        return value;
    }

    @Override
    public List<CmisExtensionElement> getChildren() {
        if (children == null) {
            return Collections.emptyList();
        }
        
        return children;
    }

    @Override
    public Map<String, String> getAttributes() {
        if (attributes == null) {
            return Collections.emptyMap();
        }

        return attributes;
    }

    public String toTreeString(int level) {
        StringBuilder sb = new StringBuilder(512);
        nextTreelevel(sb, level);
        return sb.toString();
    }

    private void nextTreelevel(StringBuilder sb, int level) {
        for (int i = 0; i < level; i++) {
            sb.append("  ");
        }

        sb.append(namespace == null ? "" : "{" + namespace + "}")
                .append(name)
                .append(" ")
                .append(attributes == null ? "[]" : attributes)
                .append(": ");

        if (children == null || children.isEmpty()) {
            sb.append(value);
            sb.append('\n');
        } else {
            sb.append('\n');

            for (CmisExtensionElement element : children) {
                if (element instanceof CmisExtensionElementImpl) {
                    sb.append(((CmisExtensionElementImpl) element).toTreeString(level + 1));
                } else if (element != null) {
                    for (int i = 0; i < level + 1; i++) {
                        sb.append("  ");
                    }
                    sb.append(element.toString());
                    sb.append('\n');
                }
            }
        }
    }

    @Override
    public String toString() {
        return (namespace == null ? "" : "{" + namespace + "}")
                + name + " " 
                + (attributes == null ? "[]" : attributes) + ": "
                + (children == null || children.isEmpty() ? value : children.toString());
    }
}
