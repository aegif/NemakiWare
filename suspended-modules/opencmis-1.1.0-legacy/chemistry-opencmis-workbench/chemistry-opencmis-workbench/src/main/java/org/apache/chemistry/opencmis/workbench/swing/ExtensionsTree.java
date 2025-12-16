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
package org.apache.chemistry.opencmis.workbench.swing;

import static org.apache.chemistry.opencmis.commons.impl.CollectionsHelper.isNotEmpty;
import static org.apache.chemistry.opencmis.commons.impl.CollectionsHelper.isNullOrEmpty;

import java.util.List;

import javax.swing.Icon;
import javax.swing.tree.DefaultMutableTreeNode;

import org.apache.chemistry.opencmis.commons.data.CmisExtensionElement;
import org.apache.chemistry.opencmis.workbench.ClientHelper;
import org.apache.chemistry.opencmis.workbench.icons.ExtensionIcon;

public class ExtensionsTree extends InfoTree<List<CmisExtensionElement>> {

    private static final long serialVersionUID = 1L;

    private static final Icon EXTENSION_ICON = new ExtensionIcon(ClientHelper.OBJECT_ICON_SIZE,
            ClientHelper.OBJECT_ICON_SIZE);

    public ExtensionsTree() {
        super();
    }

    public ExtensionsTree(final List<CmisExtensionElement> extensions) {
        super(extensions);
    }

    @Override
    protected void buildTree(DefaultMutableTreeNode parent, List<CmisExtensionElement> extensions) {
        if (isNullOrEmpty(extensions)) {
            return;
        }

        for (CmisExtensionElement ext : extensions) {
            DefaultMutableTreeNode node = new DefaultMutableTreeNode(new ExtensionNode(ext));
            parent.add(node);

            if (isNotEmpty(ext.getChildren())) {
                buildTree(node, ext.getChildren());
            }
        }
    }

    @Override
    protected Icon getIcon() {
        return EXTENSION_ICON;
    }

    private static class ExtensionNode {
        private final CmisExtensionElement extension;

        public ExtensionNode(CmisExtensionElement extension) {
            this.extension = extension;
        }

        @Override
        public String toString() {
            return (extension.getNamespace() == null ? "" : "{" + extension.getNamespace() + "}") + extension.getName()
                    + (!extension.getAttributes().isEmpty() ? " " + extension.getAttributes() : "")
                    + (extension.getChildren().isEmpty() ? ": " + extension.getValue() : "");
        }
    }
}
