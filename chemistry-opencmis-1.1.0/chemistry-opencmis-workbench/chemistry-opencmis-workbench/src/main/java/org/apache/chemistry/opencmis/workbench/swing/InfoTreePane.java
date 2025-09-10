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

import java.awt.Dimension;

import javax.swing.JScrollPane;

import org.apache.chemistry.opencmis.workbench.WorkbenchScale;

public class InfoTreePane<E> extends JScrollPane {

    private static final long serialVersionUID = 1L;

    private InfoTree<E> infoTree;

    public InfoTreePane(InfoTree<E> infoTree) {
        this(infoTree, 300, 200);
    }

    public InfoTreePane(InfoTree<E> infoTree, int width, int height) {
        super(infoTree);
        this.infoTree = infoTree;
        setPreferredSize(new Dimension(WorkbenchScale.scaleInt(width), WorkbenchScale.scaleInt(height)));
    }

    public void setData(final E data) {
        infoTree.setData(data);
        setVisible(data != null);
    }
}
