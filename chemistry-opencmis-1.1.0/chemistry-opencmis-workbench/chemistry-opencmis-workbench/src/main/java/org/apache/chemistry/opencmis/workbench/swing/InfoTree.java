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

import java.awt.Component;

import javax.swing.Icon;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeSelectionModel;

public abstract class InfoTree<E> extends JTree {

    private static final long serialVersionUID = 1L;

    public InfoTree() {
        super();

        setRootVisible(false);
        setCellRenderer(new DataTreeCellRenderer(getIcon()));
        getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        setModel(null);
    }

    public InfoTree(final E data) {
        this();
        setData(data);
    }

    public final void setData(final E data) {
        DefaultMutableTreeNode extRootNode = new DefaultMutableTreeNode("data");
        buildTree(extRootNode, data);

        setModel(new DefaultTreeModel(extRootNode));

        for (int i = 0; i < getRowCount(); i++) {
            expandRow(i);
        }
    }

    protected abstract void buildTree(DefaultMutableTreeNode parent, E data);

    protected abstract Icon getIcon();

    static class DataTreeCellRenderer extends DefaultTreeCellRenderer {

        private static final long serialVersionUID = 1L;

        private final Icon icon;

        public DataTreeCellRenderer(Icon icon) {
            this.icon = icon;
        }

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded,
                boolean leaf, int row, boolean hasFocus) {
            Component comp = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            setIcon(icon);

            return comp;
        }
    }
}
