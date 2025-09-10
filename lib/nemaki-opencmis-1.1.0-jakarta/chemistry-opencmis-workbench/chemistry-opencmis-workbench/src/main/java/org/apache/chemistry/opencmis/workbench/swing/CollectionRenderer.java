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

import java.awt.Color;
import java.awt.Component;
import java.util.Collection;
import java.util.GregorianCalendar;

import javax.swing.JTable;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableCellRenderer;

import org.apache.chemistry.opencmis.commons.definitions.Choice;
import org.apache.chemistry.opencmis.workbench.ClientHelper;

public class CollectionRenderer extends DefaultTableCellRenderer {
    private static final long serialVersionUID = 1L;

    private static final String NOT_SET_COLOR;

    private boolean bold = false;

    static {
        Color notSetColor = UIManager.getColor("Label.disabledText");
        if (notSetColor != null) {
            NOT_SET_COLOR = String.format("#%1$02x%2$02x%3$02x", notSetColor.getRed(), notSetColor.getGreen(),
                    notSetColor.getBlue());
        } else {
            NOT_SET_COLOR = "#8e8f91";
        }
    }

    public CollectionRenderer() {
        super();
    }

    public CollectionRenderer(boolean bold) {
        super();
        this.bold = bold;
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
            int row, int column) {
        Component comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

        int height = (int) getPreferredSize().getHeight();
        if (height > (getFontMetrics(getFont()).getHeight() + getInsets().bottom + getInsets().top)) {
            if (table.getRowHeight(row) != height) {
                table.setRowHeight(row, height);
            }
        }

        return comp;
    }

    @Override
    protected void setValue(Object value) {
        Collection<?> col = (Collection<?>) value;

        if (col == null || col.isEmpty()) {
            super.setValue("<html><span><font color=" + NOT_SET_COLOR + "><i>" + (bold ? "<b>" : "") + "not set</i>"
                    + (bold ? "</b>" : "") + "</font></span>");
            return;
        }

        // build string
        StringBuilder sb = new StringBuilder(128);

        sb.append("<html>");

        for (Object o : col) {
            sb.append("<span>"); // workaround for a bug in Swing
            if (bold) {
                sb.append("<b>");
            }
            if (o == null) {
                sb.append("<i>null</i>");
            } else if (o instanceof GregorianCalendar) {
                sb.append(ClientHelper.getDateString((GregorianCalendar) o));
            } else if (o instanceof Choice<?>) {
                Object choiceValue = ((Choice<?>) o).getValue();
                if (choiceValue == null) {
                    sb.append("<i>null</i>");
                } else if (o instanceof GregorianCalendar) {
                    sb.append(ClientHelper.getDateString((GregorianCalendar) o));
                } else {
                    ClientHelper.encodeHtml(sb, choiceValue.toString());
                }
            } else {
                ClientHelper.encodeHtml(sb, o.toString());
            }
            if (bold) {
                sb.append("</b>");
            }
            sb.append("</span><br/>");
        }
        // sb.append("</html>");

        super.setValue(sb.toString());
    }
}
