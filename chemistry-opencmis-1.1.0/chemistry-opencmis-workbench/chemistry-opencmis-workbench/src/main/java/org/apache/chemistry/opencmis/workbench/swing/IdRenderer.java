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

import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;

import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.workbench.ClientHelper;

public class IdRenderer extends DefaultTableCellRenderer {
    private static final long serialVersionUID = 1L;

    public IdRenderer() {
        super();
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
            int row, int column) {
        Component comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
        comp.setForeground(isSelected ? ClientHelper.LINK_SELECTED_COLOR : ClientHelper.LINK_COLOR);

        return comp;
    }

    @Override
    public void setValue(Object value) {
        String text = "";
        if (value instanceof ObjectId) {
            if (((ObjectId) value).getId() != null) {
                StringBuilder sb = new StringBuilder(128);
                sb.append("<html><u>");
                ClientHelper.encodeHtml(sb, ((ObjectId) value).getId());
                sb.append("</u></html>");
                text = sb.toString();
            }
        }

        setText(text);
    }
}
